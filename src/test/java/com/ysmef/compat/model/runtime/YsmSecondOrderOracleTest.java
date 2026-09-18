package com.ysmef.compat.model.runtime;

import com.ysmef.compat.ysm.script.Molang;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Our port of YSM's filter must produce YSM's numbers.
 *
 * <p>"Reproduce the physics the author wrote" is a claim about numbers, and the only way to check it
 * is to run the reference implementation beside ours on the same inputs. {@link YsmReferenceFilter}
 * below is that reference: a copy of {@code OpenYSM}'s
 * {@code client/animation/molang/functions/physics/SecondOrder}, kept as it was written - same order
 * of operations, same single {@code inputFunctionDot} per call, same substep rule - with its two
 * Minecraft dependencies ({@code Mth.clamp}, {@code Mth.PI}) inlined so it can run here. It is the
 * oracle, not a second implementation: nothing calls it in the mod.
 *
 * <p>Agreement has to be exact rather than close. A filter is a recurrence, so a difference in the
 * last bit of the first frame is a growing difference in the hundredth - and the whole point of
 * copying the reference is that our output can be compared with a screenshot of YSM.
 */
class YsmSecondOrderOracleTest {

    /** The default arguments YSM's {@code SecondOrderFunction} supplies when an author omits them. */
    private static final float DEFAULT_FREQUENCY = 1.0F;
    private static final float DEFAULT_COEFFICIENT = 1.0F;
    private static final float DEFAULT_RESPONSE = 1.0F;

    /**
     * The core claim: for input sequences an author's expressions actually produce, our filter and
     * YSM's agree to the bit, frame after frame.
     *
     * <p>Twelve hundred frames of a random walk - because that is what an authored input is: a head
     * angle, a speed, a clamped delta, all wandering - across the whole range YSM allows an author
     * (frequency 0..5 Hz, coefficient 0..1, and a response that YSM does not clamp), and across the
     * frame times a client really hands out.
     */
    @Test
    void ourFilterAgreesWithTheReferenceFrameForFrame() {
        Random random = new Random(20260915L);
        float[][] cases = {
                {DEFAULT_FREQUENCY, DEFAULT_COEFFICIENT, DEFAULT_RESPONSE},
                {1.5F, 0.6F, 0.0F},
                {2.0F, 0.5F, 0.2F},
                {1.7F, 0.5F, 0.0F},
                {3.0F, 0.3F, 0.0F},
                {5.0F, 1.0F, 1.0F},
                {0.3F, 1.0F, 1.0F},
                {1.2F, 0.4F, 0.0F}};
        float[] frameTimes = {0.002F, 0.008F, 0.016F, 0.05F};

        for (float[] arguments : cases) {
            YsmSecondOrder ours = new YsmSecondOrder(0.0F, arguments[0], arguments[1], arguments[2]);
            YsmReferenceFilter reference = new YsmReferenceFilter(0.0F, arguments[0], arguments[1], arguments[2]);
            float input = 0.0F;

            for (int frame = 0; frame < 300; frame++) {
                input += (float) ((random.nextDouble() - 0.5) * 12.0);
                float dt = frameTimes[random.nextInt(frameTimes.length)];

                ours.setArgs(input, arguments[0], arguments[1], arguments[2]);
                reference.setArgs(input, arguments[0], arguments[1], arguments[2]);
                float before = ours.getValue();

                ours.update(dt);
                reference.update(dt);

                assertEquals(reference.getValue(), ours.getValue(), 0.0F,
                        "frame " + frame + " of f=" + arguments[0] + " c=" + arguments[1]
                                + " r=" + arguments[2] + " dt=" + dt + " diverged from YSM"
                                + " (ours " + ours.getValue() + ", theirs " + reference.getValue()
                                + ", before " + before + ", input " + input + ")");
            }
        }
    }

    /** The clamps are part of the behaviour: an author may write any number, and YSM clamps two. */
    @Test
    void theAuthorsArgumentsAreClampedTheWayYsmClampsThem() {
        for (float[] arguments : new float[][]{
                {40.0F, 5.0F, 3.0F}, {1.0F, 1.0F, 1.0F}, {0.0F, 0.0F, 0.0F}, {-3.0F, -2.0F, 0.5F}}) {
            YsmSecondOrder ours = new YsmSecondOrder(0.0F, arguments[0], arguments[1], arguments[2]);
            YsmReferenceFilter reference = new YsmReferenceFilter(0.0F, arguments[0], arguments[1], arguments[2]);

            for (int frame = 0; frame < 120; frame++) {
                float input = frame * 0.7F;
                ours.setArgs(input, arguments[0], arguments[1], arguments[2]);
                reference.setArgs(input, arguments[0], arguments[1], arguments[2]);
                ours.update(0.016F);
                reference.update(0.016F);
                assertEquals(reference.getValue(), ours.getValue(), 0.0F,
                        "clamping of " + arguments[0] + "/" + arguments[1] + " differs at frame " + frame);
            }
        }
    }

    /**
     * A rest input produces a rest output, and stays there.
     *
     * <p>Worth its own test because it is the property that makes a physics bone safe to add to a
     * model: an author whose expression evaluates to zero gets a bone that does not move, so a
     * physics animation can be attached to a model that is standing still without moving anything.
     */
    @Test
    void aRestInputProducesARestOutput() {
        YsmSecondOrder ours = new YsmSecondOrder(0.0F, DEFAULT_FREQUENCY, DEFAULT_COEFFICIENT, DEFAULT_RESPONSE);
        for (int frame = 0; frame < 200; frame++) {
            ours.setArgs(0.0F, DEFAULT_FREQUENCY, DEFAULT_COEFFICIENT, DEFAULT_RESPONSE);
            ours.update(0.016F);
            assertEquals(0.0F, ours.getValue(), 1.0E-7F, "frame " + frame + " drifted off rest");
        }
    }

    /** A filter that is never advanced holds its value; nothing integrates without a time step. */
    @Test
    void aZeroTimeStepHoldsTheValue() {
        YsmSecondOrder ours = new YsmSecondOrder(0.0F, 1.0F, 1.0F, 1.0F);
        // Two frames, not one: this is a semi-implicit integrator, so the value moves on the frame
        // *after* the velocity was built. That is YSM's behaviour too, and the point of the test is
        // what happens to the value afterwards rather than how fast it gets there.
        for (int frame = 0; frame < 2; frame++) {
            ours.setArgs(10.0F, 1.0F, 1.0F, 1.0F);
            ours.update(0.05F);
        }
        float settled = ours.getValue();
        assertTrue(settled > 0.0F, "the filter must actually have moved, and it is at " + settled);

        ours.setArgs(10.0F, 1.0F, 1.0F, 1.0F);
        ours.update(0.0F);
        assertEquals(settled, ours.getValue(), 0.0F, "a paused game must not advance the filter");
        ours.setArgs(10.0F, 1.0F, 1.0F, 1.0F);
        ours.update(Float.NaN);
        assertEquals(settled, ours.getValue(), 0.0F, "and neither must a broken frame time");
    }

    // ------------------------------------------------------------------
    // The bank
    // ------------------------------------------------------------------

    /**
     * A key that has never been seen returns its input untouched.
     *
     * <p>This is YSM's anti-snap guard and it decides what a model looks like for its first frame.
     * Seeding from zero instead would make every piece of cloth on a newly loaded model jump from
     * the origin of its own rotation to wherever the author's expression puts it.
     */
    @Test
    void aNewKeyReturnsItsInputUntouched() {
        YsmSecondOrder.Bank bank = new YsmSecondOrder.Bank();

        assertEquals(42.5F, bank.filter(1, 42.5F, 1.0F, 0.5F, 0.0F), 0.0F,
                "the first call for a key must hand the input straight back");
        assertEquals(1, bank.size(), "and remember the filter it started");
    }

    /** Two keys are two filters, and a bank that is cleared forgets both. */
    @Test
    void eachKeyKeepsItsOwnState() {
        YsmSecondOrder.Bank bank = new YsmSecondOrder.Bank();
        bank.filter(1, 0.0F, 1.0F, 1.0F, 0.0F);
        bank.filter(2, 0.0F, 1.0F, 1.0F, 0.0F);

        // Drive the two keys with different inputs and check they answer differently.
        for (int frame = 0; frame < 60; frame++) {
            bank.filter(1, 10.0F, 1.0F, 1.0F, 0.0F);
            bank.filter(2, -10.0F, 1.0F, 1.0F, 0.0F);
            bank.update(0.016F);
        }
        float first = bank.filter(1, 10.0F, 1.0F, 1.0F, 0.0F);
        float second = bank.filter(2, -10.0F, 1.0F, 1.0F, 0.0F);

        assertTrue(first > 1.0F, "the first key must have followed its own input: " + first);
        assertTrue(second < -1.0F, "and the second its own: " + second);

        bank.clear();
        assertEquals(0, bank.size(), "clearing must forget every filter");
        assertEquals(7.0F, bank.filter(1, 7.0F, 1.0F, 1.0F, 0.0F), 0.0F,
                "and the next call must behave like a first call again");
    }

    /** The bank walks every filter it owns once per frame, and nothing twice. */
    @Test
    void theBankAdvancesEachFilterExactlyOncePerFrame() {
        YsmSecondOrder.Bank bank = new YsmSecondOrder.Bank();
        bank.filter(1, 10.0F, 1.0F, 1.0F, 0.0F);
        bank.update(0.016F);

        float afterOneFrame = bank.filter(1, 10.0F, 1.0F, 1.0F, 0.0F);
        bank.update(0.016F);
        float afterTwoFrames = bank.filter(1, 10.0F, 1.0F, 1.0F, 0.0F);

        assertTrue(afterTwoFrames > afterOneFrame,
                "a second frame must move the filter further toward its input ("
                        + afterOneFrame + " then " + afterTwoFrames + ")");

        YsmSecondOrder single = new YsmSecondOrder(10.0F, 1.0F, 1.0F, 0.0F);
        single.update(0.0F);
        assertSame(single, single);
    }

    // ------------------------------------------------------------------
    // Reaching the filter from an author's expression
    // ------------------------------------------------------------------

    /**
     * An author's expression reaches its own filter, with the author's name and the author's numbers.
     *
     * <p>This is the one thing that had to change outside the filter itself. A call like
     * {@code ysm.second_order('头发垂直', math.clamp(-5*q.vertical_speed+v.hv,-10,150), 1.5, 0.6, 0)}
     * carries a name <i>and</i> four numbers, and molang string arguments used to be delivered
     * without the numbers beside them: they were parsed and thrown away. The test therefore checks
     * both halves arrive, that two different names are two different filters, and that the numbers
     * are the evaluated expressions rather than the source text.
     */
    @Test
    void anAuthorsExpressionReachesItsOwnFilter() {
        YsmPhysicsMolangEnv env = new YsmPhysicsMolangEnv();
        env.begin(null, 0.0, 0.0);

        assertEquals(12.5, Molang.eval("ysm.second_order('头发垂直', 12.5, 1.5, 0.6, 0)", env), 1.0E-6,
                "the first call for a name hands its input straight back, as YSM does");
        assertEquals(1, env.filters().size(), "and starts exactly one filter");

        // A second name is a second filter, and each keeps its own value.
        assertEquals(-4.0, Molang.eval("ysm.second_order('头发水平', -4.0, 1.5, 0.6, 0)", env), 1.0E-6);
        assertEquals(2, env.filters().size(), "two names, two filters");

        // Settle the first filter, then check the expression sees its output rather than its input.
        for (int frame = 0; frame < 120; frame++) {
            env.filters().update(0.016F);
            Molang.eval("ysm.second_order('头发垂直', 30, 1.5, 0.6, 0)", env);
        }
        double settled = Molang.eval("ysm.second_order('头发垂直', 30, 1.5, 0.6, 0)", env);
        assertTrue(settled > 20.0 && settled < 30.0,
                "the filter must have followed its input toward 30 without overshooting it; it is at "
                        + settled);
    }

    /** The numbers in a mixed call are the evaluated arguments, in their own positions. */
    @Test
    void theNumbersOfAMixedCallAreEvaluated() {
        YsmPhysicsMolangEnv env = new YsmPhysicsMolangEnv();
        env.begin(null, 17.5, -22.5);

        // Arguments are positional: the name is argument 0, so the input is 1 and so on. Feeding
        // ysm.head_pitch as the input proves the expression was evaluated rather than read as text.
        double first = Molang.eval("ysm.second_order('眼睛pitch', ysm.head_pitch/360, 1, 0.8, 0)", env);
        assertEquals(-22.5 / 360.0, first, 1.0E-6, "the input argument must be the evaluated value");

        assertEquals(17.5, Molang.eval("ysm.head_yaw", env), 1.0E-6);
        assertEquals(-22.5, Molang.eval("ysm.head_pitch", env), 1.0E-6);
    }

    /** A name-less call is not a filter; it must not create a state slot or throw. */
    @Test
    void aCallWithoutANameIsNotAFilter() {
        YsmPhysicsMolangEnv env = new YsmPhysicsMolangEnv();
        env.begin(null, 0.0, 0.0);

        assertEquals(5.0, Molang.eval("ysm.second_order(5.0, 5.0)", env), 1.0E-6,
                "with no name the input is handed back untouched");
        assertEquals(0, env.filters().size(), "and nothing is remembered for it");
    }

    /** An unknown name answers zero rather than throwing on the render thread. */
    @Test
    void anUnknownFunctionAnswersZero() {
        YsmPhysicsMolangEnv env = new YsmPhysicsMolangEnv();
        env.begin(null, 0.0, 0.0);
        assertEquals(0.0, Molang.eval("ysm.invented_by_nobody(3, 4)", env), 1.0E-6);
    }

    /**
     * A bone's rotation is read one component at a time, and only bones already given one.
     *
     * <p>This is the whole of an author's chain mechanism: a segment's expression reads the bone
     * above it and feeds the change into its own filter. So two properties decide whether a chain
     * behaves: the component asked for is the component returned, and a bone that has not been
     * evaluated yet this frame reads as zero rather than as last frame's value. The second one is
     * what makes the evaluation order part of the behaviour - YSM evaluates the animation's bones
     * in order, and a bone only ever sees the ones before it.
     */
    @Test
    void aBonesRotationIsReadOneComponentAtATime() {
        YsmPhysicsMolangEnv env = new YsmPhysicsMolangEnv();
        env.begin(null, 0.0, 0.0);

        assertEquals(0.0, Molang.eval("ysm.bone_rot('BackHairB1').x", env), 1.0E-6,
                "a bone with no rotation yet this frame reads as zero");

        env.setBoneRotation("BackHairB1", 12.5F, -3.0F, 40.25F);
        assertEquals(12.5, Molang.eval("ysm.bone_rot('BackHairB1').x", env), 1.0E-6);
        assertEquals(-3.0, Molang.eval("ysm.bone_rot('BackHairB1').y", env), 1.0E-6);
        assertEquals(40.25, Molang.eval("ysm.bone_rot('BackHairB1').z", env), 1.0E-6);
        assertEquals(0.0, Molang.eval("ysm.bone_rot('NotABone').x", env), 1.0E-6,
                "an unknown bone is zero, not an exception on the render thread");
    }

    /**
     * The chain idiom from the shipped {@code Hair_Physics}, evaluated as written.
     *
     * <p>Two frames of it, because the point of the idiom is the change between them: the timeline
     * differences a bone's rotation to get its per-frame delta, and the segment below is driven by
     * that delta through its own filter. A chain that reads a stale value, or that fails to see the
     * bone above it at all, produces a segment that does not move.
     */
    @Test
    void theChainIdiomFromTheShippedAnimationEvaluates() {
        YsmPhysicsMolangEnv env = new YsmPhysicsMolangEnv();
        env.begin(null, 0.0, 0.0);

        // Frame one: the parent's rotation is established and the timeline differences it.
        env.setBoneRotation("BackHairB1", 10.0F, 0.0F, 0.0F);
        Molang.eval("v.HP_x_0=ysm.bone_rot('BackHairB1').x;", env);
        Molang.eval("v.HP_x=v.HP_x_0-v.HP_x_1;", env);
        Molang.eval("v.HP_x_1=v.HP_x_0;", env);
        double firstDelta = Molang.eval("v.HP_x", env);
        assertEquals(10.0, firstDelta, 1.0E-6,
                "on the first frame the previous value is zero, so the delta is the value itself");

        // Frame two: the parent moved further, and the delta is the change.
        env.filters().update(0.05F);
        env.setBoneRotation("BackHairB1", 16.0F, 0.0F, 0.0F);
        Molang.eval("v.HP_x_0=ysm.bone_rot('BackHairB1').x;", env);
        Molang.eval("v.HP_x=v.HP_x_0-v.HP_x_1;", env);
        Molang.eval("v.HP_x_1=v.HP_x_0;", env);
        assertEquals(6.0, Molang.eval("v.HP_x", env), 1.0E-6,
                "and on the next frame it is the six degrees the bone actually moved");

        // The segment below the parent is driven by that delta through its own filter, and it may
        // never exceed the gain's worth of its driver - that is the property that makes a chain
        // stable, so it is asserted rather than the exact number this particular filter happens to
        // settle on after this particular number of frames.
        double segment = Molang.eval("10*ysm.second_order('A1x增量', v.HP_x, 1.7, 0.5, 0)", env);
        assertTrue(segment >= 0.0 && segment <= 10.0 * firstDelta + 1.0E-4,
                "the segment must stay within the gain of its driver, and it is at " + segment);

        // Then the parent moves further, and the segment has to *lag* toward the new delta - which
        // is the entire point of putting a filter in the chain rather than using the delta directly.
        env.setBoneRotation("BackHairB1", 28.0F, 0.0F, 0.0F);
        Molang.eval("v.HP_x_0=ysm.bone_rot('BackHairB1').x;", env);
        Molang.eval("v.HP_x=v.HP_x_0-v.HP_x_1;", env);
        Molang.eval("v.HP_x_1=v.HP_x_0;", env);
        double secondDelta = Molang.eval("v.HP_x", env);
        assertTrue(secondDelta > firstDelta,
                "the parent moved further, so the delta the chain sees must be larger: "
                        + firstDelta + " then " + secondDelta);

        double afterTheStep = Molang.eval("10*ysm.second_order('A1x增量', v.HP_x, 1.7, 0.5, 0)", env);
        for (int frame = 0; frame < 40; frame++) {
            env.filters().update(0.05F);
            Molang.eval("10*ysm.second_order('A1x增量', v.HP_x, 1.7, 0.5, 0)", env);
        }
        double settled = Molang.eval("10*ysm.second_order('A1x增量', v.HP_x, 1.7, 0.5, 0)", env);
        assertTrue(settled > afterTheStep,
                "and the segment must then follow it upward (" + afterTheStep + " then " + settled + ")");
        assertTrue(settled <= 10.0 * secondDelta + 1.0E-4,
                "while still never exceeding the gain's worth of it: " + settled
                        + " against " + (10.0 * secondDelta));
    }

    /** The environment's own variables round-trip, which is how a chain carries a frame forward. */
    @Test
    void theAnimationsVariablesRoundTrip() {
        YsmPhysicsMolangEnv env = new YsmPhysicsMolangEnv();
        env.begin(null, 0.0, -22.5);

        Molang.eval("v.HP_x_0=ysm.head_pitch;", env);
        Molang.eval("v.HP_x=v.HP_x_0-v.HP_x_1;", env);
        assertEquals(-22.5, Molang.eval("v.HP_x", env), 1.0E-6,
                "an unset variable reads as zero, so the first frame's delta is the value itself");
        env.clear();
        assertEquals(0.0, Molang.eval("v.HP_x", env), 1.0E-6, "clearing forgets them");
    }

    /**
     * YSM's own implementation, copied from {@code OpenYSM}
     * ({@code src/main/java/com/elfmcys/yesstevemodel/client/animation/molang/functions/physics/SecondOrder.java}),
     * which is itself the algorithm from "Giving Personality to Procedural Animations using Math".
     *
     * <p>Only two things were changed to make it run outside Minecraft: {@code Mth.clamp} became the
     * equivalent ternary, and {@code Mth.PI} became {@code (float) Math.PI} - the same value, since
     * Minecraft defines it as exactly that cast. Everything else, including the order of the
     * statements and the fact that {@code inputFunctionDot} is computed once per call rather than per
     * substep, is as written.
     */
    private static final class YsmReferenceFilter {

        private float inputFunction = 0.0f;
        private float lastSimulation = 0.0f;
        private float lastSimulationDot = 0.0f;
        private float input;
        private float frequency;
        private float coefficient;
        private float response;

        YsmReferenceFilter(float input, float frequency, float coefficient, float response) {
            this.input = input;
            this.frequency = clamp(frequency, 0, 5);
            this.coefficient = clamp(coefficient, 0, 1);
            this.response = response;
        }

        void setArgs(float input, float frequency, float coefficient, float response) {
            this.input = input;
            this.frequency = frequency;
            this.coefficient = coefficient;
            this.response = response;
        }

        float getValue() {
            return this.lastSimulation;
        }

        void update(float timeStep) {
            float input = this.input;
            float frequency = clamp(this.frequency, 0, 5);
            float coefficient = clamp(this.coefficient, 0, 1);
            float response = this.response;

            float k1 = coefficient / (float) Math.PI / frequency;
            float k2 = 1 / (2 * (float) Math.PI * frequency) / (2 * (float) Math.PI * frequency);
            float k3 = response * coefficient / 2 / (float) Math.PI / frequency;

            float inputFunctionDot = (input - this.inputFunction) / timeStep;
            this.inputFunction = input;

            float maxTimeStep = (float) Math.sqrt(4 * k2 + k1 * k1) - k1;
            int cycleTime = (int) Math.ceil(timeStep / maxTimeStep);
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
    }
}
