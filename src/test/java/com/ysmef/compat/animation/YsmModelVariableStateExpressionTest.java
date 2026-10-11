package com.ysmef.compat.animation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YsmModelVariableStateExpressionTest {
    @Test
    void rouletteAssignmentsPreserveOrdinaryAndRoamingVariables() {
        Map<String, Float> values = YsmModelVariableState.evaluateConfigExpression(Map.of(),
                "v.bq=2;v.roaming.hat=1;");
        assertEquals(2.0f, values.get("v.bq"));
        assertEquals(1.0f, values.get("v.roaming.hat"));

        values = YsmModelVariableState.evaluateConfigExpression(values, "v.bq=v.bq+1;");
        assertEquals(3.0f, values.get("v.bq"));
        assertEquals(1.0f, values.get("v.roaming.hat"));
    }

    @Test
    void queryingAControlDoesNotResetItsStoredChoice() {
        Map<String, Float> values = YsmModelVariableState.evaluateConfigExpression(
                Map.of("v.bq", 3.0f), "v.bq");
        assertEquals(Map.of("v.bq", 3.0f), values);
    }

    @Test
    void firstToggleUsesTheObservedYsmValue() {
        Map<String, Float> values = YsmModelVariableState.evaluateConfigExpression(
                Map.of("v.roaming.hat", 1.0f), "v.roaming.hat=1-v.roaming.hat;");
        assertEquals(0.0f, values.get("v.roaming.hat"));
    }
}
