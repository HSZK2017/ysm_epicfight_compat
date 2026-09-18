package com.ysmef.compat.model.runtime;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pendulum dynamics: the moment arm, gravity, the spring, the drag that makes
 * cloth trail, the angular limit and collision.
 *
 * <p>These pin the three properties the in-game report was about. A piece must swing about
 * <i>its own</i> pivot (which the caller's transform does) and answer a force the way its
 * length says it should; it must come to rest hanging rather than staying where the pose
 * left it; and it must not be able to pass through the body.
 *
 * <p>{@code YsmDynamicBoneSolver.INSTANCE} is shared, so every test builds its own
 * {@link YsmDynamicBoneSolver.SegmentState}; the solver itself holds no per-segment state.
 */
class YsmDynamicBoneSolverTest {

    private static final float HANGING_DOWN = -1.0F;
    private static final Vector3f DOWN = new Vector3f(0.0F, HANGING_DOWN, 0.0F);
    private static final Vector3f STILL = new Vector3f();

    /** A collider set with one sphere, for the collision tests. */
    private static final class OneSphere implements YsmDynamicBoneSolver.Colliders {
        final Vector3f centre = new Vector3f();
        float radius = 0.5F;
        /** When true the volume is skipped, standing in for "contains the pivot". */
        boolean skipped;
        /** When true the volume is skipped only because it contains the rest centre of mass. */
        boolean skipBecauseRestIsInside;
        /** The rest centre the solver offered, so the rule can be asserted rather than assumed. */
        final Vector3f seenRestCentre = new Vector3f();

        @Override
        public int count() {
            return 1;
        }

        @Override
        public boolean resolve(Vector3f point, Vector3f velocity, float pointRadius, int index) {
            return YsmBodyColliders.pushOutOfSphere(point, velocity, pointRadius,
                    centre.x, centre.y, centre.z, radius);
        }

        @Override
        public boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
            if (restCentre != null) {
                seenRestCentre.set(restCentre);
            }
            return skipped || skipBecauseRestIsInside;
        }
    }

    // ------------------------------------------------------------------
    // The moment arm
    // ------------------------------------------------------------------

    /**
     * The whole reason for the rewrite: for the same push, a longer piece turns less.
     *
     * <p>This is what the old point-on-a-sphere model could not express. Its tip was
     * massless, so the spring's stiffness alone set the response and a metre of skirt
     * answered at the same rate as a lock of hair. Here the pivot's acceleration is a
     * torque that grows with the lever while the inertia resisting it grows with the lever
     * squared, so the long piece lags - which is what "the skirt swings and the fringe
     * flickers" means physically.
     *
     * <p>The push is deliberately gentle, so this measures the response below anything that
     * could saturate a force bound. The stronger case, where the old solver's outside-force
     * ceiling used to hide the lever entirely, is {@link #aSustainedPushTurnsALongPieceLess}.
     */
    @Test
    void aLongerPieceTurnsMoreSlowlyThanAShortOne() {
        float shortSwing = swingUnderPivotAcceleration(0.1F);
        float longSwing = swingUnderPivotAcceleration(0.8F);

        assertTrue(shortSwing > 0.0F, "the push must actually move the piece (got " + shortSwing + ")");
        assertTrue(longSwing < shortSwing,
                "a longer lever must answer more slowly (short " + shortSwing + " vs long " + longSwing + ")");
    }

    /** Drive one segment sideways and report how far it turned in radians. */
    private static float swingUnderPivotAcceleration(float lever) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);

        // Settle, then yank the pivot along +X for a fixed number of frames.
        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
                1.2F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        for (int i = 1; i <= 12; i++) {
            // 0.5 blocks/s^2, which on this test's 1 Hz spring stays under the ceiling for both
            // levers; see the test comment.
            pivot.set(0.5F * 0.5F * (i * 0.016F) * (i * 0.016F), 0.0F, 0.0F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
                    1.2F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        }
        return YsmDynamicBoneSolver.angleBetween(rest, state.direction);
    }

    /**
     * Gravity is what makes a piece hang. Given a rest direction the pose left pointing
     * sideways and a still pivot, the piece must droop downwards - and it must do so even
     * though nothing is moving, which is the case the old model explicitly skipped.
     */
    @Test
    void gravityDroopsAPieceThePoseLeftStickingOut() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(1.0F, 0.0F, 0.0F);

        for (int i = 0; i < 60; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.4F, 1.0F, 0.6F, 1.0F,
                    1.2F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        }

        assertTrue(state.direction.y < -0.05F,
                "a sideways piece must fall under gravity (y=" + state.direction.y + ")");
        assertEquals(1.0F, state.direction.length(), 1.0E-3F, "and stay on its lever");
    }

    /** With gravity zeroed and a still pivot, the piece holds the direction it was given. */
    @Test
    void aRestDirectionThatMatchesThePieceKeepsItStill() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);

        for (int i = 0; i < 40; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.3F, 2.0F, 1.0F, 1.0F,
                    1.2F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        }

        assertTrue(YsmDynamicBoneSolver.angleBetween(rest, state.direction) < 0.02F,
                "a settled piece must not drift away from where the animation put it");
        assertEquals(1.0F, out.w(), 1.0E-2F, "and the swing it reports must be a rotation, not a stretch");
    }

    // ------------------------------------------------------------------
    // Drag
    // ------------------------------------------------------------------

    /**
     * The running case: a body moving forward in a still world means air moving backwards in
     * the body's own frame, and a piece the body carries therefore feels wind and trails
     * behind it. Without this a skirt only reacts while the legs accelerate, and settles the
     * moment the run is steady.
     */
    @Test
    void aBodyMovingForwardBlowsThePieceBackwards() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        // Model space: -Z is the direction the model faces, so a forward run is -Z.
        Vector3f body = new Vector3f(0.0F, 0.0F, -5.0F);

        for (int i = 0; i < 60; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
                    1.2F, body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        }

        assertTrue(state.direction.z > 0.05F,
                "running forward must sweep the piece backwards (z=" + state.direction.z + ")");
    }

    /**
     * A heavy piece is moved less by the same wind than a light one.
     *
     * <p>The wind here is gentle (1 block/s), well inside the speed the quadratic drag law is
     * stated for. Mass is the one quantity the drag term keeps - the air's force is set by the air
     * and the piece's speed, not by the piece - and {@link #massChangesWhatTheAirDoesAndNotWhatGravityDoes}
     * pins the other half of that statement: the same mass must <i>not</i> change the angle gravity
     * and the spring settle on, because a pendulum's period does not depend on how heavy it is.
     */
    @Test
    void aHeavierPieceIsBlownAboutLess() {
        float light = trailingAngle(0.25F);
        float heavy = trailingAngle(4.0F);

        assertTrue(light > heavy,
                "mass is what the drag term divides by (light " + light + " vs heavy " + heavy + ")");
    }

    private static float trailingAngle(float mass) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        Vector3f body = new Vector3f(0.0F, 0.0F, -1.0F);
        for (int i = 0; i < 60; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.5F, 1.0F, 0.6F, mass,
                    1.2F, body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        }
        return YsmDynamicBoneSolver.angleBetween(rest, state.direction);
    }

    // ------------------------------------------------------------------
    // Bounds and degenerate input
    // ------------------------------------------------------------------

    /** The first frame establishes state from the pose; integrating from a default would snap. */
    @Test
    void theFirstUpdateStartsAtRestWithNoRotation() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, new Vector3f(), new Vector3f(DOWN), 0.5F,
                1.0F, 0.6F, 1.0F, 1.0F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);

        assertEquals(1.0F, out.w(), 1.0E-5F, "the first frame must not rotate the piece");
        assertEquals(DOWN.y, state.direction.y, 1.0E-5F);
    }

    /**
     * A piece may never bend further than its limit, however hard it is thrown.
     *
     * <p>The limit acts on the piece's direction rather than on the reported angle, so a piece that
     * hits its limit stays there and keeps its state instead of reporting a clamped angle while the
     * state runs away behind it. What the limit may not do is erase the piece's velocity, and
     * {@link #theSwingLimitKeepsTheVelocityAlongTheStop} is that half.
     */
    @Test
    void theSwingIsClampedToItsLimit() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float limit = 0.35F;

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.5F, 1.0F, 0.0F, 1.0F,
                limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        for (int i = 1; i <= 200; i++) {
            pivot.set(0.0F, 0.0F, 6.0F * i * 0.016F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.5F, 1.0F, 0.0F, 1.0F,
                    limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
            assertTrue(YsmDynamicBoneSolver.angleBetween(rest, state.direction) <= limit + 1.0E-3F,
                    "frame " + i + " bent past the limit");
        }
    }

    /**
     * A lag spike is clamped, so a two-second frame cannot be a two-second kick. The old
     * simulator had the same guard for the same reason; what is new is that the rest of the
     * state survives it too.
     */
    @Test
    void aLagSpikeIsClampedRatherThanFlingingThePiece() {
        float spiked = angleAfterOneStep(2.0F);
        float clamped = angleAfterOneStep(YsmDynamicBoneSolver.MAX_DT);

        assertEquals(clamped, spiked, 1.0E-4F,
                "a 2 s frame must be clamped to the same step as the maximum");
    }

    private static float angleAfterOneStep(float dt) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        Vector3f body = new Vector3f(0.0F, 0.0F, -6.0F);
        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
                1.2F, body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
                1.2F, body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, dt, out);
        return YsmDynamicBoneSolver.angleBetween(rest, state.direction);
    }

    /**
     * How the pose arrives may not change how the cloth behaves.
     *
     * <p>Epic Fight poses its armature at tick rate while the solver runs at frame rate, so the
     * pivot's position arrives as a staircase - and a staircase differentiated twice is an
     * alternating impulse train that has nothing to do with the body's motion. The solver averages
     * the pose over {@code PIVOT_SMOOTHING_SECONDS} before differentiating it (see that constant),
     * and this test is the invariance that filter exists to protect.
     *
     * <p>So the same gentle motion - the two centimetres an idle animation moves a hip - is fed in
     * twice, once as the renderer delivers it and once sampled exactly, and the two must agree.
     * The motion itself is far too slow to swing anything; anything the staircase adds is the
     * solver reading its own input's quantisation as an impact.
     *
     * <p>Note what this test is <i>not</i>: it is not the defect the solver rewrite was for. The
     * quantisation drives a piece at the tick rate, twenty hertz, and a piece whose own frequency
     * is 2.36 Hz has no response there, so a staircase by itself never pinned anything. What pinned
     * a whole model was the outside-force ceiling turning the gravity torque into a constant, and
     * {@link #everyPieceSettlesOnItsOwnGravityAngleNotOnOneConstant} is that measurement.
     */
    @Test
    void howThePoseArrivesDoesNotChangeHowTheClothBehaves() {
        float staircase = idlePivotSwing(true);
        float exact = idlePivotSwing(false);

        assertTrue(exact < 0.05F,
                "two centimetres of idle motion must not swing a piece of cloth: " + exact + " rad");
        assertTrue(staircase <= exact * 1.5F + 0.01F,
                "a pose delivered in ticks swung the piece " + staircase + " rad against " + exact
                        + " rad for the same motion sampled smoothly; the solver is answering the"
                        + " quantisation, not the movement");
    }

    /** Drive one piece from a pivot oscillating twice a centimetre at walking pace. */
    private static float idlePivotSwing(boolean tickQuantised) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float lever = 0.14F;
        float frame = 0.005F;
        // A tick is 50 ms: ten frames of the solver.
        int framesPerTick = 10;

        for (int frameIndex = 0; frameIndex < 400; frameIndex++) {
            float time = (tickQuantised ? (frameIndex / framesPerTick) * framesPerTick : frameIndex) * frame;
            pivot.set(0.02F * (float) Math.sin(2.0 * Math.PI * 1.0 * time), 0.0F, 0.0F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 2.36F, 0.6F, 1.0F,
                    0.349F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, frame, out);
        }

        return YsmDynamicBoneSolver.angleBetween(rest, state.direction);
    }

    /**
     * The same input through a real body motion still swings the piece, or the fix above would be
     * indistinguishable from turning the physics off.
     */
    @Test
    void aRealBodyAccelerationStillSwingsThePiece() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float lever = 0.14F;
        float limit = 0.349F;

        // A tenth of a second of real yank: thirty blocks per second squared, which is a combat
        // animation changing the shoulder's velocity by three blocks per second.
        for (int frameIndex = 0; frameIndex < 40; frameIndex++) {
            float t = frameIndex * 0.005F;
            pivot.set(0.5F * 30.0F * t * t, 0.0F, 0.0F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 2.36F, 0.6F, 1.0F,
                    limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, 0.005F, out);
        }

        float swing = YsmDynamicBoneSolver.angleBetween(rest, state.direction);
        assertTrue(swing > 0.08F,
                "a real yank must still throw the piece; it only reached " + swing + " rad");
    }
    /**
     * Nothing may make a garment levitate.
     *
     * <p>The fictitious force is real, so the pivot's own vertical acceleration legitimately works
     * against gravity - cloth inside a falling body is weightless, and that is worth keeping. What
     * it may not do is exceed gravity, because then the whole force reverses and the skirt climbs.
     * A pivot accelerating downwards at four hundred blocks per second squared is a landing, a
     * fall, or a numerical spike, and in all three cases the cloth must still hang.
     */
    @Test
    void aFallingPivotNeverLiftsThePiece() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float lever = 0.14F;

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 2.36F, 0.6F, 1.0F,
                0.349F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, 0.005F, out);
        for (int frameIndex = 1; frameIndex < 120; frameIndex++) {
            float t = frameIndex * 0.005F;
            // Accelerating downwards at 400 blocks/s^2: gravity times sixteen.
            pivot.set(0.0F, -0.5F * 400.0F * t * t, 0.0F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 2.36F, 0.6F, 1.0F,
                    0.349F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, 0.005F, out);
            assertTrue(state.direction.y <= 0.0F,
                    "frame " + frameIndex + " left the piece pointing upwards (y="
                            + state.direction.y + "); cloth must not climb");
        }
    }

    /**
     * The integrator stays stable at the worst frequency and the worst frame the game can hand it.
     *
     * <p>The substep count is now derived from the spring rather than fixed, using YSM's own rule
     * from its {@code SecondOrder#update}: the largest stable explicit step for a spring of
     * frequency {@code f} and damping ratio {@code z} is {@code sqrt(4*k2 + k1^2) - k1}, with
     * {@code k1 = 2z/omega_n} and {@code k2 = 1/omega_n^2}. What this test pins is the property
     * that rule exists to protect, and it is written for the extremes on both sides: the stiffest
     * frequency a model may author against the longest frame the solver accepts, and an undamped
     * spring, which is the least forgiving of them. A piece that shivers or explodes here is a
     * solver that will do it in game at a low frame rate, where it reads as cloth going mad.
     */
    @Test
    void aStiffSpringOnALongFrameStaysStable() {
        for (float frequency : new float[]{5.0F, 2.36F, 1.0F}) {
            for (float damping : new float[]{0.0F, 0.3F, 1.0F}) {
                YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
                Quaternionf out = new Quaternionf();
                Vector3f pivot = new Vector3f();
                Vector3f rest = new Vector3f(1.0F, 0.0F, 0.0F);
                float limit = 1.2F;

                for (int frame = 0; frame < 200; frame++) {
                    // Every frame the longest step the solver accepts, and a pivot that keeps
                    // being yanked, so the spring is never allowed to settle.
                    pivot.set(frame % 2 == 0 ? 0.1F : -0.1F, 0.02F * frame, 0.0F);
                    YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.15F, frequency, damping,
                            1.0F, limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null,
                            YsmDynamicBoneSolver.MAX_DT, out);

                    assertTrue(Float.isFinite(state.direction.x) && Float.isFinite(state.direction.y)
                                    && Float.isFinite(state.direction.z)
                                    && Float.isFinite(state.angularVelocity.length()),
                            "frame " + frame + " of the " + frequency + " Hz / zeta " + damping
                                    + " case went non-finite");
                    float swing = YsmDynamicBoneSolver.angleBetween(rest, state.direction);
                    assertTrue(swing <= limit + 1.0E-3F,
                            "frame " + frame + " of the " + frequency + " Hz / zeta " + damping
                                    + " case left the limit at " + swing + " rad");
                }
                assertTrue(state.direction.length() > 0.9F,
                        "the direction must stay a unit vector after 200 worst-case frames");
            }
        }
    }

    /**
     * Turning the body swings the cloth outward, and only turning does.
     *
     * <p>This is the one driver in the solver that comes from the body's rotation rather than its
     * translation, and it is the first one YSM's own model authors reach for: their hair and skirt
     * expressions are built on {@code q.yaw_speed}, and the chains inside their physics animation
     * are driven by how far the bone above them turned. Nothing in this solver knew about it, so a
     * character could spin on the spot and its skirt would hang as if nothing had happened.
     *
     * <p>What is asserted is the physical statement, not a number: a piece hanging at a horizontal
     * offset from the axis of a turning body must leave its rest direction <i>outward</i>, and a
     * body that is not turning must leave it alone.
     */
    @Test
    void aTurningBodySwingsTheClothOutward() {
        Vector3f turningLean = new Vector3f();
        Vector3f stillLean = new Vector3f();
        float turning = swingWhileTurning(turningLean, 5.0F, 0.0F);
        float still = swingWhileTurning(stillLean, 0.0F, 0.0F);

        assertTrue(turning > 0.02F,
                "a body turning at five radians a second must push its cloth outward; it moved the"
                        + " piece " + turning + " rad");
        assertTrue(still < 1.0E-3F,
                "and a body standing still must not: " + still + " rad");
        assertTrue(turningLean.x > 0.0F,
                "the piece hangs half a block out along +X, so a turn must lean it that way and not"
                        + " through the axis it hangs beside; it leaned " + turningLean);
        assertEquals(0.0F, stillLean.length(), 1.0E-4F,
                "and with no turn there is nothing to lean it with");
    }

    /**
     * The sideways part of a turn - the Euler term - swings a piece at a right angle to the radius.
     *
     * <p>A snap turn is mostly this: the centrifugal term goes with the square of the rate, but the
     * Euler term goes with how fast that rate is built, and for a quarter-second turn it is the
     * larger of the two. Asserted as a direction as well, because the sign of a cross product is
     * exactly the kind of thing that is wrong in a way that still looks like cloth moving.
     */
    @Test
    void aSnapTurnSwingsTheClothSideways() {
        Vector3f tangential = new Vector3f(0.0F, 0.0F, 1.0F);
        swingWhileTurning(tangential, 0.0F, 40.0F);

        assertTrue(Math.abs(tangential.z) > 0.01F,
                "a body whipping into a turn must push its cloth sideways; it moved "
                        + tangential.z + " blocks");
    }

    /**
     * Drive one piece for a second at a fixed pivot offset and report which way it ended up.
     *
     * <p>The pivot sits half a block out from the model's vertical axis - a skirt panel at the hip -
     * and the direction it settles in is written back into {@code lean} so the caller can assert on
     * it.
     */
    private static float swingWhileTurning(Vector3f lean, float yawRate, float yawAccel) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f(0.5F, 0.9F, 0.0F);
        Vector3f rest = new Vector3f(DOWN);

        for (int frame = 0; frame < 200; frame++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.3F, 1.0F, 0.6F, 1.0F,
                    1.2F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null,
                    yawRate, yawAccel, 0.016F, out);
        }
        lean.set(state.direction).sub(rest);
        return lean.length();
    }

    /** No lever, no swing: a bone whose geometry sits on its own pivot must not be divided by. */
    @Test
    void aSegmentWithNoLeverIsLeftAlone() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, new Vector3f(), new Vector3f(DOWN), 0.0F,
                1.0F, 0.6F, 1.0F, 1.0F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);

        assertEquals(1.0F, out.w(), 1.0E-5F);
        assertFalse(state.initialized, "a segment that cannot swing must not claim a state");
    }

    /** Null and non-finite input must be survivable: callers reach here from render code. */
    @Test
    void nullAndNonFiniteInputsAreSurvivable() {
        Quaternionf out = new Quaternionf();
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();

        YsmDynamicBoneSolver.INSTANCE.update(null, GRAVITY, AIR_DRAG, new Vector3f(), new Vector3f(DOWN), 0.5F,
                1.0F, 0.6F, 1.0F, 1.0F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        assertEquals(1.0F, out.w(), 1.0E-5F);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, null, new Vector3f(DOWN), 0.5F,
                1.0F, 0.6F, 1.0F, 1.0F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        assertEquals(1.0F, out.w(), 1.0E-5F);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, new Vector3f(Float.NaN, 0.0F, 0.0F),
                new Vector3f(DOWN), 0.5F, 1.0F, 0.6F, 1.0F, 1.0F, STILL,
                YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        assertEquals(1.0F, out.w(), 1.0E-5F, "a NaN pivot must not poison the state");

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, new Vector3f(), new Vector3f(DOWN), 0.5F,
                1.0F, 0.6F, 1.0F, 1.0F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null,
                Float.NaN, out);
        assertTrue(Float.isFinite(state.direction.x) && Float.isFinite(state.direction.y));
    }

    /** Without a time step the piece keeps the swing it had, which is what a paused game shows. */
    @Test
    void aZeroStepKeepsTheSwingAndProducesTheSameRotation() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        Vector3f body = new Vector3f(0.0F, 0.0F, -6.0F);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
                1.2F, body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        for (int i = 0; i < 20; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
                    1.2F, body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        }
        Vector3f before = new Vector3f(state.direction);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
                1.2F, body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.0F, out);

        assertEquals(before.x, state.direction.x, 1.0E-6F);
        assertEquals(before.y, state.direction.y, 1.0E-6F);
        assertEquals(before.z, state.direction.z, 1.0E-6F);
        assertTrue(YsmDynamicBoneSolver.angleBetween(before, rest) > 0.0F,
                "and it must still report the swing it has");
    }

    // ------------------------------------------------------------------
    // Collision
    // ------------------------------------------------------------------

    /**
     * A piece driven into the body is pushed out of it, and the push is reported so that "the
     * physics is on but nothing collides" can be told from "nothing was hit".
     *
     * <p>The volume is offset from the piece's axis, which is the case that actually occurs:
     * a skirt panel against a thigh. The head-on case is the next test.
     */
    @Test
    void aPieceDrivenIntoTheBodyIsPushedOut() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        OneSphere sphere = new OneSphere();
        sphere.centre.set(0.18F, -0.5F, 0.0F);
        sphere.radius = 0.4F;
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.6F, 1.0F, 0.6F, 1.0F,
                1.2F, STILL, sphere, 0.1F, null, 0.016F, out);
        for (int i = 0; i < 90; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.6F, 1.0F, 0.6F, 1.0F,
                    1.2F, STILL, sphere, 0.1F, null, 0.016F, out);
        }

        // The centre of mass sits at pivot + direction * lever and may not be inside. The
        // tolerance is the depth of a resting contact: the collision iteration converges onto
        // the surface from inside and the spring re-penetrates a fraction of a millimetre each
        // frame, so cloth resting on a thigh sits a millimetre or two into it rather than
        // exactly on it - which is what cloth does.
        Vector3f com = new Vector3f(state.direction).mul(0.6F);
        assertTrue(com.distance(sphere.centre) >= sphere.radius + 0.1F - 3.0E-3F,
                "the centre of mass must end up outside the volume (distance "
                        + com.distance(sphere.centre) + ")");
        assertTrue(state.lastContact > 0.0F, "and the contact must be reported");
    }

    /**
     * The degenerate case: the volume is centred exactly on the piece's axis, so the push
     * points straight along the piece and no rotation can satisfy it.
     *
     * <p>Doing nothing here is the worst outcome available - the piece stays buried in the
     * body for as long as the pose holds - so the segment is expected to slide off sideways
     * instead.
     */
    @Test
    void aPieceAimedAtTheCentreOfAVolumeSlidesOffInsteadOfStayingBuried() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        OneSphere sphere = new OneSphere();
        sphere.centre.set(0.0F, -0.5F, 0.0F);
        sphere.radius = 0.4F;
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);

        for (int i = 0; i < 60; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.6F, 1.0F, 0.6F, 1.0F,
                    1.2F, STILL, sphere, 0.1F, null, 0.016F, out);
        }

        assertTrue(YsmDynamicBoneSolver.angleBetween(rest, state.direction) > 1.0E-3F,
                "the piece must be nudged off the axis it was buried along");
        assertTrue(state.lastContact > 0.0F, "and the contact must be reported");
    }

    /** A volume the segment's own pivot starts inside is skipped, or the piece is ejected. */
    @Test
    void aVolumeContainingThePivotIsSkipped() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        OneSphere sphere = new OneSphere();
        sphere.centre.set(0.0F, 0.0F, 0.0F);
        sphere.radius = 0.4F;
        sphere.skipped = true;
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);

        for (int i = 0; i < 40; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.3F, 1.0F, 1.0F, 1.0F,
                    1.2F, STILL, sphere, 0.1F, null, 0.016F, out);
        }

        assertEquals(0.0F, state.lastContact, 1.0E-6F, "a skipped volume must not push anything");
        assertTrue(state.direction.y < -0.9F, "and the piece must keep hanging from its pivot");
    }

    /**
     * The rule that keeps a garment in one piece: a volume that already contains the piece's
     * <i>rest</i> centre of mass is skipped too, not just one containing the pivot.
     *
     * <p>The volume here is a coarse sphere standing in for the hips, and the piece hangs from
     * a pivot above it at rest. Everything about the piece is inside the sphere already, so
     * there is nothing for collision to resolve - but a solver that resolves anyway drives the
     * piece radially out of it, which is how every panel of a skirt ends up flying away from a
     * different direction at once.
     */
    @Test
    void aVolumeContainingTheRestPositionIsSkipped() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        OneSphere sphere = new OneSphere();
        sphere.centre.set(0.0F, -0.35F, 0.0F);
        sphere.radius = 0.5F;
        sphere.skipBecauseRestIsInside = true;
        Vector3f pivot = new Vector3f(0.0F, 0.0F, 0.0F);
        Vector3f rest = new Vector3f(DOWN);

        for (int i = 0; i < 40; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.4F, 1.0F, 1.0F, 1.0F,
                    1.2F, STILL, sphere, 0.1F, null, 0.016F, out);
        }

        assertEquals(0.0F, state.lastContact, 1.0E-6F,
                "a volume the piece rests inside must not eject it");
        assertTrue(YsmDynamicBoneSolver.angleBetween(rest, state.direction) < 0.02F,
                "and the piece must stay where its pose put it");
        assertEquals(0.0F, sphere.seenRestCentre.x, 1.0E-6F);
        assertEquals(-0.4F, sphere.seenRestCentre.y, 1.0E-6F,
                "the solver must offer the rest centre of mass (pivot + rest * lever) to the test");
    }

    /**
     * Collision can never turn into an unbounded transform.
     *
     * <p>This is the measured regression: on a real model a collider push left every simulated
     * bone pinned at a steady 99.4 degrees of swing against a 60 degree ceiling, because the
     * collision response ran after the clamp and wrote a direction limited only by how far away
     * the volume's surface was. The limit has to have the last word.
     */
    @Test
    void collisionCannotPushThePieceBeyondItsLimit() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        OneSphere sphere = new OneSphere();
        // A volume just below the pivot, so the rest position is outside it and the piece is
        // genuinely inside it - the case collision is for.
        sphere.centre.set(0.0F, -0.9F, 0.0F);
        sphere.radius = 0.5F;
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float limit = 0.5F;

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.25F, 1.0F, 1.0F, 1.0F,
                limit, STILL, sphere, 0.2F, null, 0.016F, out);
        for (int i = 0; i < 200; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.25F, 1.0F, 1.0F, 1.0F,
                    limit, STILL, sphere, 0.2F, null, 0.016F, out);
            assertTrue(YsmDynamicBoneSolver.angleBetween(rest, state.direction) <= limit + 1.0E-3F,
                    "frame " + i + " was pushed past the limit by collision");
        }
        assertTrue(state.lastContact > 0.0F, "the volume must actually have been hit");
    }

    /**
     * Collision is a correction, not a force, and it may not throw a piece in one frame.
     *
     * <p>This is the second of the two rules whose absence let a skirt fly. Collision writes the
     * piece's direction directly, so it is exempt from everything the integration guarantees - it
     * is a positional correction rather than a force. The shipped log caught it as
     * {@code FM2 ... hit=0.112}: a push that moved a panel's centre of mass eleven centimetres in
     * a single frame, which for a panel hanging beside a thigh is a push along the surface normal
     * and therefore <i>upward</i>. Bounded per frame, the same overlap resolves over a few frames -
     * under a fifteenth of a second - and reads as cloth sliding off a limb.
     */
    @Test
    void collisionCannotThrowAPieceItsWholeLimitInOneFrame() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float limit = 1.0F;
        // A volume centred well below the pivot, so the piece is deep inside it and the push is
        // large: exactly the geometry of a skirt panel overlapping a thigh.
        OneSphere sphere = new OneSphere();
        sphere.centre.set(0.0F, -0.75F, 0.0F);
        sphere.radius = 0.5F;
        float lever = 0.6F;

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
                limit, STILL, sphere, 0.15F, null, 0.016F, out);
        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
                limit, STILL, sphere, 0.15F, null, 0.016F, out);
        float afterOneFrame = YsmDynamicBoneSolver.angleBetween(rest, state.direction);

        assertTrue(afterOneFrame <= limit * 0.30F,
                "one frame of collision turned the piece " + afterOneFrame
                        + " rad against a limit of " + limit + "; a push must not be a throw");
    }

    /**
     * The step bound is a budget for the frame, not for each pass of the iteration.
     *
     * <p>The correction loop runs {@code COLLISION_ITERATIONS} times, so a bound expressed per pass
     * is multiplied by eight: a piece could be turned by twice its entire limit between two frames
     * while every individual pass looked correctly bounded. The measurement here is deliberately
     * taken on the whole call, because that is the unit the renderer sees.
     */
    @Test
    void theCollisionStepIsAFrameBudgetNotAnIterationBudget() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float limit = 1.2F;
        float lever = 0.6F;

        // Settle first, with no volumes at all, so that everything this call changes afterwards is
        // the collision's doing and not the spring's.
        for (int i = 0; i < 5; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
                    limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.1F, null, 0.016F, out);
        }

        OneSphere sphere = new OneSphere();
        // Deep inside the piece and well off its axis, so the correction is a large one - the
        // geometry of a skirt panel overlapping a thigh.
        sphere.centre.set(0.2F, -0.6F, 0.0F);
        sphere.radius = 0.35F;
        Vector3f before = new Vector3f(state.direction);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
                limit, STILL, sphere, 0.1F, null, 0.016F, out);

        float turned = YsmDynamicBoneSolver.angleBetween(before, state.direction);
        float frameAllowance = 0.25F * limit;
        assertTrue(turned > 0.0F, "the volume must actually have been hit");
        assertTrue(turned <= frameAllowance + 1.0E-3F,
                "one call turned the piece " + turned + " rad; a frame may spend at most "
                        + frameAllowance + " whatever the iteration count is");
        assertEquals(lever * turned, state.lastContact, 1.0E-4F,
                "and the reported contact must be the arc the piece actually travelled, not the sum"
                        + " of the corrections asked for");
    }

    /** And the overlap still resolves: bounding the step must not mean never getting out. */
    @Test
    void aBoundedCollisionStillResolvesTheOverlap() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float limit = 1.0F;
        OneSphere sphere = new OneSphere();
        sphere.centre.set(0.0F, -0.75F, 0.0F);
        sphere.radius = 0.5F;
        float lever = 0.6F;

        for (int i = 0; i < 40; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
                    limit, STILL, sphere, 0.15F, null, 0.016F, out);
        }

        Vector3f com = new Vector3f(state.direction).mul(lever);
        assertTrue(com.distance(sphere.centre) >= sphere.radius + 0.15F - 5.0E-3F,
                "after forty frames the piece must be clear of the volume (distance "
                        + com.distance(sphere.centre) + ")");
    }

    // ------------------------------------------------------------------
    // The shared rotation helper
    // ------------------------------------------------------------------

    @Test
    void theShortestRotationTakesOneDirectionOntoTheOther() {
        Quaternionf out = new Quaternionf();
        Vector3f from = new Vector3f(0.0F, -1.0F, 0.0F);
        Vector3f to = new Vector3f(1.0F, 0.0F, 0.0F);

        YsmDynamicBoneSolver.rotationFromTo(out, from, to);

        Vector3f rotated = new Vector3f(from).rotate(out);
        assertEquals(to.x, rotated.x, 1.0E-5F);
        assertEquals(to.y, rotated.y, 1.0E-5F);
        assertEquals(to.z, rotated.z, 1.0E-5F);
    }

    /** Opposed directions still have to produce a rotation; identity would freeze the piece. */
    @Test
    void anOpposedDirectionStillProducesARotation() {
        Quaternionf out = new Quaternionf();
        Vector3f from = new Vector3f(0.0F, -1.0F, 0.0F);
        Vector3f to = new Vector3f(0.0F, 1.0F, 0.0F);

        YsmDynamicBoneSolver.rotationFromTo(out, from, to);

        Vector3f rotated = new Vector3f(from).rotate(out);
        assertEquals(to.y, rotated.y, 1.0E-4F, "a flipped piece must actually be flipped back");
    }

    /** Degenerate directions fall back to identity rather than producing NaNs. */
    @Test
    void degenerateDirectionsFallBackToIdentity() {
        Quaternionf out = new Quaternionf();
        YsmDynamicBoneSolver.rotationFromTo(out, new Vector3f(), new Vector3f(0.0F, 1.0F, 0.0F));
        assertEquals(1.0F, out.w(), 1.0E-6F);

        YsmDynamicBoneSolver.rotationFromTo(out, new Vector3f(0.0F, 1.0F, 0.0F), new Vector3f());
        assertEquals(1.0F, out.w(), 1.0E-6F);
    }

    // ------------------------------------------------------------------
    // The collision geometry itself
    // ------------------------------------------------------------------

    /** A point outside the volume is left exactly where it was. */
    @Test
    void aPointOutsideTheSphereIsNotMoved() {
        Vector3f point = new Vector3f(2.0F, 0.0F, 0.0F);
        Vector3f velocity = new Vector3f(-1.0F, 0.0F, 0.0F);

        assertFalse(YsmBodyColliders.pushOutOfSphere(point, velocity, 0.1F, 0.0F, 0.0F, 0.0F, 0.4F));
        assertEquals(2.0F, point.x, 1.0E-6F);
        assertEquals(-1.0F, velocity.x, 1.0E-6F);
    }

    /** The push lands on the surface, and the velocity that drove the point in is cancelled. */
    @Test
    void thePushLandsOnTheSurfaceAndCancelsTheApproach() {
        Vector3f point = new Vector3f(0.2F, 0.0F, 0.0F);
        Vector3f velocity = new Vector3f(-3.0F, 0.0F, 0.0F);

        assertTrue(YsmBodyColliders.pushOutOfSphere(point, velocity, 0.1F, 0.0F, 0.0F, 0.0F, 0.4F));

        assertEquals(0.5F, point.x, 1.0E-5F, "centre 0 + (0.4 + 0.1) along the push direction");
        assertEquals(0.0F, velocity.x, 1.0E-5F, "the inward velocity is removed, with no bounce");
    }

    /** A tangential velocity survives, which is what lets cloth slide along a thigh. */
    @Test
    void tangentialVelocitySurvivesTheContact() {
        Vector3f point = new Vector3f(0.2F, 0.0F, 0.0F);
        Vector3f velocity = new Vector3f(-3.0F, 0.0F, 4.0F);

        assertTrue(YsmBodyColliders.pushOutOfSphere(point, velocity, 0.1F, 0.0F, 0.0F, 0.0F, 0.4F));

        assertEquals(0.0F, velocity.x, 1.0E-5F);
        assertEquals(4.0F, velocity.z, 1.0E-5F, "sliding along the surface must not be damped away");
    }

    /** Dead centre has no push direction; the fallback must still leave the point on the surface. */
    @Test
    void aPointAtTheDeadCentreIsPushedToTheSurface() {
        Vector3f point = new Vector3f(0.0F, 0.0F, 0.0F);

        assertTrue(YsmBodyColliders.pushOutOfSphere(point, null, 0.1F, 0.0F, 0.0F, 0.0F, 0.4F));

        assertEquals(0.5F, point.length(), 1.0E-5F);
        assertTrue(Float.isFinite(point.x) && Float.isFinite(point.y) && Float.isFinite(point.z));
    }

    // ------------------------------------------------------------------
    // The angle a piece rests at: gravity against the spring
    // ------------------------------------------------------------------

    /** The spring frequency every piece of the maid model in the log is authored at, Hz. */
    private static final float FREQUENCY = 2.36F;

    /** The damping ratio the same model's pieces carry, {@code 24 / (2*sqrt(220))}. */
    private static final float DAMPING_RATIO = 0.81F;

    /**
     * The gravity every test that is not about gravity hands in: the default the solver falls back
     * to. Named here so the call sites read as "with the configured default", not as a magic 24.
     */
    private static final float GRAVITY = YsmDynamicBoneSolver.GRAVITY;

    /**
     * The air-drag coefficient every test that is not about the air hands in: the default the
     * solver falls back to, so the call sites read as "with the configured defaults".
     */
    private static final float AIR_DRAG = YsmDynamicBoneSolver.AIR_DRAG;

    /**
     * {@code asin(0.3)}, the angle the solver's old outside-force ceiling held every piece at,
     * radians. Kept as a number in the test because the point of the tests below is that no piece
     * settles on it by accident any more.
     */
    private static final float ASIN_OLD_CEILING = 0.30469F;

    /**
     * One hanging piece of the maid model, exactly as the shipped log prints it: its lever, its
     * mass and the direction its pose leaves it hanging in. The log is the field evidence for the
     * defect, so the test uses the log's own pieces rather than convenient invented ones - a
     * piece with a lever of 0.5 and a rest direction straight down would have proved nothing,
     * because a piece like that never saturated the ceiling in the first place.
     */
    private record LoggedPiece(String name, float lever, float mass, float rx, float ry, float rz) {
        Vector3f rest() {
            return new Vector3f(rx, ry, rz).normalize();
        }
    }

    private static final LoggedPiece[] LOGGED_PIECES = {
            new LoggedPiece("RightSideHair", 0.23F, 4.0F, 0.35F, -0.89F, -0.28F),
            new LoggedPiece("Bangs", 0.079F, 4.0F, 0.47F, -0.78F, -0.41F),
            new LoggedPiece("BaseHair", 0.162F, 4.0F, 0.69F, 0.44F, -0.58F),
            new LoggedPiece("Tail2", 0.219F, 2.81F, -0.51F, -0.12F, 0.85F),
            new LoggedPiece("FM2", 0.089F, 0.56F, 0.10F, -0.99F, -0.12F),
    };

    /**
     * <b>The defect, as a number.</b> Every piece must settle on the angle gravity and its own
     * spring balance at, and those angles must differ from piece to piece.
     *
     * <p>The shipped log shows what the old solver did instead: eight of that model's pieces read
     * 17.4 or 17.5 degrees - {@code asin(0.3)}, the outside-force ceiling - with levers from 0.059
     * to 0.257, masses from 0.56 to 3.94 and posed rest directions from 27 to 116 degrees off
     * vertical. One constant where the physics asks for eight different angles, because the ceiling
     * capped the gravity torque below what gravity actually produces and the restoring torque
     * therefore stopped depending on the angle at all. On screen that is a piece nailed to an
     * angle: it neither droops nor swings, however the body moves.
     *
     * <p>The balance the test asserts is solved here, independently, from
     * {@code L omega^2 sin(t) = g sin(phi - t)}, so it cannot be satisfied by the solver agreeing
     * with itself. The pivot is standing still throughout, which is the case the defect was
     * reported in.
     */
    @Test
    void everyPieceSettlesOnItsOwnGravityAngleNotOnOneConstant() {
        float lowest = Float.MAX_VALUE;
        float highest = 0.0F;
        for (LoggedPiece piece : LOGGED_PIECES) {
            float settled = settledAngle(piece, 5.0F);
            float expected = equilibriumRadians(piece.lever(), FREQUENCY,
                    YsmDynamicBoneSolver.angleBetween(piece.rest(), DOWN));
            assertEquals(expected, settled, 2.0E-3F, piece.name() + " settled at "
                    + Math.toDegrees(settled) + " degrees; gravity and its spring balance at "
                    + Math.toDegrees(expected));
            assertTrue(Math.abs(settled - ASIN_OLD_CEILING) > 0.05F,
                    piece.name() + " settled at " + Math.toDegrees(settled) + " degrees, which is the"
                            + " constant the removed outside-force ceiling held every piece at");
            lowest = Math.min(lowest, settled);
            highest = Math.max(highest, settled);
        }
        // The five pieces have levers from 0.079 to 0.23 and rest directions from 9 to 116 degrees
        // off vertical, so their own angles span more than thirty degrees - 4.9, 8.5, 22.8, 25.0
        // and 40.2 degrees. What the old ceiling did was collapse exactly that spread to nothing.
        assertTrue(highest - lowest > 0.24F,
                "the pieces must settle on different angles, not one; the spread was "
                        + Math.toDegrees(highest - lowest) + " degrees");
    }

    /**
     * The air is a parameter too, and it is the knob that decides how far a run throws the cloth.
     *
     * <p>Same shape as the gravity test, with the balance the wind makes:
     * {@code tan(theta) = C v^2 / (m L (omega_n^2 + g/L))}. A piece hanging straight down
     * ({@code L} = 0.139, {@code m} = 0.56, {@code omega_n^2} = 220) inside a body running at
     * 5 blocks/s, three settings:
     *
     * <ul>
     *   <li>{@code airDrag = 0}: no air at all, so there is nothing to trail against and the piece
     *       hangs at its rest direction - measured 0.0000 rad, and every term in the balance is
     *       zero.</li>
     *   <li>{@code airDrag = 0.45} (half the default): {@code tan(t) = 11.25/30.57}, i.e.
     *       <b>0.3526 rad = 20.20 deg</b>.</li>
     *   <li>{@code airDrag = 0.9} (the default): <b>0.6347 rad = 36.37 deg</b>.</li>
     * </ul>
     *
     * <p>So the coefficient is not a scale factor on a rotation: it is the drag force's own
     * constant, and the trail is proportional to it. {@code -1} and NaN cannot be trusted - a
     * negative drag would push the cloth along the wind instead of against it - and fall back to
     * the default.
     */
    @Test
    void airDragIsWhatTheCallerConfigures() {
        float lever = 0.139F;
        float mass = 0.56F;
        float speed = 5.0F;
        float none = trailingAngle(lever, mass, speed, 0.0F);
        float half = trailingAngle(lever, mass, speed, 0.45F);
        float full = trailingAngle(lever, mass, speed, AIR_DRAG);

        assertTrue(none < 1.0E-4F, "with the air switched off the piece must hang at its rest"
                + " direction: " + Math.toDegrees(none) + " degrees");
        assertEquals(windBalance(lever, mass, speed, 0.45F), half, 3.0E-3F,
                "half the air must give half the trail's angle on this balance; it is at "
                        + Math.toDegrees(half) + " degrees");
        assertEquals(windBalance(lever, mass, speed, AIR_DRAG), full, 3.0E-3F,
                "and the default the balance for the default; it is at " + Math.toDegrees(full)
                        + " degrees");
        assertTrue(full > half + 0.15F, "twice the air must trail visibly further: "
                + Math.toDegrees(half) + " against " + Math.toDegrees(full) + " degrees");

        // The two angles the balance gives for this piece, stated as numbers: 20.20 and 36.37
        // degrees. A change to the drag law shows up here as a changed number, not a tolerance.
        assertEquals(0.3526F, half, 5.0E-3F, "airDrag 0.45 trails this piece at 20.2 degrees");
        assertEquals(0.6347F, full, 5.0E-3F, "airDrag 0.9 trails it at 36.4 degrees");

        assertEquals(full, trailingAngle(lever, mass, speed, -1.0F), 1.0E-4F,
                "a negative drag is a broken config and must fall back, not blow the cloth along the wind");
        assertEquals(full, trailingAngle(lever, mass, speed, Float.NaN), 1.0E-4F,
                "and so must a non-finite one");
    }

    /** Settle a piece hanging straight down inside a body running at {@code speed}. */
    private static float trailingAngle(float lever, float mass, float speed, float airDrag) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        Vector3f body = new Vector3f(0.0F, 0.0F, -speed);
        for (int i = 0; i < 1200; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, airDrag, pivot, rest, lever,
                    FREQUENCY, DAMPING_RATIO, mass, 1.2F, body,
                    YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, 0.006F, out);
        }
        return YsmDynamicBoneSolver.angleBetween(rest, state.direction);
    }

    /**
     * The angle the wind balance puts a piece at, solved here:
     * {@code tan(t) = C v^2 / (m L (omega^2 + g/L))}, with no limit in the way.
     */
    private static float windBalance(float lever, float mass, float speed, float airDrag) {
        double omegaSquared = Math.pow(2.0 * Math.PI * FREQUENCY, 2.0);
        double drive = airDrag * speed * speed / (mass * lever);
        double restoring = omegaSquared + GRAVITY / lever;
        return (float) Math.atan(drive / restoring);
    }

    /**
     * Standing still for five seconds leaves each piece on its own angle with no motion left in it.
     *
     * <p>Both halves are needed and they are different statements. The angle is where the forces
     * balance; the angular velocity is that the piece arrived there rather than orbiting it. A
     * solver can satisfy either one alone and still be wrong - a piece pinned by a clamp has a
     * stable angle, and a piece oscillating in a limit cycle has a bounded angular velocity.
     */
    @Test
    void standingStillLeavesEveryPieceOnItsAngleWithNoMotionLeft() {
        for (LoggedPiece piece : LOGGED_PIECES) {
            Run run = new Run(piece, 1.2F);
            advance(run, 4.0F);
            float atFourSeconds = run.swing();
            advance(run, 1.0F);
            float atFiveSeconds = run.swing();

            assertTrue(run.omega() < 1.0E-3F, piece.name() + " still had " + run.omega()
                    + " rad/s of angular velocity after five seconds of standing still");
            assertTrue(Math.abs(atFiveSeconds - atFourSeconds) < 1.0E-4F,
                    piece.name() + " was still drifting in the last second: "
                            + Math.toDegrees(atFourSeconds) + " degrees then "
                            + Math.toDegrees(atFiveSeconds));
        }
    }

    /**
     * A step makes the piece lag behind the body and then settle - the "running skirt, swinging
     * hair" half of the report, as three numbers.
     *
     * <p>The pivot carries the hip ten centimetres in a quarter of a second - a real stride, with a
     * peak acceleration of eight blocks per second squared - and then holds still for two seconds.
     * The piece must be left behind while the hip moves (its centre of mass on the opposite side
     * from the acceleration), the lag must be worth looking at rather than a fraction of a degree,
     * and it must be gone within the two seconds rather than still swinging.
     */
    @Test
    void aStepLeavesThePieceLaggingAndThenSettlesIt() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float lever = 0.139F;
        float limit = 1.2F;
        float frame = 0.006F;

        for (int i = 0; i < 167; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, FREQUENCY,
                    DAMPING_RATIO, 1.69F, limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F,
                    null, frame, out);
        }
        assertEquals(0.0F, YsmDynamicBoneSolver.angleBetween(rest, state.direction), 1.0E-4F,
                "the piece starts the step already settled");

        float distance = 0.1F;
        float duration = 0.25F;
        float peakSpeed = (float) (Math.PI * distance / (2.0 * duration));
        float x = 0.0F;
        float time = 0.0F;
        float peakLag = 0.0F;
        float lagSide = 0.0F;
        while (time < duration) {
            x += peakSpeed * (float) Math.sin(Math.PI * (time + frame) / duration) * frame;
            time += frame;
            pivot.set(x, 0.0F, 0.0F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, FREQUENCY,
                    DAMPING_RATIO, 1.69F, limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F,
                    null, frame, out);
            float lag = YsmDynamicBoneSolver.angleBetween(rest, state.direction);
            if (lag > peakLag) {
                peakLag = lag;
                lagSide = state.direction.x;
            }
        }

        assertTrue(peakLag > 0.0524F, "a stride must visibly throw the piece; it only reached "
                + Math.toDegrees(peakLag) + " degrees");
        assertTrue(lagSide < 0.0F, "the piece must trail behind the acceleration (x=" + lagSide + ")");

        for (int i = 0; i < 333; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, FREQUENCY,
                    DAMPING_RATIO, 1.69F, limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F,
                    null, frame, out);
        }
        assertTrue(YsmDynamicBoneSolver.angleBetween(rest, state.direction) < 0.0035F,
                "two seconds after the step the piece must have settled, not still be swinging: "
                        + Math.toDegrees(YsmDynamicBoneSolver.angleBetween(rest, state.direction))
                        + " degrees");
        assertTrue(state.angularVelocity.length() < 1.0E-3F);
    }

    /**
     * The moment arm, measured where the old solver could not show it at all.
     *
     * <p>A sustained push - twenty blocks per second squared for a fifth of a second, a combat
     * animation's shoulder - used to be enough to saturate the outside-force ceiling, and past that
     * point every lever answered the same: the ceiling decided the angle, not the piece. Measured
     * with that push, levers of 0.05, 0.1 and 0.2 came out at 15.3, 15.1 and 14.5 degrees - a
     * spread of under one degree across a fourfold change in the lever - while 0.4 and 0.8 answered
     * 9.1 and 4.8. That is not a moment arm, it is a bound.
     *
     * <p>So what is asserted is not merely that the series descends - a bound can pass that, and
     * the numbers above show it did - but that every step of the series is a real one: each piece
     * has to answer at least a few per cent less than the one before it, which is what a response
     * set by the lever means.
     */
    @Test
    void aSustainedPushTurnsALongPieceLess() {
        float previous = Float.MAX_VALUE;
        for (float lever : new float[]{0.05F, 0.1F, 0.2F, 0.4F, 0.8F}) {
            float swing = swingUnderSustainedPush(lever);
            assertTrue(swing < previous * 0.92F, "a longer lever must answer measurably less (L=" + lever
                    + " gave " + Math.toDegrees(swing) + " degrees, the shorter one before it "
                    + Math.toDegrees(previous) + ")");
            previous = swing;
        }
    }

    private static float swingUnderSustainedPush(float lever) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float frame = 0.005F;
        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, FREQUENCY, DAMPING_RATIO,
                1.69F, 1.2F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, frame, out);
        float peak = 0.0F;
        for (int i = 1; i <= 40; i++) {
            float t = i * frame;
            pivot.set(0.5F * 20.0F * t * t, 0.0F, 0.0F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, FREQUENCY,
                    DAMPING_RATIO, 1.69F, 1.2F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F,
                    null, frame, out);
            peak = Math.max(peak, YsmDynamicBoneSolver.angleBetween(rest, state.direction));
        }
        return peak;
    }

    /**
     * Mass is what the air divides by, and gravity does not care about it at all.
     *
     * <p>Both halves are the physics rather than a convenience. A stone and a feather fall at the
     * same rate, so two pieces of the same length in the same pose must settle on the same angle
     * whatever they weigh; and the same wind must move the heavy one less, because the force the
     * air produces is set by the air and not by what it is blowing on. A solver that used the mass
     * as a general response scale would fail the first, and one that ignored it in the drag would
     * fail the second.
     */
    @Test
    void massChangesWhatTheAirDoesAndNotWhatGravityDoes() {
        float light = settledAngle(new LoggedPiece("light", 0.117F, 0.25F, 0.72F, -0.67F, -0.16F),
                5.0F);
        float heavy = settledAngle(new LoggedPiece("heavy", 0.117F, 4.0F, 0.72F, -0.67F, -0.16F),
                5.0F);

        assertEquals(light, heavy, 1.0E-3F,
                "a pendulum's period does not depend on its mass, so neither may its angle ("
                        + Math.toDegrees(light) + " against " + Math.toDegrees(heavy) + ")");
        assertTrue(light > 0.05F, "and that angle is a real droop, not zero: " + light);
    }

    /**
     * Gravity is a parameter, and the knob the user reaches for actually reaches the cloth.
     *
     * <p>Three settings, three different pieces of arithmetic, all of them pinned to numbers:
     *
     * <ul>
     *   <li>{@code gravity = 0}: a weightless piece follows its pose. Its rest direction is 47.75
     *       degrees off vertical and it must stay there - measured 0.0000 deg, and the balance
     *       equation has nothing to solve because {@code g sin(phi - t)} is zero.</li>
     *   <li>{@code gravity = 24} (the default): the piece droops to the root of
     *       {@code L omega^2 sin(t) = 24 sin(47.75 deg - t)} with {@code L} = 0.117 and
     *       {@code omega^2} = 220, which is <b>0.4012 rad = 22.99 deg</b>.</li>
     *   <li>{@code gravity = 48}: twice the weight, and the same equation with 48 instead of 24
     *       gives <b>0.5495 rad = 31.48 deg</b> - a larger droop, which is the statement "the knob
     *       moves the cloth" rather than "the knob changes a log line".</li>
     * </ul>
     *
     * <p>And the two settings that cannot be trusted fall back to the default instead of running:
     * a negative gravity would tilt the piece upwards and the garment would climb, and NaN is
     * NaN. Both must give exactly what the default gives.
     */
    @Test
    void gravityIsWhatTheCallerConfigures() {
        LoggedPiece piece = new LoggedPiece("LongRightHair", 0.117F, 1.69F, 0.72F, -0.67F, -0.16F);
        float phi = YsmDynamicBoneSolver.angleBetween(piece.rest(), DOWN);

        float weightless = settledAngle(piece, 0.0F, 5.0F);
        float normal = settledAngle(piece, GRAVITY, 5.0F);
        float heavy = settledAngle(piece, GRAVITY * 2.0F, 5.0F);

        assertTrue(weightless < 1.0E-4F, "with gravity 0 a piece must follow its pose, not droop: "
                + Math.toDegrees(weightless) + " degrees");
        assertEquals(equilibriumRadians(piece.lever(), FREQUENCY, phi, GRAVITY), normal, 2.0E-3F,
                "at the default gravity the piece must sit on the balance; it is at "
                        + Math.toDegrees(normal) + " degrees");
        assertEquals(equilibriumRadians(piece.lever(), FREQUENCY, phi, GRAVITY * 2.0F), heavy, 2.0E-3F,
                "and at twice the gravity, on the balance for twice the gravity; it is at "
                        + Math.toDegrees(heavy) + " degrees");
        assertTrue(heavy > normal + 0.10F,
                "twice the weight must droop visibly further: " + Math.toDegrees(normal)
                        + " then " + Math.toDegrees(heavy) + " degrees");

        // 22.99 and 31.48 degrees are what the bisection above returns for this piece; stating them
        // here means a change to the balance shows up as a changed number, not as a tolerance.
        assertEquals(0.4012F, normal, 3.0E-3F, "24 blocks/s^2 balances this piece at 23.0 degrees");
        assertEquals(0.5495F, heavy, 3.0E-3F, "48 blocks/s^2 balances it at 31.5 degrees");

        assertEquals(normal, settledAngle(piece, -GRAVITY, 5.0F), 1.0E-4F,
                "a negative gravity is a broken config and must fall back, not turn the cloth upside down");
        assertEquals(normal, settledAngle(piece, Float.NaN, 5.0F), 1.0E-4F,
                "and so must a non-finite one");
    }

    /**
     * A caller with nothing to say about the body's motion gets a body at rest, not an exception.
     *
     * <p>{@code bodyVelocity == null} used to reach {@code relative.add(bodyVelocity)} and throw.
     * This is a numeric class called from render code, where a null is an ordinary absent value;
     * the solver reads it as zero and the piece hangs exactly as it would with a zero vector.
     */
    @Test
    void aNullBodyVelocityIsReadAsABodyAtRest() {
        YsmDynamicBoneSolver.SegmentState withNull = new YsmDynamicBoneSolver.SegmentState();
        YsmDynamicBoneSolver.SegmentState withZero = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(1.0F, -0.5F, 0.2F).normalize();

        for (int i = 0; i < 200; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(withNull, GRAVITY, AIR_DRAG, pivot, rest, 0.14F, FREQUENCY,
                    DAMPING_RATIO, 1.69F, 0.349F, null, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F,
                    null, 0.006F, out);
            YsmDynamicBoneSolver.INSTANCE.update(withZero, GRAVITY, AIR_DRAG, pivot, rest, 0.14F, FREQUENCY,
                    DAMPING_RATIO, 1.69F, 0.349F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F,
                    null, 0.006F, out);
        }

        assertEquals(0.0F, YsmDynamicBoneSolver.angleBetween(withZero.direction, withNull.direction),
                1.0E-6F, "a null velocity must behave exactly as a zero one");
        assertEquals(withZero.direction.y, withNull.direction.y, 1.0E-6F);
        assertTrue(Float.isFinite(withNull.direction.x) && Float.isFinite(withNull.angularVelocity.length()));
    }

    /**
     * The shortest lever the model actually has is still resolved by the substep cap, with room to
     * spare - and the cap's arithmetic is stated here rather than left to be re-derived.
     *
     * <p>Including gravity in the fastest mode is what made this worth checking: gravity's own
     * restoring stiffness is {@code g/L}, so a short piece is the stiff one. The shortest lever the
     * shipped log reports is 0.021 (the {@code Tail7} bone of the maid model), for which
     * {@code sqrt(omega_n^2 + g/L) = sqrt(220 + 1143) = 36.9 rad/s}; with the model's damping ratio
     * 0.81 the stable step is
     * {@code sqrt(4/omega^2 + (2 zeta/omega)^2) - 2 zeta/omega = 0.0258 s}, so the longest frame
     * the solver accepts (50 ms) needs <b>2 substeps</b> against a cap of {@code MAX_SUBSTEPS} = 16.
     *
     * <p>The cap only binds below a lever of about <b>0.26 mm</b> (where the stable step reaches
     * 50/16 ms), which is two orders of magnitude under anything a model can express. What binds
     * first is the early return for a lever under {@link YsmDynamicBoneSolver#MAX_DT}-sized
     * epsilon, so the cap is a cost guard on a pathological input rather than a limit on real
     * pieces - and the test below pins the consequence that matters: at the real minimum the piece
     * is resolved, stays finite, and does not ring.
     */
    @Test
    void theShortestRealLeverIsStillResolvedAtTheLongestFrame() {
        float lever = 0.021F;                                  // Tail7, the shortest in the log
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(0.2F, -1.0F, -0.1F).normalize();
        float limit = 0.698F;

        float worstFrameChange = 0.0F;
        float previousAngle = 0.0F;
        for (int frame = 0; frame < 200; frame++) {
            // The longest frame the solver accepts, and a pivot that keeps being yanked, so the
            // spring is never allowed to settle into looking stable.
            pivot.set(frame % 2 == 0 ? 0.02F : -0.02F, 0.0F, 0.0F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, lever, 2.36F,
                    DAMPING_RATIO, 0.56F, limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F,
                    null, YsmDynamicBoneSolver.MAX_DT, out);
            float angle = YsmDynamicBoneSolver.angleBetween(rest, state.direction);
            if (frame > 180) {
                worstFrameChange = Math.max(worstFrameChange, Math.abs(angle - previousAngle));
            }
            previousAngle = angle;
            assertTrue(YsmDynamicBoneSolver.isFinite(state.direction)
                            && Float.isFinite(state.angularVelocity.length()),
                    "frame " + frame + " of the shortest lever went non-finite");
            assertTrue(angle <= limit + 1.0E-3F,
                    "frame " + frame + " left the limit at " + angle);
        }
        assertTrue(state.direction.length() > 0.9F, "the direction must stay a unit vector");
        assertTrue(worstFrameChange < 0.35F,
                "the shortest lever must not ring on the longest frame; its angle moved "
                        + worstFrameChange + " rad between two of the last frames");
    }

    /** Run one logged piece for {@code seconds} against a static pivot and report its swing. */
    private static float settledAngle(LoggedPiece piece, float seconds) {
        return settledAngle(piece, GRAVITY, seconds);
    }

    /** The same, with the gravity the caller hands in. */
    private static float settledAngle(LoggedPiece piece, float gravity, float seconds) {
        Run run = new Run(piece, 1.2F, gravity);
        advance(run, seconds);
        return run.swing();
    }

    private static void advance(Run run, float seconds) {
        int frames = Math.round(seconds / run.frame);
        for (int i = 0; i < frames; i++) {
            run.tick();
        }
    }

    /** One logged piece being simulated: the solver's own state, plus the loop around it. */
    private static final class Run {
        final YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        final Quaternionf out = new Quaternionf();
        final Vector3f pivot = new Vector3f();
        final Vector3f rest;
        final float lever;
        final float mass;
        final float limit;
        final float gravity;
        final float frame = 0.006F;

        Run(LoggedPiece piece, float limit) {
            this(piece, limit, GRAVITY);
        }

        Run(LoggedPiece piece, float limit, float gravity) {
            this.rest = piece.rest();
            this.lever = piece.lever();
            this.mass = piece.mass();
            this.limit = limit;
            this.gravity = gravity;
        }

        void tick() {
            YsmDynamicBoneSolver.INSTANCE.update(state, gravity, AIR_DRAG, pivot, rest, lever, FREQUENCY,
                    DAMPING_RATIO, mass, limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F,
                    null, frame, out);
        }

        float swing() {
            return YsmDynamicBoneSolver.angleBetween(rest, state.direction);
        }

        float omega() {
            return state.angularVelocity.length();
        }
    }

    /**
     * The angle gravity and the spring balance at, solved here rather than read out of the solver:
     * the root of {@code L omega^2 sin(t) = g sin(phi - t)} between the posed direction and
     * vertical. Bisection, because the equation has no useful closed form once the piece can be
     * clamped or can pass vertical, and a solver checked against its own arithmetic proves nothing.
     */
    private static float equilibriumRadians(float lever, float frequencyHz, float phi) {
        return equilibriumRadians(lever, frequencyHz, phi, GRAVITY);
    }

    /** The same, for a call that hands in its own gravity. */
    private static float equilibriumRadians(float lever, float frequencyHz, float phi, float gravity) {
        double stiffness = Math.pow(2.0 * Math.PI * frequencyHz, 2.0);
        double gravityPerLever = gravity / lever;
        double hi = Math.min(phi, Math.PI * 0.5 - 1.0E-9);
        double lo = 0.0;
        double atZero = -gravityPerLever * Math.sin(phi);
        if (stiffness * Math.sin(hi) - gravityPerLever * Math.sin(phi - hi) <= 0.0) {
            // Nothing balances this side of vertical: the piece hangs straight down (or against
            // its own limit, which the caller folds in).
            return (float) hi;
        }
        for (int i = 0; i < 100; i++) {
            double mid = 0.5 * (lo + hi);
            if ((stiffness * Math.sin(mid) - gravityPerLever * Math.sin(phi - mid)) * atZero > 0.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (float) (0.5 * (lo + hi));
    }

    // ------------------------------------------------------------------
    // The swing limit as a constraint
    // ------------------------------------------------------------------

    /**
     * A piece at its limit keeps the velocity that runs <i>along</i> the stop.
     *
     * <p>This is the difference between a stop and a brake. A panel resting against a thigh is
     * still sliding across it, and a panel held at its swing limit by a wind is still being carried
     * sideways by it; the constraint is entitled to remove the component of the angular velocity
     * that would drive the piece further past the limit, and nothing else.
     *
     * <p>The measurement is one very short frame, so that the integrator moves the piece by
     * essentially nothing and the only thing that can touch the angular velocity in between is the
     * constraint itself. The version this replaces multiplied the whole angular velocity by 0.25 on
     * every frame spent at the limit, which is an energy sink with no physical counterpart: it is
     * what made a piece at its stop sit there dead until the pose changed, and what made the
     * release read as a snap.
     */
    @Test
    void theSwingLimitKeepsTheVelocityAlongTheStop() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float limit = 0.35F;
        parkOnTheLimit(state, pivot, rest, limit);

        // A spin about an axis perpendicular to both the lever and the limit's normal: it carries
        // the piece around the cone without changing how far out it is.
        Vector3f normal = new Vector3f(rest).cross(state.direction).normalize();
        Vector3f alongTheStop = new Vector3f(normal).cross(state.direction).normalize();
        state.angularVelocity.set(alongTheStop).mul(1.0F);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.3F, FREQUENCY, 0.0F, 1.69F,
                limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, 1.0E-6F, out);

        assertTrue(YsmDynamicBoneSolver.angleBetween(rest, state.direction) <= limit + 1.0E-4F,
                "the limit must still hold the piece: "
                        + YsmDynamicBoneSolver.angleBetween(rest, state.direction));
        assertTrue(state.angularVelocity.length() > 0.9F,
                "velocity along the stop must survive the constraint instead of being scaled away; "
                        + state.angularVelocity.length() + " rad/s of the 1.0 rad/s is left");
    }

    /**
     * And the component the constraint does remove is the one that would pass the limit - removed,
     * not merely scaled, with nothing added anywhere else.
     *
     * <p>What comes back is the small rebound a body gives a piece that hits it (see
     * {@code LIMIT_RESTITUTION}): the piece arrives with a unit of outward speed and leaves with a
     * few per cent of it, going the other way. That is a restitution and not a bounce - it loses
     * ninety-seven per cent of the energy - and it is what keeps the stop from reading as a wall.
     */
    @Test
    void theSwingLimitRemovesOnlyTheVelocityThatWouldPassIt() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float limit = 0.35F;
        parkOnTheLimit(state, pivot, rest, limit);

        Vector3f normal = new Vector3f(rest).cross(state.direction).normalize();
        state.angularVelocity.set(normal).mul(1.0F);
        float speedBefore = state.angularVelocity.length();

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.3F, FREQUENCY, 0.0F, 1.69F,
                limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, 1.0E-6F, out);

        Vector3f normalAfter = new Vector3f(rest).cross(state.direction).normalize();
        float rebound = state.angularVelocity.dot(normalAfter);
        assertTrue(rebound < 0.0F && rebound > -0.25F,
                "the outward component must come back as a small rebound and not as a full bounce: "
                        + rebound + " of the 1.0 rad/s it arrived with");
        assertTrue(state.angularVelocity.length() <= speedBefore + 1.0E-4F,
                "a constraint removes velocity, it never adds any");
        assertTrue(YsmDynamicBoneSolver.angleBetween(rest, state.direction) <= limit + 1.0E-4F);
    }

    /**
     * A piece parked on its limit leaves it under its own spring as soon as nothing is pushing it
     * there - the stop is not sticky, and the piece does not need a frame of the pose to change
     * before it comes back.
     */
    @Test
    void aPieceParkedOnItsLimitLeavesItUnderItsOwnSpring() {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        Vector3f rest = new Vector3f(DOWN);
        float limit = 0.35F;
        parkOnTheLimit(state, pivot, rest, limit);
        state.direction.set(rest).rotateAxis(limit, 1.0F, 0.0F, 0.0F);

        for (int i = 0; i < 40; i++) {                       // a quarter of a second
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, pivot, rest, 0.3F, FREQUENCY, DAMPING_RATIO,
                    1.69F, limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, 0.006F, out);
        }

        float swing = YsmDynamicBoneSolver.angleBetween(rest, state.direction);
        assertTrue(swing < limit * 0.5F, "a piece left alone must come off its limit, not stay on it;"
                + " it was still at " + Math.toDegrees(swing) + " of " + Math.toDegrees(limit)
                + " degrees");
    }

    /** Put a fresh segment exactly {@code 2%} past its limit, settled and with no history. */
    private static void parkOnTheLimit(YsmDynamicBoneSolver.SegmentState state, Vector3f pivot,
                                       Vector3f rest, float limit) {
        state.direction.set(rest).rotateAxis(limit * 1.02F, 1.0F, 0.0F, 0.0F);
        state.angularVelocity.zero();
        state.initialized = true;
        state.lastPivot.set(pivot);
        state.smoothedPivot.set(pivot);
        state.pivotVelocity.zero();
        state.lastPivotVelocity.zero();
    }
}
