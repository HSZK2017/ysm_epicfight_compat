package com.ysmef.compat.model;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class YsmWheelFrameHashTest {
    @Test
    void templateIdentityTracksGeneratedMatrices() {
        float[] sameSource = {0, 0, 0, 2, 0, 0, 1, 1, 1};
        OpenMatrix4f oldFrame = new OpenMatrix4f();
        OpenMatrix4f correctedFrame = new OpenMatrix4f();
        correctedFrame.m30 = 0.125f;
        YsmExtraFrameWriter.Clip oldClip = clip(sameSource, oldFrame);
        YsmExtraFrameWriter.Clip correctedClip = clip(sameSource, correctedFrame);

        assertNotEquals(YsmWheelFrameHash.of(oldClip), YsmWheelFrameHash.of(correctedClip));
        assertEquals(YsmWheelFrameHash.of(correctedClip),
                YsmWheelFrameHash.of(clip(new float[9], correctedFrame)));
    }

    private static YsmExtraFrameWriter.Clip clip(float[] source, OpenMatrix4f frame) {
        return new YsmExtraFrameWriter.Clip("test", 1, 1, 1,
                Map.of(0, source), Map.of(0, new OpenMatrix4f[]{frame}), new JsonObject());
    }
}
