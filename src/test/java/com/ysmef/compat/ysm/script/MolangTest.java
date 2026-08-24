package com.ysmef.compat.ysm.script;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Molang evaluator unit tests (pure Java, no Minecraft runtime needed).
 * Locks the arithmetic / variable / ternary / comparison / coalesce / function
 * / statement-sequence semantics the YSM runtime scripts rely on.
 */
public class MolangTest {

    /** Minimal env mirroring the roaming/runtime env semantics (degrees for math.*). */
    private static final class MapEnv implements Molang.Env {
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
        public double callFunction(String name, double[] args) {
            switch (name) {
                case "math.sin":
                    return Math.sin(Math.toRadians(args[0]));
                case "math.abs":
                    return Math.abs(args[0]);
                case "math.floor":
                    return Math.floor(args[0]);
                case "math.clamp":
                    return Math.max(args[1], Math.min(args[2], args[0]));
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
}
