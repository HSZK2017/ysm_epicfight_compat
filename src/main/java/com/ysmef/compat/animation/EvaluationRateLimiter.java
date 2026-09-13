package com.ysmef.compat.animation;

/**
 * Cadence gate for expensive per-entity work (YSM script/animation evaluation),
 * expressed in Hz rather than in ticks.
 *
 * <p>Why not {@code tick % n}: the existing distance LOD uses exactly that, and it
 * couples the cadence to the tick counter rather than to a rate - it cannot express
 * an arbitrary Hz, and its phase is unrelated to when the entity was last actually
 * evaluated. This limiter instead keeps an absolute deadline per entity and decides
 * from wall-clock time, which is what makes a configured Hz mean what it says.
 *
 * <p>The subtlety it exists for is phase carry. Scheduling the next evaluation as
 * {@code now + interval} on every accepted frame looks right and is not: on a render
 * loop faster than the target, each frame lands a little past the deadline, so the
 * deadline drifts later every time and the achieved rate sags below the target - a
 * 60 Hz target under a 144 FPS loop settles near 48 Hz. Instead the deadline advances
 * by whole intervals from the previous deadline, so the phase is preserved, and a
 * frame that arrives late by several intervals advances past all of them at once
 * (missed samples are coalesced, never replayed as a burst).
 *
 * <p>Ported from EpicYSM's sibling project
 * ({@code AnimationEvaluationRateLimiter}, MIT). Kept free of Minecraft types so the
 * cadence rules can be unit-tested directly.
 *
 * @param <C> an immutable snapshot of what the work depends on; only the most
 *            recently evaluated context is reusable, and a changed context always
 *            evaluates immediately rather than waiting for the deadline
 */
public final class EvaluationRateLimiter<C> {

    private static final double EPSILON = 1.0E-9D;

    private boolean initialized;
    private double lastEvaluatedAt;
    private double nextDeadline;
    private int lastRateHz;
    private C lastContext;

    /**
     * Whether the work should run at {@code now}.
     *
     * @param rateHz  0 or less means unlimited
     * @param force   evaluate regardless (a state change that must not wait)
     * @param context the immutable inputs the work depends on
     */
    public boolean shouldEvaluate(double now, int rateHz, C context, boolean force) {
        return rateHz <= 0
                || force
                || !this.initialized
                || rateHz != this.lastRateHz
                || !Double.isFinite(now)
                || now < this.lastEvaluatedAt
                // A different context is a different problem: never serve it the
                // previous entity's cadence.
                || !currentContextMatches(context)
                || !Double.isFinite(this.nextDeadline)
                || now + EPSILON >= this.nextDeadline;
    }

    /** Whether {@code context} is the one the last evaluation was for. */
    public boolean currentContextMatches(C context) {
        return this.initialized && java.util.Objects.equals(this.lastContext, context);
    }

    /** Record that the work ran at {@code now}, advancing the deadline. */
    public void evaluated(double now, int rateHz, C context) {
        if (!Double.isFinite(now)) {
            reset();
            return;
        }
        double interval = rateHz > 0 ? 1.0D / rateHz : 0.0D;

        if (!this.initialized || rateHz != this.lastRateHz || now < this.lastEvaluatedAt
                || !currentContextMatches(context) || !Double.isFinite(this.nextDeadline)) {
            // New cadence (or a new context): start the phase here.
            this.nextDeadline = now + interval;
        } else if (rateHz > 0 && now + EPSILON >= this.nextDeadline) {
            // Carry the phase: advance by whole intervals from the previous deadline
            // rather than from this (possibly late) frame, and skip every interval
            // that was missed instead of replaying them back to back.
            double missed = Math.floor((now - this.nextDeadline + EPSILON) / interval) + 1.0D;
            this.nextDeadline += missed * interval;
        }

        this.initialized = true;
        this.lastEvaluatedAt = now;
        this.lastRateHz = rateHz;
        this.lastContext = context;
    }

    /** Forget all state (a new world, or a context that can no longer be compared). */
    public void reset() {
        this.initialized = false;
        this.lastEvaluatedAt = 0.0D;
        this.nextDeadline = 0.0D;
        this.lastRateHz = 0;
        this.lastContext = null;
    }
}
