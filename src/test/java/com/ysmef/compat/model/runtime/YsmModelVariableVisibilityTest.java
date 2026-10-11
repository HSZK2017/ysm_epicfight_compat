package com.ysmef.compat.model.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YsmModelVariableVisibilityTest {
    private static final String RUNTIME = """
            {
              "bones": [
                {"name":"Body","pivot":[0,0,0],"rot":[0,0,0],"joint":0,"mapped":true},
                {"name":"Hat","parent":"Body","pivot":[0,0,0],"rot":[0,0,0],"joint":0},
                {"name":"Dress","parent":"Body","pivot":[0,0,0],"rot":[0,0,0],"joint":0},
                {"name":"Shoes","parent":"Body","pivot":[0,0,0],"rot":[0,0,0],"joint":0}
              ],
              "animations": {
                "pre_parallel_0": {"loop":1,"length":1,"bones": {
                  "Hat": {"scale":[{"t":0,"post":["v.roaming.hat","v.roaming.hat","v.roaming.hat"]}]},
                  "Dress": {"scale":[{"t":0,"post":["v.bq == 2 ? 1 : 0","v.bq == 2 ? 1 : 0","v.bq == 2 ? 1 : 0"]}]},
                  "Shoes": {"scale":[{"t":0,"post":["variable.shoe","variable.shoe","variable.shoe"]}]}
                }}
              }
            }
            """;

    @Test
    void selectedModelVariablesRevealOnlyTheChosenParts() throws Exception {
        Method compile = YSMRuntimeModel.class.getDeclaredMethod("compile", String.class, JsonObject.class);
        compile.setAccessible(true);
        YSMRuntimeModel model = (YSMRuntimeModel) compile.invoke(null,
                "synthetic_customization", JsonParser.parseString(RUNTIME).getAsJsonObject());
        assertEquals(Set.of("v.roaming.hat", "v.bq", "variable.shoe"), model.modelVariableNames);
        assertEquals(Set.of("Hat", "Dress", "Shoes"), model.defaultHiddenBoneNames());

        Method evaluate = YSMRuntimeModel.class.getDeclaredMethod("computeHiddenWithVariables", Map.class);
        evaluate.setAccessible(true);
        boolean[] onlyHat = (boolean[]) evaluate.invoke(model, Map.of("v.roaming.hat", 1.0f));
        assertFalse(onlyHat[1]);
        assertTrue(onlyHat[2]);
        assertTrue(onlyHat[3]);

        boolean[] allSelected = (boolean[]) evaluate.invoke(model,
                Map.of("v.roaming.hat", 1.0f, "v.bq", 2.0f, "variable.shoe", 1.0f));
        assertFalse(allSelected[1]);
        assertFalse(allSelected[2]);
        assertFalse(allSelected[3]);
    }

    @Test
    void expressionPositionMovesFaceAndChildrenWithoutMovingMappedBody() throws Exception {
        String runtime = """
                {
                  "bones": [
                    {"name":"Body","pivot":[0,0,0],"rot":[0,0,0],"joint":0,"mapped":true},
                    {"name":"Face","parent":"Body","pivot":[0,0,0],"rot":[0,0,0],"joint":0},
                    {"name":"Eye","parent":"Face","pivot":[0,0,0],"rot":[0,0,0],"joint":0},
                    {"name":"MappedChild","parent":"Face","pivot":[0,0,0],"rot":[0,0,0],"joint":0,"mapped":true}
                  ],
                  "animations": {
                    "pre_parallel_0": {"loop":1,"length":1,"bones": {
                      "Body": {"position":[{"t":0,"post":[0,0,"v.bq == 2 ? 16 : 0"]}]},
                      "Face": {"position":[{"t":0,"post":[0,0,"v.bq == 2 ? 16 : 0"]}]}
                    }}
                  }
                }
                """;
        Method compile = YSMRuntimeModel.class.getDeclaredMethod("compile", String.class, JsonObject.class);
        compile.setAccessible(true);
        YSMRuntimeModel model = (YSMRuntimeModel) compile.invoke(null,
                "synthetic_expression_position", JsonParser.parseString(runtime).getAsJsonObject());
        YsmStaticCustomizationTransforms transforms = new YsmStaticCustomizationTransforms(model);
        OpenMatrix4f[] closed = transforms.evaluate(Map.of("v.bq", 0.0f));
        OpenMatrix4f[] selected = transforms.evaluate(Map.of("v.bq", 2.0f));
        assertEquals(null, closed[1]);
        assertEquals(null, selected[0]);
        assertEquals(1.0f, selected[1].m32, 1.0e-5f);
        assertEquals(1.0f, selected[2].m32, 1.0e-5f);
        assertEquals(null, selected[3]);
    }
}
