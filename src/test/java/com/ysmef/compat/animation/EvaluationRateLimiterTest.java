package com.ysmef.compat.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the cadence rules of the animation-evaluation cap, which are the whole reason
 * this class exists instead of a {@code tick % n} check.
 *
 * <p>The behaviour a user actually feels is the achieved rate. A naive limiter that
 * re-schedules the next evaluation from the current frame drifts: under a render loop
 * faster than the target every accepted frame arrives slightly late, so each deadline
 * is pushed a little further out and the achieved rate settles below the configured
 * one. These tests measure the achieved rate over a simulated loop rather than
 * asserting on the deadline arithmetic, so a regression in that respect fails here
 * even though every individual decision still "looks" right.
 */
class EvaluationRateLimiterTest {

    /** A render loop at {@code fps} for {@code seconds}, counting accepted evaluations. */
    private static int evaluationsAt(int rateHz, double fps, double seconds) {
        EvaluationRateLimiter<String> limiter = new EvaluationRateLimiter<>();
        String context = "state";
        int accepted = 0;
        double dt = 1.0 / fps;
        for (double now = 0.0; now < seconds; now += dt) {
            if (limiter.shouldEvaluate(now, rateHz, context, false)) {
                limiter.evaluated(now, rateHz, context);
                accepted++;
            }
        }
        return accepted;
    }

    /**
     * The headline case: a 60 Hz target on a 144 FPS loop must land near 60 Hz, not
     * near the ~48 Hz a re-scheduling limiter converges to.
     */
    @Test
    void achievedRateMatchesTargetEvenWhenFramesAreFasterThanTheTarget() {
        int accepted = evaluationsAt(60, 144.0, 10.0);

        // 10 seconds at 60 Hz is 600 evaluations. Allow the sampling edges to cost a
        // few, but a drifting implementation lands near 480 and must fail this.
        assertTrue(accepted >= 595 && accepted <= 605,
                "expected ~600 evaluations for 60 Hz over 10 s, got " + accepted);
    }

    /** A target slower than the frame rate: the common phone-configuration case. */
    @Test
    void achievedRateMatchesASlowTargetUnderAFastLoop() {
        int accepted = evaluationsAt(30, 240.0, 10.0);

        assertTrue(accepted >= 295 && accepted <= 305,
                "expected ~300 evaluations for 30 Hz over 10 s, got " + accepted);
    }

    /** A target faster than the frame rate can only be honoured as far as frames allow. */
    @Test
    void aTargetAboveTheFrameRateRunsEveryFrame() {
        int accepted = evaluationsAt(240, 60.0, 10.0);

        // 60 FPS for 10 s is 600 frames, and every one of them is due.
        assertEquals(600, accepted, "a target above the frame rate must evaluate every frame");
    }

    @Test
    void zeroOrNegativeRateIsUnlimited() {
        EvaluationRateLimiter<String> limiter = new EvaluationRateLimiter<>();
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.shouldEvaluate(i * 0.001, 0, "s", false),
                    "rate 0 means unlimited and must never gate");
        }
        assertTrue(limiter.shouldEvaluate(0.0, -5, "s", false));
    }

    @Test
    void theFirstDecisionAlwaysEvaluates() {
        EvaluationRateLimiter<String> limiter = new EvaluationRateLimiter<>();
        assertTrue(limiter.shouldEvaluate(100.0, 1, "s", false),
                "an uninitialized limiter must not withhold the first evaluation");
    }

    @Test
    void aForceAlwaysEvaluates() {
        EvaluationRateLimiter<String> limiter = new EvaluationRateLimiter<>();
        limiter.evaluated(0.0, 1, "s");

        assertFalse(limiter.shouldEvaluate(0.1, 1, "s", false),
                "0.1 s into a 1 Hz cadence is not due");
        assertTrue(limiter.shouldEvaluate(0.1, 1, "s", true),
                "force must override the cadence");
    }

    /**
     * A changed context is a different problem and must not inherit the previous
     * cadence: waiting out a deadline would keep playing the previous state's pose.
     */
    @Test
    void aChangedContextEvaluatesImmediately() {
        EvaluationRateLimiter<String> limiter = new EvaluationRateLimiter<>();
        limiter.evaluated(0.0, 1, "idle");

        assertFalse(limiter.shouldEvaluate(0.1, 1, "idle", false));
        assertTrue(limiter.shouldEvaluate(0.1, 1, "run", false),
                "a state change must evaluate at once rather than wait for the deadline");
    }

    /** A rate change re-plans the cadence instead of serving the old one. */
    @Test
    void changingTheRateEvaluatesImmediately() {
        EvaluationRateLimiter<String> limiter = new EvaluationRateLimiter<>();
        limiter.evaluated(0.0, 1, "s");

        assertTrue(limiter.shouldEvaluate(0.1, 60, "s", false),
                "a new configured rate must take effect at once");
    }

    /** A clock that jumped backwards must not stall the limiter indefinitely. */
    @Test
    void aBackwardsClockStillEvaluates() {
        EvaluationRateLimiter<String> limiter = new EvaluationRateLimiter<>();
        limiter.evaluated(100.0, 1, "s");

        assertTrue(limiter.shouldEvaluate(50.0, 1, "s", false),
                "a non-monotonic clock reading must not gate forever");
    }

    @Test
    void resetForgetsEverything() {
        EvaluationRateLimiter<String> limiter = new EvaluationRateLimiter<>();
        limiter.evaluated(0.0, 1, "s");
        limiter.reset();

        assertFalse(limiter.currentContextMatches("s"));
        assertTrue(limiter.shouldEvaluate(0.1, 1, "s", false),
                "after a reset the next evaluation is due");
    }
}
