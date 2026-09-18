package com.ysmef.compat.acceptance;

import com.ysmef.compat.model.runtime.YsmDynamicBoneSolver;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent acceptance for the pendulum integrator (task T2), written against the
 * behaviour the in-game report describes rather than against the implementation.
 *
 * <p>Every test here is a <b>black-box</b> statement about numbers the solver produces
 * from a given input; none of them reads a constant, a field or a helper of the solver
 * other than to drive it. That is deliberate: the four implementation tasks each rewrote
 * parts of the solver's surroundings, and a verifier that asserts on internals ends up
 * checking that the internals were renamed, not that the cloth hangs.
 *
 * <h2>Which of these can actually fail, and on what</h2>
 *
 * <p>Being explicit about this is the point of an adversarial suite, so:
 *
 * <ul>
 *   <li>{@link #aLongLeverAnswersMoreSlowlyThanAShortOne()} is <b>discriminating</b>. Its
 *       two pieces are identical except for the lever, and the initial angular
 *       acceleration is read on the very first step - where drag is exactly zero (the
 *       piece starts at rest with a stationary pivot) and the spring term is identical
 *       for both. The only term that can differ is {@code (d x g) / L}, so the assertion
 *       is a direct measurement of whether the lever still reaches the torque. It goes
 *       red under the design this task removed, because that design replaced both
 *       answers with the same saturated number.</li>
 *   <li>{@link #aSteadySidewaysPullSettlesWellInsideTheLimit()} is <b>discriminating</b>
 *       in the same way and against the shipped log: it asserts that a sustained lateral
 *       acceleration settles the piece at the angle ordinary pendulum balance names, not
 *       at the outside-force ceiling. The shipped log is a whole skirt sitting at 18.8 to
 *       20.0 degrees with the player standing still, which is the ceiling talking.</li>
 *   <li>{@link #aViolentPulseIsAnsweredAndDoesNotDiverge()} is a response and stability check:
 *       the piece must react to a combat-sized pivot jerk, must hold its limit, and must settle
 *       again. It does not discriminate the removed design, because the removed design also
 *       reacted and also settled.</li>
 *   <li>{@link #theVelocityIsNeverClearedInsideTheLimit()} is a bound-the-forces check, not a
 *       comparison: inside the limit no constraint acts, so a frame-to-frame change larger than
 *       the modelled forces allow means the velocity is being cleared or scaled. Two earlier
 *       versions of this test were red on legitimate behaviour - a driving-force reversal and a
 *       constraint projection - and the class comment on the test records both, because that
 *       trap is the trap in this whole area. It does not discriminate the removed design
 *       either: that design's brake fired only <i>at</i> the clamp, where a projection is
 *       legitimate, so no purely behavioural test can separate the two there. See
 *       {@link #theRemovedDesignFailsBothDiscriminatingAssertions()} for what does.</li>
 *   <li>{@link #aStationaryPieceConverges()} is a precondition, not a finding: if it ever
 *       fails, nothing else in this file means anything.</li>
 * </ul>
 */
class SolverDynamicsAcceptanceTest {

    private static final float DT = 1.0F / 60.0F;
    private static final Vector3f STILL = new Vector3f();
    private static final float FRAME = 1.0F / 60.0F;

    private static final float GRAVITY = AcceptanceSupport.constant("GRAVITY", 24.0F);

    /** A hanging piece: pivot at the waist, rest direction straight down. */
    private static final Vector3f REST = new Vector3f(0.0F, -1.0F, 0.0F);

    private static YsmDynamicBoneSolver.SegmentState started(Vector3f pivot, float lever) {
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        AcceptanceSupport.step(state, pivot, REST, lever, 2.36F, 0.5F, 0.1F, 0.349F,
                STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, new Quaternionf());
        return state;
    }

    /**
     * A piece left alone settles: no residual angular velocity, no drift in the angle.
     *
     * <p>This is the precondition for every other measurement in the file. A solver whose
     * resting state shivers has no steady state to compare, and "the piece is nailed to a
     * constant angle" and "the piece is at rest" are indistinguishable in a single frame -
     * so the angle is required to be flat across the last second, not merely small.
     */
    @Test
    @DisplayName("a stationary piece converges: |omega| < 1e-3 rad/s and a flat angle")
    void aStationaryPieceConverges() {
        Vector3f pivot = new Vector3f(0.0F, 0.9F, 0.0F);
        YsmDynamicBoneSolver.SegmentState state = started(pivot, 0.14F);
        // Knocked 17 degrees off plumb, which is inside the 20 degree limit.
        state.direction.set(0.3F, -1.0F, 0.1F).normalize();
        state.angularVelocity.zero();

        float minAngle = Float.MAX_VALUE;
        float maxAngle = -Float.MAX_VALUE;
        for (int frame = 0; frame < 600; frame++) {
            AcceptanceSupport.step(state, pivot, REST, 0.14F, 2.36F, 0.5F, 0.1F, 0.349F,
                    STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, new Quaternionf());
            if (frame >= 540) {
                float angle = AcceptanceSupport.angleBetween(REST, state.direction);
                minAngle = Math.min(minAngle, angle);
                maxAngle = Math.max(maxAngle, angle);
            }
        }

        assertTrue(Float.isFinite(state.angularVelocity.length()),
                "the angular velocity went non-finite while the piece hung still");
        assertTrue(state.angularVelocity.length() < 1.0E-3F,
                "a piece left alone must come to rest; |omega| = " + state.angularVelocity.length()
                        + " rad/s after 10 s");
        assertTrue(AcceptanceSupport.degrees(AcceptanceSupport.angleBetween(REST, state.direction)) < 1.0F,
                "it must hang at its rest direction; it is at "
                        + AcceptanceSupport.degrees(AcceptanceSupport.angleBetween(REST, state.direction))
                        + " degrees");
        assertTrue(AcceptanceSupport.degrees(maxAngle - minAngle) < 0.5F,
                "the resting angle must be flat across the last second; it moved by "
                        + AcceptanceSupport.degrees(maxAngle - minAngle) + " degrees");
    }

    /**
     * The lever still reaches the torque: a long piece answers a pull more slowly than a
     * short one, measured on the first step where nothing else can differ.
     *
     * <h2>The arithmetic this asserts</h2>
     *
     * <p>For a direction {@code d} displaced {@code theta} from rest, with a stationary
     * pivot and no velocity, the step's angular acceleration is exactly
     *
     * <pre>
     *   alpha = (d x g) / L + omega_n^2 (d x rest)
     * </pre>
     *
     * <p>and the drag term is identically zero (it is proportional to the relative
     * velocity, which is zero at rest with a stationary pivot). With {@code theta} = 56
     * degrees, {@code g} = 24, {@code omega_n} = 2*pi*2 Hz = 12.57:
     *
     * <pre>
     *   omega_n^2 * sin(56)          = 130.9 rad/s^2   (identical for both levers)
     *   |d x g| / 0.30               =  66.3           (the long piece)
     *   |d x g| / 0.05               = 398.1           (the short piece)
     * </pre>
     *
     * <p>so a design in which the lever reaches the torque must show the short piece
     * gaining angular velocity at least twice as fast. A design that replaces the
     * outside-force total with a ceiling proportional to {@code omega_n^2} replaces both
     * of those with the same number - measured here at {@code 0.3 * 157.9 = 47.4} - and
     * both pieces then gain exactly the same angular velocity, which is what this test
     * went red on before the fix.
     */
    @Test
    @DisplayName("the lever reaches the torque: the long piece answers more slowly than the short one")
    void aLongLeverAnswersMoreSlowlyThanAShortOne() {
        float theta = AcceptanceSupport.radians(56.0);
        Vector3f displaced = new Vector3f(0.0F, -(float) Math.cos(theta), (float) Math.sin(theta));

        float shortGain = firstStepAngularVelocity(0.05F, displaced);
        float longGain = firstStepAngularVelocity(0.30F, displaced);

        assertTrue(Float.isFinite(shortGain) && Float.isFinite(longGain),
                "a finite input produced a non-finite angular velocity");
        assertTrue(shortGain > 0.0F && longGain > 0.0F,
                "the pull must move both pieces: short=" + shortGain + ", long=" + longGain);
        assertTrue(shortGain > 1.5F * longGain,
                "with a 0.30 m lever the first step must gain less angular velocity than with a"
                        + " 0.05 m one, because alpha = (d x g)/L; measured short=" + shortGain
                        + " rad/s, long=" + longGain
                        + " rad/s (ratio " + (shortGain / longGain) + "). A ratio near 1 means the"
                        + " lever is not reaching the torque and the two pieces are being held by a"
                        + " ceiling instead of by gravity.");
    }

    /** One step from a displaced, motionless start; returns the angular velocity gained. */
    private static float firstStepAngularVelocity(float lever, Vector3f displaced) {
        Vector3f pivot = new Vector3f(0.0F, 0.9F, 0.0F);
        // The limit is deliberately wide here: this test is about the torque, not the clamp.
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        AcceptanceSupport.step(state, pivot, REST, lever, 2.0F, 0.5F, 0.1F, 1.2F,
                STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, new Quaternionf());
        state.direction.set(displaced).normalize();
        state.angularVelocity.zero();
        AcceptanceSupport.step(state, pivot, REST, lever, 2.0F, 0.5F, 0.1F, 1.2F,
                STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, new Quaternionf());
        return state.angularVelocity.length();
    }

    /**
     * A sustained sideways pull settles the piece where ordinary pendulum balance says,
     * not against the limit and not at a fixed fraction of the spring's authority.
     *
     * <h2>The arithmetic this asserts</h2>
     *
     * <p>A pivot accelerating at {@code a} is, in the piece's own frame, a uniform field
     * {@code g_eff = g - a}. The equilibrium is the direction parallel to
     * {@code g_eff / L + omega_n^2 * rest}, whose angle from rest is
     *
     * <pre>
     *   tan(theta) = (a / L) / (g / L + omega_n^2)
     * </pre>
     *
     * <p>For {@code a} = 40, {@code L} = 0.14, {@code g} = 24, {@code omega_n^2} = 220:
     * {@code tan(theta) = 285.7 / 391.4}, so {@code theta} = 36.1 degrees.
     *
     * <p>The design this task removed capped the outside-force total at
     * {@code omega_n^2 * 0.3}, which bounds the equilibrium by
     * {@code asin(0.3)} = <b>17.5 degrees</b> no matter what the physics says. The
     * assertion below sits between the two answers on purpose: anything past 22 degrees
     * cannot have been produced by that ceiling, and 22 is far enough below 36 that a
     * reasonable reformulation of the balance still passes.
     *
     * <p>The mass is set high (50) on purpose. The drag term scales as
     * {@code 1 / (m * L)}, so a realistic mass would let the airflow - not the balance -
     * decide where the piece sits, and this test is about the balance. The stated cost is
     * that this test does not measure the drag at all; nothing here does, and the report
     * says so.
     */
    @Test
    @DisplayName("a sustained sideways pull settles well inside the limit, not at an outside-force ceiling")
    void aSteadySidewaysPullSettlesWellInsideTheLimit() {
        Vector3f pivot = new Vector3f(0.0F, 0.9F, 0.0F);
        float mass = 50.0F;
        float lever = 0.14F;
        float limit = AcceptanceSupport.radians(60.0);

        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        AcceptanceSupport.step(state, pivot, REST, lever, 2.36F, 0.5F, mass, limit,
                STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, new Quaternionf());

        // A constant 40 blocks/s^2 to the -X side, delivered the way a moving body delivers
        // it: as pivot displacement. The solver differentiates the pivot itself, so nothing
        // here injects a force the game could not produce.
        float acceleration = 40.0F;
        float velocity = 0.0F;
        float sum = 0.0F;
        int samples = 0;
        for (int frame = 0; frame < 120; frame++) {
            velocity += acceleration * DT;
            pivot.x -= velocity * DT;
            AcceptanceSupport.step(state, pivot, REST, lever, 2.36F, 0.5F, mass, limit,
                    STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, new Quaternionf());
            if (frame >= 90) {
                sum += AcceptanceSupport.angleBetween(REST, state.direction);
                samples++;
            }
        }
        float settled = AcceptanceSupport.degrees(sum / samples);

        assertTrue(Float.isFinite(settled), "the settled angle is not finite");
        assertTrue(settled < AcceptanceSupport.degrees(limit) - 1.0F,
                "the pull must not pin the piece at its limit; it is at " + settled
                        + " degrees of " + AcceptanceSupport.degrees(limit));
        assertTrue(settled > 22.0F,
                "a 40 blocks/s^2 lateral pull on a 2.36 Hz, L=0.14 piece balances at about 36"
                        + " degrees; it settled at " + settled + " degrees. An answer at or below"
                        + " asin(0.3) = 17.5 degrees is the outside-force ceiling talking, and that"
                        + " ceiling is what held every panel of a real skirt at its stop while the"
                        + " player stood still.");
    }

    /**
     * A violent pulse is answered, stays bounded, and stays finite.
     *
     * <p>The pulse is delivered as a single-frame pivot teleport, which is what a combat
     * animation does to a shoulder. What is asserted is the pair the in-game report is
     * about: the piece must react (it may not sit still through a 0.15 block jump), and it
     * may not run away (a swing that grows frame after frame is the "flung away" half of
     * the report).
     */
    @Test
    @DisplayName("a violent pivot pulse is answered and does not diverge")
    void aViolentPulseIsAnsweredAndDoesNotDiverge() {
        Vector3f pivot = new Vector3f(0.0F, 0.9F, 0.0F);
        float lever = 0.14F;
        float limit = AcceptanceSupport.radians(40.0);
        float maxSpeed = AcceptanceSupport.constant("MAX_SPEED", 220.0F);

        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        AcceptanceSupport.step(state, pivot, REST, lever, 2.36F, 0.3F, 0.1F, limit,
                STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, new Quaternionf());
        Quaternionf out = new Quaternionf();
        for (int frame = 0; frame < 30; frame++) {
            AcceptanceSupport.step(state, pivot, REST, lever, 2.36F, 0.3F, 0.1F, limit,
                    STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, out);
        }

        pivot.x += 0.15F;
        float peak = 0.0F;
        float peakOmega = 0.0F;
        for (int frame = 0; frame < 240; frame++) {
            AcceptanceSupport.step(state, pivot, REST, lever, 2.36F, 0.3F, 0.1F, limit,
                    STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, out);
            assertTrue(YsmDynamicBoneSolver.isFinite(state.direction)
                            && Float.isFinite(state.angularVelocity.length()),
                    "the state went non-finite at frame " + frame + " of the response");
            assertTrue(state.angularVelocity.length() <= maxSpeed * 1.001F,
                    "|omega| = " + state.angularVelocity.length() + " exceeded the solver's own"
                            + " ceiling of " + maxSpeed + " rad/s at frame " + frame);
            peak = Math.max(peak, AcceptanceSupport.angleBetween(REST, state.direction));
            peakOmega = Math.max(peakOmega, state.angularVelocity.length());
        }

        assertTrue(AcceptanceSupport.degrees(peak) > 3.0F,
                "0.15 blocks of pivot displacement in one frame must be answered; the largest"
                        + " angle seen was " + AcceptanceSupport.degrees(peak) + " degrees");
        assertTrue(AcceptanceSupport.degrees(peak) <= AcceptanceSupport.degrees(limit) + 1.0F,
                "the swing limit must hold; peak was " + AcceptanceSupport.degrees(peak)
                        + " degrees against a limit of " + AcceptanceSupport.degrees(limit));
        assertTrue(AcceptanceSupport.degrees(AcceptanceSupport.angleBetween(REST, state.direction)) < 1.0F,
                "the swing must decay back to rest within 4 s; it is still at "
                        + AcceptanceSupport.degrees(AcceptanceSupport.angleBetween(REST, state.direction))
                        + " degrees");
        assertTrue(peakOmega > 0.0F, "the piece never rotated at all");
    }

    /**
     * While the piece is inside its limit, the angular velocity never changes faster than the
     * forces the solver models can change it - i.e. nothing is quietly cleared or scaled.
     *
     * <h2>Why this is stated as a force bound and not as "no instant recentre"</h2>
     *
     * <p>Two earlier versions of this test went red on legitimate behaviour, and the reason is
     * worth recording because it is the trap in this whole area:
     *
     * <ul>
     *   <li>A 0.15 block pivot teleport in one frame makes the finite-difference acceleration
     *       swing from about +120 to about -120 blocks/s^2 within two frames, and at
     *       {@code L} = 0.14 that is a change of 1700 rad/s^2. An angular velocity is
     *       legitimately cancelled in about a frame. The measurement was reading the <i>input</i>,
     *       not the solver.</li>
     *   <li>At the limit itself, a constraint projection is allowed to remove the entire normal
     *       component in one frame - instantaneously, and correctly. So "the velocity must not
     *       drop by more than X in a frame" is simply not a true statement about a solver that
     *       has a constraint in it, and any threshold picked there tests the fixture.</li>
     * </ul>
     *
     * <p>What remains true everywhere is a bound: inside the limit no constraint is acting, so
     * the frame-to-frame change is bounded by the modelled forces,
     *
     * <pre>
     *   |d omega| / dt  &lt;=  omega_n^2 + 2 zeta omega_n |omega| + |g_eff| / L + drag_max
     * </pre>
     *
     * <p>with {@code |g_eff| <= MAX_PIVOT_ACCEL + GRAVITY} (the pivot acceleration is clamped
     * before it is used) and {@code drag_max} the drag term at its own speed cap. The check
     * carries a 25 percent margin plus half a rad/s for the finite differences. Anything that
     * scales or clears the velocity inside the limit - the failure this guards against - is
     * caught immediately, and nothing physical is flagged.
     */
    @Test
    @DisplayName("inside its limit, the angular velocity changes no faster than the modelled forces allow")
    void theVelocityIsNeverClearedInsideTheLimit() {
        float frequency = 2.36F;
        float zeta = 0.3F;
        float mass = 4.0F;                       // top of the real range; keeps drag out of the way
        float lever = 0.14F;
        float limit = AcceptanceSupport.radians(40.0);
        float insideBand = 0.90F * limit;
        float omegaN = (float) (2.0 * Math.PI * frequency);
        float omegaNSquared = omegaN * omegaN;
        float pivotAccelCap = AcceptanceSupport.constant("MAX_PIVOT_ACCEL", 120.0F);
        float dragCap = AcceptanceSupport.constant("AIR_DRAG", 0.9F)
                * AcceptanceSupport.constant("DRAG_SPEED_CAP", 12.0F)
                * AcceptanceSupport.constant("DRAG_SPEED_CAP", 12.0F) / (mass * lever);

        Vector3f pivot = new Vector3f(0.0F, 0.9F, 0.0F);
        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        AcceptanceSupport.step(state, pivot, REST, lever, frequency, zeta, mass, limit,
                STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, new Quaternionf());
        Quaternionf out = new Quaternionf();

        float previous = 0.0F;
        float previousAngle = 0.0F;
        boolean wasInside = false;
        int samples = 0;
        float worstExcess = -1.0F;
        int worstFrame = -1;
        for (int frame = 0; frame < 400; frame++) {
            if (frame == 30) {
                pivot.x += 0.15F;
            }
            AcceptanceSupport.step(state, pivot, REST, lever, frequency, zeta, mass, limit,
                    STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, out);
            float speed = state.angularVelocity.length();
            float angle = AcceptanceSupport.angleBetween(REST, state.direction);
            boolean inside = angle < insideBand;
            if (previous > 1.0F && inside && wasInside) {
                samples++;
                float bound = (omegaNSquared
                        + 2.0F * zeta * omegaN * Math.max(previous, speed)
                        + (pivotAccelCap + GRAVITY) / lever
                        + dragCap) * DT * 1.25F + 0.5F;
                float excess = (previous - speed) / bound;
                if (excess > worstExcess) {
                    worstExcess = excess;
                    worstFrame = frame;
                }
            }
            previous = speed;
            previousAngle = angle;
            wasInside = inside;
        }

        System.out.println("[T5] limit-bound trace: samples=" + samples
                + ", worst frame-to-frame drop as a fraction of the force bound=" + worstExcess
                + " (frame " + worstFrame + ", limit " + AcceptanceSupport.degrees(limit)
                + " deg, band " + AcceptanceSupport.degrees(insideBand) + " deg)");

        assertTrue(samples >= 10,
                "the fixture never left the piece free inside its limit, so nothing was measured: "
                        + samples + " usable frames");
        assertTrue(worstExcess <= 1.0F,
                "the angular velocity fell faster than the modelled forces can explain at frame "
                        + worstFrame + ": the drop was " + worstExcess + " times the bound"
                        + " (omega_n^2 + damping + |g_eff|/L + drag, times dt, plus margin). A piece"
                        + " that is not touching its limit has nothing but those forces acting on"
                        + " it, so a larger change means the velocity is being cleared or scaled"
                        + " somewhere - which is what 'flung away and snapped home' is made of.");
    }

    /**
     * The solver is stable under a long run of hard, signed pulses rather than smoothing
     * its way to a stuck state.
     *
     * <p>Deterministic on purpose: a random test that happens never to hit the bad frame
     * is worse than no test, and this one has to be reproducible from the report.
     */
    @Test
    @DisplayName("900 hard alternating pulses stay bounded and finite")
    void nineHundredHardPulsesStayBounded() {
        Vector3f pivot = new Vector3f(0.0F, 0.9F, 0.0F);
        float lever = 0.12F;
        float limit = AcceptanceSupport.radians(35.0);
        float maxSpeed = AcceptanceSupport.constant("MAX_SPEED", 220.0F);

        YsmDynamicBoneSolver.SegmentState state = new YsmDynamicBoneSolver.SegmentState();
        AcceptanceSupport.step(state, pivot, REST, lever, 2.36F, 0.4F, 0.1F, limit,
                STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, new Quaternionf());
        Quaternionf out = new Quaternionf();
        float peak = 0.0F;
        for (int frame = 0; frame < 900; frame++) {
            if (frame % 7 == 0) {
                pivot.x += ((frame / 7) % 2 == 0 ? 1.0F : -1.0F) * 0.12F;
            }
            AcceptanceSupport.step(state, pivot, REST, lever, 2.36F, 0.4F, 0.1F, limit,
                    STILL, YsmDynamicBoneSolver.NO_COLLIDERS, 0.02F, null, DT, out);
            assertTrue(YsmDynamicBoneSolver.isFinite(state.direction),
                    "the direction went non-finite at frame " + frame);
            assertTrue(Float.isFinite(state.angularVelocity.length()),
                    "the angular velocity went non-finite at frame " + frame);
            assertTrue(state.angularVelocity.length() <= maxSpeed * 1.001F,
                    "|omega| = " + state.angularVelocity.length() + " exceeded " + maxSpeed
                            + " rad/s at frame " + frame);
            peak = Math.max(peak, AcceptanceSupport.angleBetween(REST, state.direction));
        }
        assertTrue(AcceptanceSupport.degrees(peak) <= AcceptanceSupport.degrees(limit) + 2.0F,
                "the limit must hold under repeated shocks; the peak was "
                        + AcceptanceSupport.degrees(peak) + " degrees against "
                        + AcceptanceSupport.degrees(limit));
        assertTrue(AcceptanceSupport.degrees(peak) > 3.0F,
                "900 pulses and the piece never moved more than "
                        + AcceptanceSupport.degrees(peak) + " degrees, which means it is not being"
                        + " driven at all");
    }

    /** The gravity the suite's arithmetic assumes, so the report's numbers can be re-derived. */
    @Test
    @DisplayName("the solver's gravity is the value the acceptance arithmetic uses")
    void gravityIsWhatTheArithmeticAssumes() {
        assertEquals(24.0F, GRAVITY, 1.0E-6F,
                "the acceptance arithmetic in this file was derived for 24 blocks/s^2; if the"
                        + " default changed, the 22 degree threshold in"
                        + " aSteadySidewaysPullSettlesWellInsideTheLimit must be re-derived");
        assertEquals(1.0F / 60.0F, FRAME, 1.0E-9F, "sanity");
    }

    /**
     * The mutation evidence for the two discriminating assertions, made executable.
     *
     * <p>A verifier may not edit {@code src/main/java}, so the "break it and watch the test go
     * red" step cannot be performed on the living file. It is performed here instead, on the
     * design the task set out to delete: both branches that design produced are recomputed from
     * the same inputs the two behavioural tests use, and this test asserts that those answers
     * <b>fail</b> those tests. If a future change makes this test fail, the two behavioural
     * assertions have stopped discriminating and the report's evidence for them is void.
     *
     * <p>The arithmetic is the removed design's own, read from
     * {@code YsmDynamicBoneSolver.java} as it stood before the fix (recorded at
     * {@code tmp_verify/T5_snapshots/}):
     *
     * <pre>
     *   alpha_external = (d x g) / L  + drag                 // computed first
     *   ceiling        = 0.3 * min(1, maxAngle / 0.349)      // REFERENCE_ANGLE
     *   authority      = omega_n^2 * ceiling
     *   if (|alpha_external| &gt; authority) alpha_external *= authority / |alpha_external|
     *   alpha          = alpha_external + omega_n^2 (d x rest) - 2 zeta omega_n w
     * </pre>
     */
    @Test
    @DisplayName("the removed outside-force ceiling fails both discriminating assertions")
    void theRemovedDesignFailsBothDiscriminatingAssertions() {
        // --- aLongLeverAnswersMoreSlowlyThanAShortOne, replayed against the old design ---
        float frequency = 2.0F;
        float omegaN = (float) (2.0 * Math.PI * frequency);
        float omegaNSquared = omegaN * omegaN;
        float sin56 = (float) Math.sin(AcceptanceSupport.radians(56.0));
        float spring = omegaNSquared * sin56;
        float authority = omegaNSquared * 0.3F;                 // maxAngle/REFERENCE_ANGLE >= 1 here
        float gravityLong = GRAVITY * sin56 / 0.30F;
        float gravityShort = GRAVITY * sin56 / 0.05F;
        float oldGainLong = (spring + Math.min(gravityLong, authority)) * DT;
        float oldGainShort = (spring + Math.min(gravityShort, authority)) * DT;

        assertEquals(oldGainLong, oldGainShort, 1.0E-4F,
                "the removed ceiling did not equalise the two levers after all, so"
                        + " aLongLeverAnswersMoreSlowlyThanAShortOne is not measuring what the"
                        + " report claims it measures");
        assertFalse(oldGainShort > 1.5F * oldGainLong,
                "the removed design would have passed aLongLeverAnswersMoreSlowlyThanAShortOne,"
                        + " which means that assertion proves nothing about the ceiling");

        // --- aSteadySidewaysPullSettlesWellInsideTheLimit, replayed against the old design ---
        // Under the ceiling the equilibrium is where the spring alone balances the saturated
        // outside force: omega_n^2 * sin(theta) = authority, i.e. sin(theta) = 0.3.
        float oldEquilibrium = AcceptanceSupport.degrees((float) Math.asin(0.3));
        assertTrue(oldEquilibrium < 22.0F,
                "the removed design's equilibrium (" + oldEquilibrium + " degrees) is not below the"
                        + " 22 degree threshold, so"
                        + " aSteadySidewaysPullSettlesWellInsideTheLimit is not measuring the ceiling");
    }
}
