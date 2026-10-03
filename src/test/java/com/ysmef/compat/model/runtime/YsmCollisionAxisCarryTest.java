package com.ysmef.compat.model.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collision response must not damp the piece along an axis this call did not build.
 *
 * <p>{@code resolveCollisions} builds its correction axis on one branch of one loop iteration and
 * reads it after the loop, on every call whose loop touched anything - and two of the loop's exits
 * leave with {@code touched} true without ever reaching that write (the degenerate correction at the
 * pivot, and the radial correction no rotation can apply). The field lives on
 * {@link YsmDynamicBoneSolver#INSTANCE}, which is shared by every piece, every model and every
 * frame, so the value read there belongs to whatever last wrote one: an earlier iteration, an earlier
 * piece, an earlier frame, an earlier model. The projection is not a no-op - it removes the whole
 * component of the angular velocity along that foreign axis, which is the approach velocity of a
 * contact that is not this one.
 *
 * <p>No rig is needed. The contamination is a field, so two calls in sequence are enough: the first
 * builds an axis, the second touches without building one, and the second must not be able to tell
 * what the first did. This is the reduced form of the bare-pair finding of round 8 (two states
 * handed identical inputs back to back disagreeing by 0.175 on EF walk), which round 9 traced to
 * exactly this read.
 *
 * <p>Both calls go through the public {@link YsmDynamicBoneSolver#update} entry point the frame path
 * uses, with hand-written volumes standing in for the body: a volume that is always inside and shoves
 * the point sideways reaches the write, and a volume that swallows the point back onto the pivot
 * reaches the degenerate exit. Both are deterministic, so the two arms of the test differ in nothing
 * but the axis their predecessor left.
 */
class YsmCollisionAxisCarryTest {

    /** The pivot every piece in this test turns about, at the model origin. */
    private static final Vector3f PIVOT = new Vector3f();

    /** The pose's direction: straight down, which is also the spring's target (no follow weight). */
    private static final Vector3f REST = new Vector3f(0.0F, -1.0F, 0.0F);

    /** A body at rest, and no gravity: the piece's state is then the whole of the input. */
    private static final Vector3f STILL = new Vector3f();
    private static final float WEIGHTLESS = 0.0F;
    private static final float NO_AIR = 0.0F;
    private static final float NO_FOLLOW = 0.0F;

    private static final float LEVER = 0.4F;
    private static final float FREQUENCY = 1.0F;
    private static final float DAMPING = 0.3F;
    private static final float MASS = 1.0F;
    private static final float MAX_ANGLE = 0.6F;
    private static final float SEGMENT_RADIUS = 0.05F;

    /**
     * A step short enough that one call barely integrates: the piece turns a thousandth of a radian
     * and the geometry the two arms are handed is the geometry they were built with. What the test
     * measures is the response, not the integration.
     */
    private static final float DT = 1.0E-4F;

    /**
     * The swing both arms carry into the collided call, radians/s. Deliberately not aligned with
     * either axis a predecessor can build, so a foreign projection removes a large part of it and a
     * direction-only assertion cannot pass by luck.
     */
    private static final Vector3f SPIN = new Vector3f(3.0F, 0.0F, 12.0F);

    /**
     * A volume that always contains the point and shoves it a fixed way. The correction axis the
     * solver builds from that shove is {@code direction x (com - pivot)}, which for a downward piece
     * is perpendicular to the shove - so a +X shove builds an axis along Z and a +Z shove builds one
     * along X. Two of these are the two different predecessors the test needs.
     */
    private static final class AlwaysPushes implements YsmDynamicBoneSolver.Colliders {
        private final Vector3f push;

        AlwaysPushes(float x, float y, float z) {
            this.push = new Vector3f(x, y, z);
        }

        @Override
        public int count() {
            return 1;
        }

        @Override
        public boolean resolve(Vector3f point, Vector3f velocity, float radius, int index) {
            point.add(this.push);
            return true;
        }
    }

    /**
     * A volume that swallows the point back onto the pivot. The push the solver measures is then the
     * whole lever - so the loop records a correction and `touched` becomes true - and the axis it
     * builds from {@code com - pivot} is the zero vector, which is the exit that leaves the loop
     * without writing {@code contactAxis}.
     */
    private static final class SwallowsToThePivot implements YsmDynamicBoneSolver.Colliders {
        @Override
        public int count() {
            return 1;
        }

        @Override
        public boolean resolve(Vector3f point, Vector3f velocity, float radius, int index) {
            point.set(PIVOT);
            return true;
        }
    }

    // ------------------------------------------------------------------
    // The test
    // ------------------------------------------------------------------

    /**
     * The same piece, the same pose, the same volume, two different predecessors: the response must
     * not be able to tell them apart, and it must not lose the direction of the swing it was handed.
     */
    @Test
    void aCallThatBuildsNoAxisDoesNotDampAlongTheLastCallsAxis() {
        Vector3f poisonedByX = collidedAfter(new AlwaysPushes(0.01F, 0.0F, 0.0F));
        Vector3f poisonedByZ = collidedAfter(new AlwaysPushes(0.0F, 0.0F, 0.01F));

        // 1. The call reached the response: the contact friction below the projection ran, so the
        //    speed is strictly lower than the one the piece carried in. Without this, a call that
        //    never touched anything would satisfy every assertion below for the wrong reason.
        assertTrue(poisonedByX.length() < SPIN.length(),
                "the collided call must have touched the volume and paid the friction: in "
                        + SPIN.length() + " rad/s, out " + poisonedByX.length());

        // 2. The direction of the swing is untouched. This is the claim in one line: the projection
        //    removes the component of the angular velocity along the correction axis of THIS contact,
        //    and this contact has no correction axis. An earlier call's axis is not this contact's.
        assertEquals(1.0F, cosine(poisonedByX, SPIN), 1.0E-5F,
                "the collided call damped the swing along an axis it did not build: in " + SPIN
                        + ", out " + poisonedByX);

        // 3. The outcome is a function of the call's own inputs. Two predecessors that build two
        //    different axes leave the second call bit-identical; before the guard, one victim keeps
        //    only the X part of its spin and the other only the Z part.
        assertEquals(0.0F, poisonedByX.distance(poisonedByZ), 1.0E-6F,
                "the same call with the same inputs produced two outcomes, which differ only in the "
                        + "axis the previous call left behind: " + poisonedByX + " vs " + poisonedByZ);
    }

    // ------------------------------------------------------------------
    // The harness
    // ------------------------------------------------------------------

    /**
     * Drive the poisoned arm, then the collided arm, and report the collided piece's angular
     * velocity. The two pieces are built by the same code with the same inputs, so the only thing
     * that differs between two calls of this method is {@code poison}.
     */
    private static Vector3f collidedAfter(YsmDynamicBoneSolver.Colliders poison) {
        YsmDynamicBoneSolver.SegmentState poisoned = piece();
        drive(poisoned, poison);

        YsmDynamicBoneSolver.SegmentState collided = piece();
        drive(collided, new SwallowsToThePivot());
        return new Vector3f(collided.angularVelocity);
    }

    /** A piece resting on its pose, initialized, with the swing the test hands it. */
    private static YsmDynamicBoneSolver.SegmentState piece() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        // The first call establishes the state from the pose and returns; the volumes are not
        // consulted on it, which is the case every caller starts in.
        drive(state, YsmDynamicBoneSolver.NO_COLLIDERS);
        state.angularVelocity.set(SPIN);
        return state;
    }

    private static void drive(YsmDynamicBoneSolver.SegmentState state,
                              YsmDynamicBoneSolver.Colliders colliders) {
        Quaternionf out = new Quaternionf();
        YsmDynamicBoneSolver.INSTANCE.update(state, WEIGHTLESS, NO_AIR, NO_FOLLOW, REST, PIVOT, REST,
                LEVER, FREQUENCY, DAMPING, MASS, MAX_ANGLE, STILL, colliders, SEGMENT_RADIUS, null,
                DT, out);
    }

    /** The cosine between two vectors, both non-zero here. */
    private static float cosine(Vector3f a, Vector3f b) {
        return a.dot(b) / (a.length() * b.length());
    }
}
