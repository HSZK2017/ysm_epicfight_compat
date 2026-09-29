package com.ysmef.compat.ysm.script;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Molang evaluator unit tests (pure Java, no Minecraft runtime needed).
 * Locks the arithmetic / variable / ternary / comparison / coalesce / function
 * / statement-sequence semantics the YSM runtime scripts rely on.
 */
public class MolangTest {

    /** Minimal env mirroring the roaming/runtime env semantics (degrees for math.*). */
    private static class MapEnv implements Molang.Env {
        final Map<Integer, Double> vars = new HashMap<>();

        @Override
        public double getVarById(int id) {
            return vars.getOrDefault(id, 0.0);
        }

        @Override
        public boolean hasVarById(int id) {
            return vars.containsKey(id);
        }

        @Override
        public void setVarById(int id, double value) {
            vars.put(id, value);
        }

        @Override
        public double getQueryById(int id) {
            return 0.0;
        }

        @Override
        public double callFunction(String name, double[] args, int argCount) {
            switch (name) {
                case "math.sin":
                    return argCount < 1 ? 0.0 : Math.sin(Math.toRadians(args[0]));
                case "math.abs":
                    return argCount < 1 ? 0.0 : Math.abs(args[0]);
                case "math.floor":
                    return argCount < 1 ? 0.0 : Math.floor(args[0]);
                // math.max / math.min exist in every production env of this mod
                // (YSMPlayerAnimator, YSMRuntimeModel, YsmExtraFrameWriter,
                // YsmRoamingState); they are mirrored here so an expression built out
                // of them behaves in the test exactly as it does in game. The
                // argCount guards copy the production shape - a one-argument min/max
                // is that argument, not an error.
                case "math.min":
                    return argCount < 1 ? 0.0 : (argCount < 2 ? args[0] : Math.min(args[0], args[1]));
                case "math.max":
                    return argCount < 1 ? 0.0 : (argCount < 2 ? args[0] : Math.max(args[0], args[1]));
                case "math.clamp":
                    return argCount < 3 ? 0.0 : Math.max(args[1], Math.min(args[2], args[0]));
                default:
                    return 0.0;
            }
        }

        @Override
        public double callStringFunction(String name, String[] args) {
            return 0.0;
        }
    }

    private static double eval(String src) {
        return Molang.eval(src, new MapEnv());
    }

    @Test
    void arithmeticAndPrecedence() {
        assertEquals(7.0, eval("1 + 2 * 3"), 1e-9);
        assertEquals(9.0, eval("(1 + 2) * 3"), 1e-9);
        assertEquals(2.5, eval("10 / 4"), 1e-9);
        assertEquals(1.0, eval("10 % 3"), 1e-9);
    }

    @Test
    void variableAssignmentAndAugmentedAssignment() {
        assertEquals(10.0, eval("v.x = 5; v.x * 2"), 1e-9);
        assertEquals(8.0, eval("v.x = 5; v.x += 3"), 1e-9);
        assertEquals(2.0, eval("v.x = 5; v.x -= 3"), 1e-9);
    }

    @Test
    void ternaryAndBooleanLogic() {
        assertEquals(10.0, eval("1 > 0 ? 10 : 20"), 1e-9);
        assertEquals(20.0, eval("0 > 1 ? 10 : 20"), 1e-9);
        assertEquals(0.0, eval("1 && 0"), 1e-9);
        assertEquals(1.0, eval("1 || 0"), 1e-9);
        assertEquals(1.0, eval("!0"), 1e-9);
    }

    @Test
    void comparisons() {
        assertEquals(1.0, eval("1 == 1"), 1e-9);
        assertEquals(0.0, eval("1 != 1"), 1e-9);
        assertEquals(1.0, eval("2 >= 2"), 1e-9);
        assertEquals(1.0, eval("1 < 2"), 1e-9);
        assertEquals(0.0, eval("2 <= 1"), 1e-9);
    }

    @Test
    void nullCoalescing() {
        assertEquals(42.0, eval("v.undefined ?? 42"), 1e-9);
        assertEquals(7.0, eval("v.defined = 7; v.defined ?? 42"), 1e-9);
    }

    @Test
    void mathFunctions() {
        assertEquals(1.0, eval("math.sin(90)"), 1e-6);
        assertEquals(3.0, eval("math.abs(-3)"), 1e-9);
        assertEquals(5.0, eval("math.clamp(10, 0, 5)"), 1e-9);
        assertEquals(4.0, eval("math.floor(4.7)"), 1e-9);
    }

    @Test
    void statementSequenceYieldsLastValue() {
        assertEquals(3.0, eval("v.a = 1; v.b = 2; v.a + v.b"), 1e-9);
        assertEquals(2.0, eval("v.a = 1; v.a = 2"), 1e-9);
    }

    @Test
    void divisionByZeroIsSanitized() {
        assertEquals(0.0, eval("1 / 0"), 1e-9);
    }

    @Test
    void brokenExpressionsFallBackToZeroWithoutThrowing() {
        assertEquals(0.0, eval("1 +"), 1e-9);
        assertEquals(0.0, eval(""), 1e-9);
        assertEquals(0.0, eval(null), 1e-9);
        assertEquals(0.0, eval("v.x[0]"), 1e-9); // unsupported syntax -> logged zero
    }

    @Test
    void unaryMinus() {
        assertEquals(-5.0, eval("-5"), 1e-9);
        assertEquals(-3.0, eval("v.x = 3; -v.x"), 1e-9);
        assertEquals(3.0, eval("v.x = 3; --v.x"), 1e-9);
    }

    @Test
    void constantFoldingProducesSameValue() {
        MapEnv env = new MapEnv();
        Molang.Expr folded = Molang.compile("2 + 2");
        assertEquals(4.0, folded.eval(env), 1e-9);
        assertEquals(4.0, folded.eval(new MapEnv()), 1e-9);
    }

    @Test
    void functionCallsReceiveExactArgumentCount() {
        java.util.concurrent.atomic.AtomicInteger seenArgCount = new java.util.concurrent.atomic.AtomicInteger(-1);
        Molang.Env env = new Molang.Env() {
            @Override
            public double getVarById(int id) {
                return 0.0;
            }

            @Override
            public boolean hasVarById(int id) {
                return false;
            }

            @Override
            public void setVarById(int id, double value) {
            }

            @Override
            public double getQueryById(int id) {
                return 0.0;
            }

            @Override
            public double callFunction(String name, double[] args, int argCount) {
                seenArgCount.set(argCount);
                return 0.0;
            }

            @Override
            public double callStringFunction(String name, String[] args) {
                return 0.0;
            }
        };
        Molang.compile("math.sin(1)").eval(env);
        assertEquals(1, seenArgCount.get(), "reused argument slots must not widen the call");
        Molang.compile("math.clamp(1, 2, 3)").eval(env);
        assertEquals(3, seenArgCount.get(), "each call must receive its own argument count");
    }

    /**
     * A call's arguments must survive a nested call in a later argument position.
     *
     * <p>Every argument is staged in a scratch array before the call is made.
     * Evaluating argument i runs another call, which starts filling from index 0 and
     * overwrites the slots 0..i-1 this call has already filled - so the outer call
     * receives a mixture of its own earlier arguments and the inner call's. The
     * failure is silent: the expression compiles, evaluates, and answers a wrong
     * number. These were the missing assertions - every other {@code math.*} case in
     * this class either contains no nested call at all, or nests only in argument 0,
     * where the overwrite is harmless because nothing has been written yet.
     */
    @Test
    void nestedCallInALaterArgumentKeepsTheOuterArguments() {
        assertEquals(5.0, eval("math.max(5, math.min(1, 2))"), 1e-9,
                "argument 0 (5) must survive the nested min() in argument 1");
        assertEquals(9.0, eval("math.max(9, math.abs(-3))"), 1e-9,
                "argument 0 (9) must survive the nested abs() in argument 1");
        assertEquals(5.0, eval("math.max(5, math.min(3, 2))"), 1e-9);
        assertEquals(4.0, eval("math.clamp(0, 4, math.max(1, 2))"), 1e-9,
                "both earlier arguments of clamp() must survive a nested max() in argument 2");
        assertEquals(3.0, eval("math.max(1, math.max(2, math.min(3, 4)))"), 1e-9,
                "three levels of nesting");
    }

    /** The nesting that already worked: a call in argument position 0. Pinned so a fix cannot break it. */
    @Test
    void nestedCallInArgumentZeroStillWorks() {
        assertEquals(4.0, eval("math.abs(math.min(-4, 9))"), 1e-9);
        assertEquals(5.0, eval("math.clamp(math.abs(-9), 0, 5)"), 1e-9);
    }

    /**
     * The same rule for a call that mixes a string literal with numbers - the shape
     * YSM's own physics bindings use, e.g.
     * {@code ysm.second_order('头发垂直', math.clamp(...), 1.5, 0.6, 0)}.
     *
     * <p>Both arrays are slot-aligned with the argument list: {@code strings} carries
     * the literal at a string position and null elsewhere, {@code numbers} carries the
     * value at a numeric position. The nested call has to be a string-bearing one here:
     * a numeric call nested inside a mixed call was already harmless, because the two
     * kinds staged their arguments in separate scratch arrays. The mixed-to-mixed case
     * is the one that shared an array, and it is what this pins.
     */
    @Test
    void nestedMixedCallInALaterArgumentKeepsTheOuterArguments() {
        RecordingMixedEnv env = new RecordingMixedEnv();
        Molang.eval("ysm.outer(1, 'x', ysm.inner('y', 2))", env);
        assertEquals(3, env.argCount, "the outer call sees three arguments");
        assertNull(env.strings[0], "argument 0 is numeric, so its string slot is null");
        assertEquals("x", env.strings[1], "argument 1 is the string literal");
        assertNull(env.strings[2], "argument 2 is a nested call, so its string slot is null");
        assertEquals(1.0, env.numbers[0], 1e-9,
                "argument 0 must survive the nested string-bearing call in argument 2");
        assertEquals(0.0, env.numbers[1], 1e-9,
                "the placeholder of the string argument must survive that nested call too");
    }

    /** Records what a mixed call received, so the numbers can be checked position by position. */
    private static final class RecordingMixedEnv extends MapEnv {
        String[] strings;
        double[] numbers;
        int argCount;

        @Override
        public boolean wantsMixedArguments() {
            return true;
        }

        @Override
        public double callMixedFunction(String name, String[] stringArgs, double[] numberArgs, int count) {
            this.strings = stringArgs.clone();
            this.numbers = java.util.Arrays.copyOf(numberArgs, count);
            this.argCount = count;
            return count < 2 ? 0.0 : numberArgs[1];
        }
    }

    /**
     * The scratch is per thread, not shared: a compiled expression is cached once and
     * evaluated from the render thread and from the async script pool.
     */
    @Test
    void argumentScratchIsPerThread() throws Exception {
        Molang.Expr expr = Molang.compile("math.max(5, math.min(1, 2))");
        int threads = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<Double>> results = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    start.await();
                    double value = 0.0;
                    for (int i = 0; i < 200; i++) {
                        value = expr.eval(new MapEnv());
                    }
                    return value;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<Double> future : results) {
                assertEquals(5.0, future.get(), 1e-9, "each thread must stage its own arguments");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
