package com.ysmef.compat.model.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * YSM's own second-order filter, ported so that this mod can reproduce the physics its model authors
 * actually wrote.
 *
 * <p>YSM has no rigid-body simulation. Its entire physics subsystem is a bank of one-dimensional
 * scalar filters that animation authors invoke from molang - {@code ysm.second_order('key', input,
 * frequency, coefficient, response)} - and every hair, tail and skirt that moves in YSM moves
 * because an author wrote an expression for it and wrapped that expression in this filter. So
 * "reproducing YSM's physics" means exactly this class and the {@link Bank} below it: the rest is
 * molang, and the molang is the author's.
 *
 * <p>The algorithm is from {@code OpenYSM}'s {@code SecondOrder}, which is itself the standard
 * second-order system popularised by the talk "Giving Personality to Procedural Animations using
 * Math". Reproduced here rather than re-derived, deliberately and to the letter: the numbers this
 * produces have to match the ones YSM produces for the same authored animation, or a model that
 * looks right in YSM will look wrong here, and no amount of tuning our own solver can fix that. The
 * one thing worth understanding about it is that it is <i>not</i> a spring solver on a physical
 * quantity - it is a filter on a signal the author computed:
 *
 * <pre>
 *   k1 = coefficient / (pi * frequency)          the damping term
 *   k2 = 1 / (2*pi*frequency)^2                  the stiffness term, as a time squared
 *   k3 = response * coefficient / (2*pi*frequency)
 *   accel = (k3 * inputDot + input - value - k1 * velocity) / k2
 * </pre>
 *
 * <p>{@code k3} is what makes it feel alive: it feeds the <i>rate of change of the input</i> in, so
 * a piece anticipates a movement instead of only trailing it. {@code response} is that gain, and
 * YSM leaves it unclamped while clamping frequency to 0..5 Hz and the coefficient to 0..1.
 *
 * <p>Three properties of the reference are load-bearing and are kept exactly:
 *
 * <ol>
 *   <li>The step is divided into {@code ceil(dt / (sqrt(4*k2 + k1^2) - k1))} substeps, which is the
 *       largest stable explicit step for this spring. Our own pendulum solver borrowed the same
 *       rule for the same reason.</li>
 *   <li>{@code inputDot} is computed once per call, from the whole time step, and then reused by
 *       every substep - not recomputed per substep.</li>
 *   <li>Nothing is seeded from zero: the first call for a key returns the input untouched. That is
 *       YSM's anti-snap guard, and it is why a model does not flinch when it first appears.</li>
 * </ol>
 *
 * <p>The one deliberate difference is a ceiling on the substep count. YSM has none, so a two-second
 * frame is two seconds' worth of substeps through every filter on the model; ours stops at
 * {@link #MAX_SUBSTEPS} and accepts a slightly stiffer step on a frame that long. Past a fifth of a
 * second the caller is expected to have clamped the step anyway, so this only ever fires on a stall,
 * where the alternative is a visible freeze.
 */
public final class YsmSecondOrder {

    /** The most substeps one call may take; see the class comment for why this exists. */
    static final int MAX_SUBSTEPS = 256;

    /** YSM clamps the author's frequency to this range, in Hz. */
    static final float MIN_FREQUENCY = 0.0F;
    static final float MAX_FREQUENCY = 5.0F;

    private float inputFunction;
    private float lastSimulation;
    private float lastSimulationDot;
    private float input;
    private float frequency;
    private float coefficient;
    private float response;

    public YsmSecondOrder(float input, float frequency, float coefficient, float response) {
        this.input = input;
        this.frequency = clamp(frequency, MIN_FREQUENCY, MAX_FREQUENCY);
        this.coefficient = clamp(coefficient, 0.0F, 1.0F);
        this.response = response;
    }

    /** The author's arguments for this frame, as the molang call supplied them. */
    public void setArgs(float input, float frequency, float coefficient, float response) {
        this.input = input;
        this.frequency = frequency;
        this.coefficient = coefficient;
        this.response = response;
    }

    /** The filtered value: what the author's expression evaluates to for this frame. */
    public float getValue() {
        return this.lastSimulation;
    }

    /**
     * Advance the filter by one frame. Called once per frame per filter, before anything reads it -
     * the same order YSM uses, so a value read during a frame is the one the previous frame's input
     * produced.
     */
    public void update(float timeStep) {
        float input = this.input;
        float frequency = clamp(this.frequency, MIN_FREQUENCY, MAX_FREQUENCY);
        float coefficient = clamp(this.coefficient, 0.0F, 1.0F);
        float response = this.response;

        if (!Float.isFinite(timeStep) || timeStep <= 0.0F || !Float.isFinite(input)
                || !Float.isFinite(frequency) || !Float.isFinite(coefficient)
                || !Float.isFinite(response)) {
            // No time passed, or an author's expression produced something that is not a number:
            // hold the last value rather than integrating a NaN into every frame that follows.
            return;
        }

        float k1 = coefficient / (float) Math.PI / frequency;
        float k2 = 1.0F / (2.0F * (float) Math.PI * frequency) / (2.0F * (float) Math.PI * frequency);
        float k3 = response * coefficient / 2.0F / (float) Math.PI / frequency;
        if (!Float.isFinite(k1) || !Float.isFinite(k2) || !Float.isFinite(k3) || k2 <= 0.0F) {
            // A frequency of zero divides by it. YSM would produce an infinity here; holding the
            // last value is the same answer for a filter that has no restoring term at all.
            return;
        }

        float inputFunctionDot = (input - this.inputFunction) / timeStep;
        this.inputFunction = input;

        float maxTimeStep = (float) Math.sqrt(4.0F * k2 + k1 * k1) - k1;
        int cycleTime = maxTimeStep > 0.0F ? (int) Math.ceil(timeStep / maxTimeStep) : 1;
        cycleTime = Math.max(1, Math.min(MAX_SUBSTEPS, cycleTime));
        timeStep = timeStep / cycleTime;

        float lastSimulationDot = this.lastSimulationDot;
        float lastSimulation = this.lastSimulation;
        for (; cycleTime > 0; cycleTime--) {
            lastSimulation = lastSimulation + timeStep * lastSimulationDot;
            lastSimulationDot = lastSimulationDot + timeStep
                    * (k3 * inputFunctionDot + input - lastSimulation - k1 * lastSimulationDot) / k2;
        }
        this.lastSimulation = lastSimulation;
        this.lastSimulationDot = lastSimulationDot;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * One filter per author-named key, per entity - the same shape as YSM's {@code PhysicsManager}.
     *
     * <p>Keyed by the interned id of the name the author wrote, so a model whose expressions use
     * {@code '头发垂直'} and {@code '头发水平'} keeps two independent states, and two entities each
     * keep their own. That per-entity separation is not a detail: a shared bank would make one
     * player's hair answer another player's movement.
     *
     * <p>The first call for a key returns its input unchanged and starts the filter there. YSM does
     * the same, and the reason is worth keeping: seeding from zero would make every piece of cloth
     * on a freshly loaded model jump from the origin of its own rotation to wherever it belongs.
     */
    public static final class Bank {

        private final Map<Integer, YsmSecondOrder> filters = new HashMap<>();
        private boolean primed;

        /**
         * The filtered value for this key, or the input itself on the frame the key first appears.
         *
         * @param key         the interned id of the author's name for this filter
         * @param input       the author's expression value for this frame
         * @param frequency   the author's frequency, Hz
         * @param coefficient the author's damping coefficient, 0..1
         * @param response    the author's response gain
         */
        public float filter(int key, float input, float frequency, float coefficient, float response) {
            YsmSecondOrder existing = this.filters.get(key);
            if (existing == null) {
                this.filters.put(key, new YsmSecondOrder(input, frequency, coefficient, response));
                return input;
            }
            existing.setArgs(input, frequency, coefficient, response);
            return existing.getValue();
        }

        /**
         * Advance every filter by one frame.
         *
         * <p>Called once per frame, before the frame's expressions are evaluated - the order YSM
         * uses. A value read during the frame is therefore the one the previous frame's input
         * produced, which is the one-frame lag the whole scheme is built on.
         */
        public void update(float timeStep) {
            for (YsmSecondOrder filter : this.filters.values()) {
                filter.update(timeStep);
            }
        }

        /** Forget everything (the model was reloaded, the entity respawned). */
        public void clear() {
            this.filters.clear();
            this.primed = false;
        }

        /** How many filters this entity is carrying; for the log. */
        public int size() {
            return this.filters.size();
        }

        /** Whether a frame has gone by since the last clear, so the caller can skip a dt it lacks. */
        public boolean isPrimed() {
            return this.primed;
        }

        /** The first frame only establishes the clock: there is no previous frame to measure from. */
        public void prime() {
            this.primed = true;
        }
    }
}
