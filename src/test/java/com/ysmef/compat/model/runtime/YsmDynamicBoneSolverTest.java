package com.ysmef.compat.model.runtime;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

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

    /**
     * No gravity following: the spring's target is the posed rest direction, which is what every
     * test written before the weight existed asserts. Named rather than written as {@code 0.0F} at
     * fifty call sites so that "this test is about the pre-existing behaviour" is readable.
     */
    private static final float NO_FOLLOW = 0.0F;

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
            return YsmBodyColliders.pushOutOfCapsule(point, velocity, pointRadius,
                    centre.x, centre.y, centre.z, centre.x, centre.y, centre.z, radius);
        }

        @Override
        public boolean skipFor(Vector3f pivot, Vector3f restCentre, float swingReach, int index) {
            if (restCentre != null) {
                seenRestCentre.set(restCentre);
            }
            return skipped || skipBecauseRestIsInside;
        }
    }

    @Test
    void normalFramesDoNotRetainCollisionProbeRecords() {
        YsmMeshSecondaryMotion.clearProbePoints();
        try {
            YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
            OneSphere sphere = new OneSphere();
            Vector3f pivot = new Vector3f();
            Vector3f rest = new Vector3f(DOWN);
            Quaternionf rotation = new Quaternionf();
            for (int frame = 0; frame < 100; frame++) {
                YsmDynamicBoneSolver.advanceProbeFrame();
                YsmDynamicBoneSolver.INSTANCE.setProbeSegment(0);
                YsmDynamicBoneSolver.INSTANCE.update(state, 24.0F, 0.1F, NO_FOLLOW, DOWN,
                        pivot, rest, 0.6F, 1.0F, 0.6F, 1.0F,
                        1.2F, STILL, sphere, 0.1F, null, 0.016F, rotation);
            }
            assertEquals(0, YsmDynamicBoneSolver.probeFrame());
            assertTrue(YsmDynamicBoneSolver.PROBE_QUESTIONS.isEmpty());
            assertTrue(YsmMeshSecondaryMotion.PROBE_TESTED_POINTS.isEmpty());

            YsmDynamicBoneSolver.enableProbeForTests();
            YsmDynamicBoneSolver.advanceProbeFrame();
            YsmDynamicBoneSolver.INSTANCE.setProbeSegment(0);
            YsmDynamicBoneSolver.INSTANCE.update(state, 24.0F, 0.1F, NO_FOLLOW, DOWN,
                    pivot, rest, 0.6F, 1.0F, 0.6F, 1.0F,
                    1.2F, STILL, sphere, 0.1F, null, 0.016F, rotation);
            assertEquals(1, YsmDynamicBoneSolver.probeFrame());
            assertFalse(YsmDynamicBoneSolver.PROBE_QUESTIONS.isEmpty());
        } finally {
            YsmMeshSecondaryMotion.clearProbePoints();
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
        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
                1.2F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        for (int i = 1; i <= 12; i++) {
            // 0.5 blocks/s^2, which on this test's 1 Hz spring stays under the ceiling for both
            // levers; see the test comment.
            pivot.set(0.5F * 0.5F * (i * 0.016F) * (i * 0.016F), 0.0F, 0.0F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.4F, 1.0F, 0.6F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.3F, 2.0F, 1.0F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.5F, 1.0F, 0.6F, mass,
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

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, new Vector3f(), new Vector3f(DOWN), 0.5F,
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

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.5F, 1.0F, 0.0F, 1.0F,
                limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        for (int i = 1; i <= 200; i++) {
            pivot.set(0.0F, 0.0F, 6.0F * i * 0.016F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.5F, 1.0F, 0.0F, 1.0F,
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
        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
                1.2F, body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 2.36F, 0.6F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 2.36F, 0.6F, 1.0F,
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

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 2.36F, 0.6F, 1.0F,
                0.349F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, 0.005F, out);
        for (int frameIndex = 1; frameIndex < 120; frameIndex++) {
            float t = frameIndex * 0.005F;
            // Accelerating downwards at 400 blocks/s^2: gravity times sixteen.
            pivot.set(0.0F, -0.5F * 400.0F * t * t, 0.0F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 2.36F, 0.6F, 1.0F,
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
                    YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.15F, frequency, damping,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.3F, 1.0F, 0.6F, 1.0F,
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

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, new Vector3f(), new Vector3f(DOWN), 0.0F,
                1.0F, 0.6F, 1.0F, 1.0F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);

        assertEquals(1.0F, out.w(), 1.0E-5F);
        assertFalse(state.initialized, "a segment that cannot swing must not claim a state");
    }

    /** Null and non-finite input must be survivable: callers reach here from render code. */
    @Test
    void nullAndNonFiniteInputsAreSurvivable() {
        Quaternionf out = new Quaternionf();
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();

        YsmDynamicBoneSolver.INSTANCE.update(null, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, new Vector3f(), new Vector3f(DOWN), 0.5F,
                1.0F, 0.6F, 1.0F, 1.0F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        assertEquals(1.0F, out.w(), 1.0E-5F);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, null, new Vector3f(DOWN), 0.5F,
                1.0F, 0.6F, 1.0F, 1.0F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        assertEquals(1.0F, out.w(), 1.0E-5F);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, new Vector3f(Float.NaN, 0.0F, 0.0F),
                new Vector3f(DOWN), 0.5F, 1.0F, 0.6F, 1.0F, 1.0F, STILL,
                YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        assertEquals(1.0F, out.w(), 1.0E-5F, "a NaN pivot must not poison the state");

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, new Vector3f(), new Vector3f(DOWN), 0.5F,
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

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
                1.2F, body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        for (int i = 0; i < 20; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
                    1.2F, body, YsmDynamicBoneSolver.NO_COLLIDERS, 0.05F, null, 0.016F, out);
        }
        Vector3f before = new Vector3f(state.direction);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.5F, 1.0F, 0.6F, 1.0F,
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

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.6F, 1.0F, 0.6F, 1.0F,
                1.2F, STILL, sphere, 0.1F, null, 0.016F, out);
        for (int i = 0; i < 90; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.6F, 1.0F, 0.6F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.6F, 1.0F, 0.6F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.3F, 1.0F, 1.0F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.4F, 1.0F, 1.0F, 1.0F,
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

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.25F, 1.0F, 1.0F, 1.0F,
                limit, STILL, sphere, 0.2F, null, 0.016F, out);
        for (int i = 0; i < 200; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.25F, 1.0F, 1.0F, 1.0F,
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

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
                limit, STILL, sphere, 0.15F, null, 0.016F, out);
        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
                    limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.1F, null, 0.016F, out);
        }

        OneSphere sphere = new OneSphere();
        // Deep inside the piece and well off its axis, so the correction is a large one - the
        // geometry of a skirt panel overlapping a thigh.
        sphere.centre.set(0.2F, -0.6F, 0.0F);
        sphere.radius = 0.35F;
        Vector3f before = new Vector3f(state.direction);

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 1.0F, 0.6F, 1.0F,
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

        assertFalse(YsmBodyColliders.pushOutOfCapsule(point, velocity, 0.1F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.4F));
        assertEquals(2.0F, point.x, 1.0E-6F);
        assertEquals(-1.0F, velocity.x, 1.0E-6F);
    }

    /** The push lands on the surface, and the velocity that drove the point in is cancelled. */
    @Test
    void thePushLandsOnTheSurfaceAndCancelsTheApproach() {
        Vector3f point = new Vector3f(0.2F, 0.0F, 0.0F);
        Vector3f velocity = new Vector3f(-3.0F, 0.0F, 0.0F);

        assertTrue(YsmBodyColliders.pushOutOfCapsule(point, velocity, 0.1F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.4F));

        assertEquals(0.5F, point.x, 1.0E-5F, "centre 0 + (0.4 + 0.1) along the push direction");
        assertEquals(0.0F, velocity.x, 1.0E-5F, "the inward velocity is removed, with no bounce");
    }

    /** A tangential velocity survives, which is what lets cloth slide along a thigh. */
    @Test
    void tangentialVelocitySurvivesTheContact() {
        Vector3f point = new Vector3f(0.2F, 0.0F, 0.0F);
        Vector3f velocity = new Vector3f(-3.0F, 0.0F, 4.0F);

        assertTrue(YsmBodyColliders.pushOutOfCapsule(point, velocity, 0.1F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.4F));

        assertEquals(0.0F, velocity.x, 1.0E-5F);
        assertEquals(4.0F, velocity.z, 1.0E-5F, "sliding along the surface must not be damped away");
    }

    /** Dead centre has no push direction; the fallback must still leave the point on the surface. */
    @Test
    void aPointAtTheDeadCentreIsPushedToTheSurface() {
        Vector3f point = new Vector3f(0.0F, 0.0F, 0.0F);

        assertTrue(YsmBodyColliders.pushOutOfCapsule(point, null, 0.1F,
                    0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.4F));

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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, airDrag, NO_FOLLOW, DOWN, pivot, rest, lever,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, FREQUENCY,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, FREQUENCY,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, FREQUENCY,
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
        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, FREQUENCY, DAMPING_RATIO,
                1.69F, 1.2F, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, frame, out);
        float peak = 0.0F;
        for (int i = 1; i <= 40; i++) {
            float t = i * frame;
            pivot.set(0.5F * 20.0F * t * t, 0.0F, 0.0F);
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, FREQUENCY,
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
            YsmDynamicBoneSolver.INSTANCE.update(withNull, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.14F, FREQUENCY,
                    DAMPING_RATIO, 1.69F, 0.349F, null, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F,
                    null, 0.006F, out);
            YsmDynamicBoneSolver.INSTANCE.update(withZero, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.14F, FREQUENCY,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, 2.36F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, gravity, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, lever, FREQUENCY,
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

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.3F, FREQUENCY, 0.0F, 1.69F,
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

        YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.3F, FREQUENCY, 0.0F, 1.69F,
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
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, AIR_DRAG, NO_FOLLOW, DOWN, pivot, rest, 0.3F, FREQUENCY, DAMPING_RATIO,
                    1.69F, limit, STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, 0.006F, out);
        }

        float swing = YsmDynamicBoneSolver.angleBetween(rest, state.direction);
        assertTrue(swing < limit * 0.5F, "a piece left alone must come off its limit, not stay on it;"
                + " it was still at " + Math.toDegrees(swing) + " of " + Math.toDegrees(limit)
                + " degrees");
    }

    // ------------------------------------------------------------------
    // Gravity following: the spring's target as a blend of the pose and
    // the world's vertical, and what the weight buys
    // ------------------------------------------------------------------

    /** The maid model's root limit, {@code secondaryMotionMaxAngleRootDegrees} = 20 degrees. */
    private static final float ROOT_LIMIT = (float) Math.toRadians(20.0);

    /** The frequencies the shipped log measures on the maid model, and the config's damping. */
    private static final float AUTHORED_FREQUENCY = 2.36F;
    private static final float AUTHORED_DAMPING = 0.81F;

    /**
     * The weights the classification hands out. Duplicated here as literals rather than read from
     * {@code YsmPhysicsParts}, so that changing one is a decision someone has to make twice.
     */
    private static final float CLOTH_FOLLOW = 0.92F;
    private static final float HAIR_FOLLOW = 0.60F;
    private static final float TAIL_FOLLOW = 0.80F;

    /** The levers the shipped log measures on the maid model, blocks. */
    private static final float[] MAID_LEVERS = {0.09F, 0.18F, 0.26F};

    /**
     * The regression that protects every other piece of behaviour in this class: a weight of zero
     * must reproduce the pre-existing solver exactly.
     *
     * <p>"Exactly" is meant literally and the implementation is what makes it true: at zero the
     * solver copies the rest direction into the target rather than blending toward it, so the
     * spring's cross product is computed from the same bits it always was. This test pins the
     * property as an identity over a sweep of levers, limits and poses - a lever arm, a swing under
     * gravity, a piece held on its stop - rather than as a single spot check, because the failure
     * mode is a solver that is right at rest and one part in ten thousand off under load.
     *
     * <p>The comparison is against a second {@link YsmDynamicBoneSolver.SegmentState} driven with
     * the target explicitly set to the rest direction - which is what the parameter's own
     * documentation promises the zero case means - so the two are the same statement of intent
     * written two ways.
     */
    @Test
    void aZeroWeightReproducesTheOldSolverExactly() {
        for (float lever : MAID_LEVERS) {
            for (float leanDegrees : new float[]{0.0F, 30.0F, 45.0F, 60.0F}) {
                Vector3f rest = leaningDir(leanDegrees);
                // Two pieces in identical circumstances, one through each route to "target = rest".
                YsmDynamicBoneSolver.SegmentState byWeight =
                        settled(rest, lever, ROOT_LIMIT, 0.0F, DOWN, 0.0F);
                YsmDynamicBoneSolver.SegmentState byHand =
                        settled(rest, lever, ROOT_LIMIT, 0.0F, rest, 0.0F);

                assertEquals(byHand.direction.x, byWeight.direction.x, 0.0F,
                        "x must be identical, not close: lever " + lever + " lean " + leanDegrees);
                assertEquals(byHand.direction.y, byWeight.direction.y, 0.0F,
                        "y must be identical, not close: lever " + lever + " lean " + leanDegrees);
                assertEquals(byHand.direction.z, byWeight.direction.z, 0.0F,
                        "z must be identical, not close: lever " + lever + " lean " + leanDegrees);
                assertEquals(byHand.angularVelocity.x, byWeight.angularVelocity.x, 0.0F,
                        "the velocity must be identical too, not just the angle");
                assertEquals(byHand.angularVelocity.y, byWeight.angularVelocity.y, 0.0F);
                assertEquals(byHand.angularVelocity.z, byWeight.angularVelocity.z, 0.0F);
            }
        }
    }

    /**
     * A weight of zero is not merely the same as the old behaviour, it is the old behaviour's
     * <i>signature</i>: a panel on a body leaning sixty degrees barely moves.
     *
     * <p>This is the test the whole task exists for, stated as the failure. At zero the spring's
     * target is the pose, so the panel's balance is only as far from the pose as gravity can push
     * it - and the authored root limit of twenty degrees is where it ends up, leaving the panel
     * forty degrees off vertical. The assertion is deliberately on the WRONG side of ten degrees,
     * so that this test fails the day someone makes the weight zero again.
     */
    @Test
    void aClothPanelWithNoWeightStaysOnTheBodyAtSixtyDegrees() {
        float offVertical = settledOffVertical(60.0F, 0.26F, ROOT_LIMIT, NO_FOLLOW, DOWN);

        assertTrue(offVertical > 30.0F,
                "without a weight the panel is meant to stay near the body's own axis - that is the "
                        + "defect this feature removes, and this test asserts it is still measurable. "
                        + "Got " + offVertical + " degrees off vertical, which means the weight is "
                        + "no longer what moves it.");
    }

    /**
     * The acceptance criterion: at a sprint's lean, a cloth panel ends up within ten degrees of the
     * world's vertical - at every lever the maid model has.
     *
     * <p>The bound is ten degrees because that is the number the report asks for, and it is met at
     * sixty degrees of lean by a weight of 0.92. What the margin is made of, exactly: the target a
     * weight-b blend puts at {@code phi} from the pose is at {@code (1-b) * lean} from vertical, so
     * 0.92 of the weight leaves eight per cent of sixty degrees - 4.8 - and the rest of the budget
     * is the gravity share the spring cannot hold, which is larger for a shorter lever. Measured
     * across 0.09 to 0.26 blocks, the worst is 4.6 degrees.
     */
    @Test
    void clothHangsNearTheWorldVerticalAtASprintLean() {
        for (float leanDegrees : new float[]{45.0F, 60.0F}) {
            for (float lever : MAID_LEVERS) {
                float offVertical = settledOffVertical(leanDegrees, lever, ROOT_LIMIT,
                        CLOTH_FOLLOW, DOWN);
                assertTrue(offVertical <= 10.0F,
                        "a cloth panel must hang within 10 degrees of vertical at a "
                                + leanDegrees + " degree lean; lever " + lever + " left it at "
                                + offVertical + " degrees");
            }
        }
    }

    /**
     * The "false success" guard, and the reason it is worth a test of its own: passing the wrong
     * direction does not throw, does not log, and <i>looks like the feature working</i>.
     *
     * <p>If the model's own tilted axis were handed in as the world's downward direction - the
     * mistake this test exists for, and the one an earlier revision of this task proposed - then
     * the spring's target would be the body's axis again, the panel would sit at the same angle as
     * before, and the log would report a healthy weight. The two runs below differ only in one
     * argument, so the test can say which of the two the solver is actually doing: the right
     * direction leaves the panel near vertical, the wrong one leaves it on the body.
     */
    @Test
    void theWrongDownTargetKeepsThePanelOnTheBody() {
        float leanDegrees = 60.0F;
        float lever = 0.26F;
        Vector3f rest = leaningDir(leanDegrees);

        float withTheWorld = settledOffVertical(leanDegrees, lever, ROOT_LIMIT, CLOTH_FOLLOW, DOWN);
        // The model's own "vertical" for this pose: the pose's rest direction, which is what the
        // body's axis is. Renormalised, because that is what the solver does with it.
        float withTheBody = settledOffVertical(leanDegrees, lever, ROOT_LIMIT, CLOTH_FOLLOW, rest);

        assertTrue(withTheWorld <= 10.0F,
                "the world's downward direction must bring the panel near vertical, got "
                        + withTheWorld + " degrees");
        // Which number the wrong direction produces is worth stating rather than bounding loosely,
        // because it is not the lean itself. Handing in the body's axis rotates the whole problem
        // with the body: the spring's target becomes the pose, so the piece settles at exactly the
        // angle it settles at with no weight at all - 16.7 degrees from the pose, measured - and
        // the angle from the world's vertical is therefore the lean MINUS that, whatever the weight
        // is. The weight sweep in tmp_verify/T9_probe.txt shows it flat at 43.28 degrees from 0.0 to
        // 1.0, which is what makes this a signature of the mistake rather than a loose bound.
        assertTrue(withTheBody > 35.0F,
                "the body's own axis must leave the panel on the body. Got " + withTheBody
                        + " degrees off vertical at a " + leanDegrees + " degree lean; the wrong "
                        + "direction is meant to leave it near " + (leanDegrees - 16.7F) + " and the "
                        + "right one near 3, so a number below 35 means the two directions are not "
                        + "being told apart");
    }

    /**
     * The lead's fourth question, answered as a test: at a cloth weight and a sixty degree lean the
     * panel must NOT be resting on its swing limit.
     *
     * <p>If the constraint were still a cone about the pose, the piece would be pinned twenty
     * degrees from the pose and the whole mechanism would be invisible - which is exactly what the
     * cone's axis was moved to the target to avoid. So the swing the solver reports is compared
     * against <i>both</i> numbers it could be stuck on: the authored root limit, and the limit's
     * allowance plus the pose-to-target gap. Being past the first and well inside the second is what
     * "on its balance rather than on its stop" means.
     *
     * <p>Measured: swing 57.09 degrees from the pose, 1.22 from the spring's target, for a limit of
     * 20 - so the stop is 19 degrees away and the piece is nowhere near it. See
     * {@code tmp_verify/T9_probe.txt}.
     */
    @Test
    void theSwingLimitDoesNotPinThePanelAtASprintLean() {
        float leanDegrees = 60.0F;
        float lever = 0.26F;
        Vector3f rest = leaningDir(leanDegrees);
        YsmDynamicBoneSolver.SegmentState state =
                settled(rest, lever, ROOT_LIMIT, CLOTH_FOLLOW, DOWN, 4.0F);

        float swing = YsmDynamicBoneSolver.angleBetween(rest, state.direction);
        float fromTarget = YsmDynamicBoneSolver.angleBetween(
                blendedTarget(rest, DOWN, CLOTH_FOLLOW), state.direction);

        assertTrue(swing > ROOT_LIMIT * 1.5F,
                "the panel must be swinging well past its own root limit for the limit to be "
                        + "irrelevant; it swung " + Math.toDegrees(swing) + " of "
                        + Math.toDegrees(ROOT_LIMIT) + " degrees, so the panel is pinned and the "
                        + "weight is being absorbed by the constraint");
        assertTrue(fromTarget < ROOT_LIMIT,
                "and it must be strictly inside the cone about its target, not on it: "
                        + Math.toDegrees(fromTarget) + " degrees from the target against a limit of "
                        + Math.toDegrees(ROOT_LIMIT));
        assertEquals(swing, state.lastAngle, 1.0E-6F,
                "the reported swing is the one the constraint was measured against; a report that "
                        + "disagreed with the state would make the log useless for this question");
    }

    /** The solver's own blend, recomputed here so a test can say where the spring is pulling. */
    private static Vector3f blendedTarget(Vector3f rest, Vector3f downTarget, float weight) {
        return new Vector3f(rest).mul(1.0F - weight).fma(weight, downTarget).normalize();
    }

    /**
     * The weight has to change the physics, not just the field.
     *
     * <p>A parameter that is stored and never used, or one whose effect is cancelled by the swing
     * limit, passes every test that only reads the configuration back. This one measures the two
     * ends of the range through the solver's real path: turning the weight up must move a panel on
     * a leaning body by tens of degrees, and the movement must be large enough that no limit could
     * be responsible for it.
     */
    @Test
    void theWeightChangesWhereThePieceComesToRest() {
        float leanDegrees = 60.0F;
        float lever = 0.26F;
        float without = settledOffVertical(leanDegrees, lever, ROOT_LIMIT, NO_FOLLOW, DOWN);
        float with = settledOffVertical(leanDegrees, lever, ROOT_LIMIT, CLOTH_FOLLOW, DOWN);

        // The "with" number is already asserted against ten degrees by the acceptance test; what is
        // asserted here is that the distance between the two ends of the range is a distance no
        // constraint could have produced. The panel has to leave the pose by roughly the lean angle
        // to reach vertical, and the authored root limit is twenty degrees - so a movement of tens
        // of degrees is evidence that the weight reached the dynamics rather than being absorbed by
        // the stop.
        assertTrue(without - with > 25.0F,
                "a cloth weight must move the panel tens of degrees toward vertical; it moved "
                        + (without - with) + " (" + without + " -> " + with + ")");
    }

    /**
     * The ordering the classification depends on, at the lean angles that matter: cloth hangs
     * closest to vertical, a tail keeps a little more of the lean, and hair keeps the most of the
     * three - because a lock of hair grows out of a skull and pointing every strand straight down
     * is what wet hair looks like, not hair.
     *
     * <p>The numbers are asserted as ranges rather than as one value each, because a category's
     * weight is a judgement and the test should fail when the judgement is broken, not when
     * somebody moves a weight by a hundredth.
     */
    @Test
    void hairKeepsMoreOfTheLeanThanClothDoes() {
        float leanDegrees = 60.0F;
        float lever = 0.26F;
        float cloth = settledOffVertical(leanDegrees, lever, ROOT_LIMIT, CLOTH_FOLLOW, DOWN);
        float tail = settledOffVertical(leanDegrees, lever, ROOT_LIMIT, TAIL_FOLLOW, DOWN);
        float hair = settledOffVertical(leanDegrees, lever, ROOT_LIMIT, HAIR_FOLLOW, DOWN);

        assertTrue(cloth < tail, "cloth must hang closer to vertical than a tail: " + cloth
                + " vs " + tail);
        assertTrue(tail < hair, "a tail must hang closer to vertical than hair: " + tail
                + " vs " + hair);
        assertTrue(hair > 15.0F && hair < 40.0F,
                "hair must keep a visible share of the lean without pointing straight down; got "
                        + hair + " degrees at a " + leanDegrees + " degree lean");
    }

    /**
     * The short-lever regime the class comment names: below {@code L = g / omega^2} - eleven
     * centimetres at the defaults - gravity's torque beats the spring's at every angle, so there is
     * no balance to come to rest at and the piece is driven out to whatever stops it.
     *
     * <p>What must not happen, and what this pins: it must be <i>stopped</i>, by the authored limit
     * and not by anything else, and it must stay a unit vector pointing downward. A solver that let
     * the runaway gravity torque have its way would show a direction that grew past unit length, a
     * piece that flipped over, or a NaN - all three of which have appeared in this class's history.
     */
    @Test
    void aLeverTooShortForGravityToBalanceIsHeldByItsLimitNotLost() {
        for (float lever : new float[]{0.03F, 0.05F, 0.09F, 0.105F}) {
            Vector3f rest = leaningDir(60.0F);
            YsmDynamicBoneSolver.SegmentState state =
                    settled(rest, lever, ROOT_LIMIT, CLOTH_FOLLOW, DOWN, 6.0F);

            assertTrue(YsmDynamicBoneSolver.isFinite(state.direction),
                    "a gravity-dominated piece must not produce a non-finite direction (L=" + lever + ")");
            assertEquals(1.0F, state.direction.length(), 1.0E-3F,
                    "the direction must stay a unit vector (L=" + lever + ")");
            assertTrue(state.direction.y < 0.0F,
                    "and it must still point downward, not flip over (L=" + lever
                            + ", y=" + state.direction.y + ")");
            // What holds it is the cone about the spring's target, and the cone is the authored
            // limit: at a cloth weight the target is close to vertical, so the piece is held just
            // short of it rather than anywhere the gravity torque would take it.
            float swing = YsmDynamicBoneSolver.angleBetween(rest, state.direction);
            assertTrue(swing <= 55.9F * (float) Math.PI / 180.0F + ROOT_LIMIT + 1.0E-3F,
                    "the piece must be inside the cone about its target (L=" + lever + ", swing="
                            + Math.toDegrees(swing) + " degrees)");
        }
    }

    /**
     * A weight changes nothing when there is nothing for it to change: with the pose already
     * hanging straight down, the blend of two identical directions is that direction, and the
     * piece's rest angle is the same at every weight.
     *
     * <p>Worth pinning because it is the case most of a model's pieces are in most of the time, and
     * a weight that introduced a bias there would tilt a standing character's hair for no reason.
     */
    @Test
    void aPoseAlreadyVerticalIsUnaffectedByTheWeight() {
        Vector3f rest = new Vector3f(DOWN);
        for (float weight : new float[]{0.0F, 0.3F, 0.6F, 0.92F, 1.0F}) {
            YsmDynamicBoneSolver.SegmentState state =
                    settled(rest, 0.26F, ROOT_LIMIT, weight, DOWN, 4.0F);
            float offVertical = YsmDynamicBoneSolver.angleBetween(DOWN, state.direction);
            assertTrue(offVertical <= 2.0F,
                    "a piece already pointing down must stay down at weight " + weight
                            + "; it drifted " + offVertical + " degrees");
        }
    }

    // ------------------------------------------------------------------
    // Round 20: a piece the pose has not moved is pulled by its spring
    // alone, so a standing character is drawn exactly as authored
    // ------------------------------------------------------------------

    /**
     * <b>The defect, as a number.</b> The gravity-follow weight says how much of the world's
     * vertical a piece follows <i>rather than the pose</i>, and the world's vertical is only a
     * different answer from the pose's when the pose has moved. Applying it to a piece the pose has
     * left where the mesh was authored therefore rotates that piece off the drawing and holds it
     * there: the shipped {@code 兽耳酱x1} hem is a flared band authored 22.95 degrees off vertical, and
     * the plain blend held it 22.16 degrees away from that - 39 millimetres of movement while the
     * character stands still, which is a rotation, not a translation, and is what the in-game report
     * called a skewed hem.
     *
     * <p>The test drives the solver through its real path with the pose's rotation of the piece's
     * joint as the input, and asserts the property directly: an unchanged pose leaves the piece
     * where it is, at the weights and levers the shipped models use - including the flared case,
     * which is the one the weight used to rotate. The bound is half a degree rather than zero
     * because gravity's own torque is still there and cannot be zero for a piece whose centre of
     * mass is not already below its pivot; what the plain blend added on top of that was
     * {@code (1 - weight) * angle(rest, down)}, which is 1.84 degrees for this piece, and the point
     * is that the piece no longer inherits it.
     */
    @Test
    void aPoseThatDidNotMoveThePieceLeavesItWhereItWasAuthored() {
        // The direction the authored mesh hangs in, and the lever, of the pieces the shipped maid
        // and skirt models carry: a fringe, a lock of hair, the flared hem, and a tail.
        float[] authoredOffDown = {38.43F, 36.30F, 22.95F, 116.0F};
        float[] levers = {0.105F, 0.322F, 0.102F, 0.079F};
        float[] weights = {HAIR_FOLLOW, HAIR_FOLLOW, CLOTH_FOLLOW, TAIL_FOLLOW};
        for (int i = 0; i < authoredOffDown.length; i++) {
            Vector3f rest = leaningDir(authoredOffDown[i]);
            for (float weight : new float[]{weights[i], 0.3F, 1.0F}) {
                YsmDynamicBoneSolver.SegmentState state = settledWithJointRotation(
                        rest, levers[i], ROOT_LIMIT, weight, new Quaternionf(), 6.0F);
                float twist = (float) Math.toDegrees(
                        YsmDynamicBoneSolver.angleBetween(rest, state.direction));
                assertTrue(twist < 0.5F,
                        "a piece the pose left at its authored direction (" + authoredOffDown[i]
                                + " degrees off vertical, lever " + levers[i] + ", weight " + weight
                                + ") must be drawn there; it settled " + twist + " degrees away");
            }
        }
    }

    /**
     * The same property stated as the failure it replaces: the old behaviour held the flared hem
     * more than twenty degrees off its authored direction at a standstill, and this test pins that
     * the number is gone rather than merely bounded - so a future "improvement" that reintroduces a
     * weight at rest fails here even if it is small.
     */
    @Test
    void theFlaredHemIsNoLongerRotatedAtAStandstill() {
        Vector3f rest = leaningDir(22.95F);
        YsmDynamicBoneSolver.SegmentState atRest = settledWithJointRotation(
                rest, 0.102F, ROOT_LIMIT, CLOTH_FOLLOW, new Quaternionf(), 6.0F);
        float stationaryTwist = (float) Math.toDegrees(
                YsmDynamicBoneSolver.angleBetween(rest, atRest.direction));

        // And the same piece on a body that HAS moved, where the weight is meant to act: the piece
        // must leave the authored direction by tens of degrees, which is the behaviour the weight
        // exists for and which the fix must not have removed.
        YsmDynamicBoneSolver.SegmentState leaning = settledWithJointRotation(
                rest, 0.102F, ROOT_LIMIT, CLOTH_FOLLOW, aboutX((float) Math.toRadians(60.0)), 6.0F);
        float leaningTwist = (float) Math.toDegrees(
                YsmDynamicBoneSolver.angleBetween(rest, leaning.direction));

        assertTrue(stationaryTwist < 0.5F, "a standstill must not rotate the hem; it rotated "
                + stationaryTwist + " degrees");
        assertTrue(leaningTwist > 10.0F, "a sixty degree lean must still hand the piece to gravity;"
                + " it only moved " + leaningTwist + " degrees, so the weight has stopped working");
        // The ramp itself: the weight comes up over FULL_FOLLOW_LEAN, so a smaller lean leaves the
        // piece closer to the pose than a larger one does. A monotonic walk, because a scale that
        // was not monotonic would let a piece snap back as the body leaned further.
        float previous = -1.0F;
        for (float leanDegrees : new float[]{0.0F, 15.0F, 30.0F, 45.0F, 60.0F, 80.0F}) {
            YsmDynamicBoneSolver.SegmentState state = settledWithJointRotation(rest, 0.102F, ROOT_LIMIT,
                    CLOTH_FOLLOW, aboutX((float) Math.toRadians(leanDegrees)), 6.0F);
            float twist = (float) Math.toDegrees(
                    YsmDynamicBoneSolver.angleBetween(rest, state.direction));
            assertTrue(twist >= previous - 1.0E-3F, "the weight must rise with the lean, so the"
                    + " piece must move further from the pose at every step: at " + leanDegrees
                    + " degrees it was at " + twist + " after " + previous);
            previous = twist;
        }
    }

    /**
     * The problem the parameter's name makes unavoidable - <i>which</i> vertical - answered as a
     * test, because the wrong choice is not a small error: it is the difference between a hem drawn
     * as authored and a hem held twenty-two degrees off it.
     *
     * <p>The ramp is measured at four points and asserted as a SHRINKING distance from the authored
     * direction, not as a list of angles: the scale saturates at {@code FULL_FOLLOW_LEAN}, so the
     * interesting property is that more movement means the piece sits closer to the world's vertical
     * up to that point - which a test comparing only "none" with "a lot" could not tell from a step.
     * The angles themselves are driven by a gyroscopic balance as well as by the weight (the torque
     * the weight produces is perpendicular to the axis the test rotates about), so they are pinned as
     * an ordering rather than as values: the numbers belong to the model, the ordering to the rule.
     *
     * <p>The saturation is pinned on the scale itself rather than through the solver, because past
     * the saturation the solver's answer keeps changing for a different reason (the piece has been
     * carried further from its authored direction, so the spring has further to pull it back) and a
     * loose bound there would pass for the wrong reason.
     */
    @Test
    void theJointRotationDecidesHowFarTheWeightActsAndNeitherExtremeIsBackwards() {
        Vector3f rest = leaningDir(36.30F);
        float lever = 0.322F;
        float[] leans = {0.0F, 20.0F, 35.0F, 50.0F, 60.0F};
        float previous = Float.MAX_VALUE;
        for (float leanDegrees : leans) {
            YsmDynamicBoneSolver.SegmentState state = settledWithJointRotation(rest, lever, ROOT_LIMIT,
                    HAIR_FOLLOW, movedBy(rest, (float) Math.toRadians(leanDegrees)), 6.0F);
            float offVertical = degreesOffVertical(state);
            assertTrue(offVertical < previous,
                    "each further degree the pose moves the piece must pull it nearer the world's"
                            + " vertical, up to FULL_FOLLOW_LEAN: at " + leanDegrees + " degrees it"
                            + " was at " + offVertical + " after " + previous);
            previous = offVertical;
        }
        assertTrue(previous < 25.0F,
                "at a full follow lean a hair piece keeps a share of the lean, not most of it; got "
                        + previous + " degrees off vertical");

        // The scale's own two ends: the identity is no weight at all, and every rotation at or past
        // FULL_FOLLOW_LEAN scales the configured weight by exactly one - so past that point the
        // mechanism is bit for bit the one the weight's documentation describes, and the weight's own
        // numbers still mean what they say. A rotation's ANGLE is what is measured, so this holds for
        // any axis - including one a piece happens to lie along, which is the blind direction the
        // first version of this rule had.
        assertEquals(0.0F, YsmDynamicBoneSolver.followScaleFor(new Quaternionf()), 1.0E-6F,
                "a pose that did not rotate the piece's joint must scale the weight to nothing");
        for (float leanDegrees : new float[]{60.0F, 90.0F, 180.0F}) {
            assertEquals(1.0F, YsmDynamicBoneSolver.followScaleFor(
                            aboutX((float) Math.toRadians(leanDegrees))), 1.0E-3F,
                    "a pose that turned the joint by " + leanDegrees + " degrees must scale the weight"
                            + " by exactly one");
        }
        assertEquals(0.5F, YsmDynamicBoneSolver.followScaleFor(
                        aboutX((float) Math.toRadians(30.0))), 1.0E-3F,
                "and the ramp is linear in the angle, so half of FULL_FOLLOW_LEAN is half the weight");

        // Gravity's torque is scaled by the same rule, which is what makes the standstill exact
        // rather than merely closer: at the identity the gravity torque is zero, and every rotation
        // at or past the full lean gets all of it. The two are separate methods so that a reader can
        // see the two halves of the mechanism agree, and separate calls so that this test can say the
        // second one still answers.
        assertEquals(0.0F, YsmDynamicBoneSolver.gravityScaleFor(new Quaternionf()), 1.0E-6F,
                "gravity must have no say in a piece the pose has not turned");
        assertEquals(1.0F, YsmDynamicBoneSolver.gravityScaleFor(
                        aboutX((float) Math.toRadians(60.0))), 1.0E-3F,
                "and all of it once the pose has turned the joint by FULL_FOLLOW_LEAN");
    }

    /**
     * The rotation the caller hands in has to be read in the same frame as the rest direction, and
     * the cheapest way to get that wrong is to read the wrong part of a matrix. This drives the
     * public {@code pivotDeltaOf} over the deformation the frame path builds - the pose's joint
     * transform times the inverse of the authored one - and asserts the identity against a
     * rotation, which is the pair of answers the weight's scale is made of.
     */
    @Test
    void theJointRotationIsTheDifferenceBetweenThePoseAndTheAuthoredTransform() {
        Quaternionf out = new Quaternionf();
        // An authored joint transform and the same one rotated by an arbitrary rotation: the
        // deformation is pose x inverse(authored), so its rotation must come back as that rotation.
        float angle = (float) Math.toRadians(37.0);
        Quaternionf applied = new Quaternionf().fromAxisAngleRad(
                new Vector3f(0.3F, 0.8F, -0.5F).normalize(), angle);
        Matrix4f authoredRotation = new Matrix4f().rotate(aboutX(0.4F));
        OpenMatrix4f authored = toOpen(authoredRotation);
        OpenMatrix4f posed = toOpen(new Matrix4f().rotate(applied).mul(authoredRotation));
        OpenMatrix4f deformation = OpenMatrix4f.mul(posed, OpenMatrix4f.invert(authored, null), null);

        YsmMeshSecondaryMotion.pivotDeltaOf(deformation, out);
        assertEquals((float) Math.toDegrees(angle), (float) Math.toDegrees(out.angle()), 0.5F,
                "the extracted rotation must be the rotation the pose applied, not the joint's own"
                        + " authored orientation and not its transpose");

        // And the identity, which is the case the whole fix rests on: a deformation that is the
        // identity matrix is "the pose left this joint exactly where the rig authors it", and the
        // pivot delta must read as no rotation at all - not as the joint's authored orientation
        // (which for this rig would be 0.4 radians) and not as a transpose of it.
        OpenMatrix4f identity = new OpenMatrix4f();
        YsmMeshSecondaryMotion.pivotDeltaOf(identity, out);
        assertEquals(0.0F, out.angle(), 1.0E-3F,
                "an unchanged pose must read as the identity rotation, got " + out.angle()
                        + " radians");
    }

    /**
     * A rotation about the model's own left-right axis, which is the axis a forward lean uses.
     *
     * <p>JOML 1.10.5's four-float overload is {@code (x, y, z, angle)} - the angle last. Called as
     * {@code (angle, x, y, z)} with {@code x = 1.0} it normalises to the axis {@code (1, 0, 0)} and
     * reads {@code angle} from the fourth slot, so it happens to be right for this axis and wrong for
     * every other; the axis overload is used so there is no ordering to get wrong.
     */
    private static Quaternionf aboutX(float radians) {
        return new Quaternionf().fromAxisAngleRad(new Vector3f(1.0F, 0.0F, 0.0F), radians);
    }

    /**
     * A segment settled with the pose's rotation of its joint handed in - the round-20 path. The
     * identity is "the pose left this piece exactly where the mesh was authored", which is the
     * standing-still case the fix is about.
     */
    private static YsmDynamicBoneSolver.SegmentState settledWithJointRotation(
            Vector3f rest, float lever, float maxAngle, float weight, Quaternionf jointRotation,
            float seconds) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        float dt = 1.0F / 60.0F;
        int frames = Math.round(seconds / dt);
        for (int i = 0; i <= frames; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, 0.0F, weight, DOWN,
                    pivot, rest, lever, AUTHORED_FREQUENCY, AUTHORED_DAMPING, 1.0F, maxAngle,
                    STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, 0.0F, 0.0F, dt, out,
                    jointRotation);
        }
        return state;
    }

    /** The angle of a settled direction from the world's vertical, in degrees. */
    private static float degreesOffVertical(YsmDynamicBoneSolver.SegmentState state) {
        return (float) Math.toDegrees(YsmDynamicBoneSolver.angleBetween(DOWN, state.direction));
    }

    /**
     * A rotation that moves {@code direction} by exactly {@code radians}: about the axis
     * perpendicular to it, so the angle between the vector and its image is the angle asked for.
     *
     * <p>Rotating about a fixed world axis does NOT do that - the component of the direction along
     * that axis is left alone, so a "sixty degree rotation" moves a direction 36 degrees off the axis
     * by less than sixty, and one 36 degrees from the axis by about seventy-five. The scale this test
     * exists for is a cosine of the angle actually moved, so the rotation has to be built from the
     * direction rather than from the world.
     */
    private static Quaternionf movedBy(Vector3f direction, float radians) {
        Vector3f axis = new Vector3f(direction).cross(0.0F, 1.0F, 0.0F);
        if (axis.lengthSquared() < 1.0E-8F) {
            axis.set(1.0F, 0.0F, 0.0F);
        }
        return new Quaternionf().fromAxisAngleRad(axis.normalize(), radians);
    }

    /** A JOML matrix into Epic Fight's, for the tests that build one by hand. */
    private static OpenMatrix4f toOpen(Matrix4f m) {
        OpenMatrix4f out = new OpenMatrix4f();
        out.m00 = m.m00(); out.m01 = m.m01(); out.m02 = m.m02(); out.m03 = m.m03();
        out.m10 = m.m10(); out.m11 = m.m11(); out.m12 = m.m12(); out.m13 = m.m13();
        out.m20 = m.m20(); out.m21 = m.m21(); out.m22 = m.m22(); out.m23 = m.m23();
        out.m30 = m.m30(); out.m31 = m.m31(); out.m32 = m.m32(); out.m33 = m.m33();
        return out;
    }

    /**
     * A non-finite weight is a broken caller, not a reason to throw on the render thread, and a
     * weight outside 0..1 would extrapolate the target past the world's vertical - which pulls the
     * cloth upwards. Both are refused, and the fallback is the value that keeps the old behaviour.
     */
    @Test
    void aBrokenWeightFallsBackInsteadOfPullingTheClothUpwards() {
        Vector3f rest = leaningDir(60.0F);
        float reference = settledOffVertical(60.0F, 0.26F, ROOT_LIMIT, NO_FOLLOW, DOWN);
        for (float weight : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1.0F}) {
            YsmDynamicBoneSolver.SegmentState state =
                    settled(rest, 0.26F, ROOT_LIMIT, weight, DOWN, 0.0F);
            assertTrue(YsmDynamicBoneSolver.isFinite(state.direction), "got " + state.direction);
            assertTrue(state.direction.y < 0.0F, "a broken weight must not lift the piece");
        }
        float accepted = settledOffVertical(60.0F, 0.26F, ROOT_LIMIT, 0.0F, DOWN);
        assertEquals(reference, accepted, 1.0E-4F,
                "a zero weight and a refused weight must agree, because both mean 'follow the pose'");
    }

    /**
     * The downward direction is a parameter, so a caller can hand in a slope or a gust - and a null
     * or a collapsed vector is read as the world's vertical rather than as "no direction at all",
     * which would leave the spring pulling nowhere.
     */
    @Test
    void aMissingDownTargetMeansTheWorldVertical() {
        float leanDegrees = 60.0F;
        float withExplicit = settledOffVertical(leanDegrees, 0.26F, ROOT_LIMIT, CLOTH_FOLLOW, DOWN);
        float withNull = settledOffVertical(leanDegrees, 0.26F, ROOT_LIMIT, CLOTH_FOLLOW, null);
        float withZero = settledOffVertical(leanDegrees, 0.26F, ROOT_LIMIT, CLOTH_FOLLOW,
                new Vector3f());

        assertEquals(withExplicit, withNull, 1.0E-4F,
                "a null downward direction must be read as the world's vertical");
        assertEquals(withExplicit, withZero, 1.0E-4F,
                "and so must a zero vector, which is a caller with nothing to say");
    }

    /**
     * A <i>custom</i> downward direction is honoured, not overwritten by the world's: a slope of
     * thirty degrees leaves a cloth panel thirty degrees from the true vertical, at the same
     * weight that would have put it within ten of the world's.
     */
    @Test
    void aCustomDownTargetIsHonouredRatherThanReplaced() {
        float leaned = settledOffVertical(60.0F, 0.26F, ROOT_LIMIT, CLOTH_FOLLOW, DOWN);
        // A "downhill" that is thirty degrees off the world's vertical, in the same plane.
        Vector3f slope = new Vector3f((float) Math.sin(Math.toRadians(30.0)),
                -(float) Math.cos(Math.toRadians(30.0)), 0.0F);
        float onTheSlope = settledOffVertical(60.0F, 0.26F, ROOT_LIMIT, CLOTH_FOLLOW, slope);

        assertTrue(leaned <= 10.0F, "the world's vertical is the baseline: " + leaned);
        assertTrue(onTheSlope > leaned + 10.0F,
                "a slope must actually steer the pieces; the two runs differed by only "
                        + (onTheSlope - leaned) + " degrees");
    }

    // ------------------------------------------------------------------
    // Helpers for the gravity-follow tests
    // ------------------------------------------------------------------

    /** The direction a piece hanging along a body leaning {@code degrees} forward points. */
    private static Vector3f leaningDir(float degrees) {
        double radians = Math.toRadians(degrees);
        return new Vector3f((float) Math.sin(radians), -(float) Math.cos(radians), 0.0F);
    }

    /**
     * Drive one segment from a leaning pose with a still pivot and report where it ended up, in
     * degrees from the world's vertical.
     *
     * <p>The world's vertical is {@link #DOWN} whatever {@code downTarget} is, so that a test can
     * hand in a wrong or a sloped direction and still be measured against the truth.
     */
    private static float settledOffVertical(float leanDegrees, float lever, float maxAngle,
                                            float weight, Vector3f downTarget) {
        YsmDynamicBoneSolver.SegmentState state =
                settled(leaningDir(leanDegrees), lever, maxAngle, weight, downTarget, 4.0F);
        return (float) Math.toDegrees(YsmDynamicBoneSolver.angleBetween(DOWN, state.direction));
    }

    /**
     * A segment settled on its balance: fresh state, first frame to establish it, then
     * {@code seconds} of a still pivot with no air and no colliders.
     *
     * <p>No air deliberately. The drag term is the only one that carries the body's speed, and the
     * question these tests ask is where a piece rests, which is a balance between gravity and the
     * spring alone - the same balance the class comment derives. A wind would only blur it.
     */
    private static YsmDynamicBoneSolver.SegmentState settled(Vector3f rest, float lever,
                                                             float maxAngle, float weight,
                                                             Vector3f downTarget, float seconds) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        Quaternionf out = new Quaternionf();
        Vector3f pivot = new Vector3f();
        // 60 Hz, which is a frame rate the render thread really sees, and a dt well inside the
        // solver's own stability rule for a 2.36 Hz spring on a nine-centimetre lever.
        float dt = 1.0F / 60.0F;
        int frames = Math.round(seconds / dt);
        for (int i = 0; i <= frames; i++) {
            YsmDynamicBoneSolver.INSTANCE.update(state, GRAVITY, 0.0F, weight, downTarget,
                    pivot, rest, lever, AUTHORED_FREQUENCY, AUTHORED_DAMPING, 1.0F, maxAngle,
                    STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.03F, null, dt, out);
        }
        return state;
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
