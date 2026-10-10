package com.ysmef.compat.model;

import com.google.gson.JsonObject;
import com.ysmef.compat.ysm.YsmModelPackage;
import com.ysmef.compat.ysm.script.ScriptAnim;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.TransformSheet;
import yesman.epicfight.api.asset.JsonAssetLoader;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YsmWheelConversionRegressionTest {
    @Test
    void infiniteStaticPoseStillHasPlayableDuration() {
        ScriptAnim anim = new ScriptAnim();
        anim.length = Float.POSITIVE_INFINITY;
        ScriptAnim.BoneChannels channels = new ScriptAnim.BoneChannels();
        channels.position = new ScriptAnim.Channel();
        ScriptAnim.Key key = new ScriptAnim.Key();
        key.time = 0.0f;
        key.post = ScriptAnim.Value.ofNumber(0, -5, 0);
        channels.position.keys.add(key);
        anim.bones.put("Root", channels);

        assertEquals(1.0f, YsmWheelSampler.animationLength(anim));
    }

    @Test
    void downwardRootTranslationKeepsItsDirectionThroughEpicFightJson() throws Exception {
        OpenMatrix4f reference = YsmWheelFrameEncoder.referenceLocal(JointTable.ROOT);
        OpenMatrix4f inverse = OpenMatrix4f.invert(reference, null);
        OpenMatrix4f shift = new OpenMatrix4f();
        shift.m31 = -0.25f;
        OpenMatrix4f local = OpenMatrix4f.mul(inverse, OpenMatrix4f.mul(shift, reference, null), null);
        JsonObject json = YsmWheelFrameEncoder.toJson(ScriptAnim.LOOP_HOLD, 1.0f,
                Map.of(JointTable.ROOT, new OpenMatrix4f[]{local}));
        JsonObject root = json.getAsJsonArray("animation").get(0).getAsJsonObject();
        TransformSheet decoded = JsonAssetLoader.getTransformSheet(root, inverse,
                true, JsonAssetLoader.TransformFormat.MATRIX);
        OpenMatrix4f posed = OpenMatrix4f.mul(reference, decoded.getStartTransform().toMatrix(), null);
        OpenMatrix4f displacement = OpenMatrix4f.mul(posed, inverse, null);

        assertEquals(-0.25f, displacement.m31, 1e-5f);
    }

    @Test
    void localEkuHeldSittingClipsAreConvertible() {
        Assumptions.assumeTrue(System.getenv(YsmModelPackage.CONFIG_ROOT_ENV) != null);
        YsmModelPackage model = YsmModelPackage.load("EKU(1.0.ysm");
        Assumptions.assumeTrue(model != null);
        for (String name : new String[]{"坐1", "坐6", "坐7", "坐8"}) {
            YsmExtraFrameWriter.Clip clip = YsmExtraFrameWriter.convert(model, name);
            assertNotNull(clip, name);
            assertEquals(1.0f, clip.length, name);
            assertEquals(61, clip.frameCount, name);
        }
    }

    @Test
    void localWhalePropVisibilityDoesNotCollapseBipedJoints() {
        Assumptions.assumeTrue(System.getenv(YsmModelPackage.CONFIG_ROOT_ENV) != null);
        YsmModelPackage model = YsmModelPackage.load("DS鲸鱼娘flash.ysm");
        Assumptions.assumeTrue(model != null);
        for (String name : new String[]{"摸鱼", "怒了"}) {
            YsmExtraFrameWriter.Clip clip = YsmExtraFrameWriter.convert(model, name);
            assertNotNull(clip, name);
            assertTrue(clip.localFrames.size() > 1, name + " lost its mapped body motion");
            for (int joint : new int[]{JointTable.ROOT, JointTable.HEAD}) {
                OpenMatrix4f[] frames = clip.localFrames.get(joint);
                if (frames == null) {
                    continue; // An unanimated head keeps the bind pose.
                }
                for (OpenMatrix4f frame : frames) {
                    assertTrue(Math.abs(determinant3(frame)) > 0.1f,
                            name + " collapsed " + JointTable.nameOf(joint));
                }
            }
        }
    }

    private static float determinant3(OpenMatrix4f m) {
        return m.m00 * (m.m11 * m.m22 - m.m12 * m.m21)
                - m.m10 * (m.m01 * m.m22 - m.m02 * m.m21)
                + m.m20 * (m.m01 * m.m12 - m.m02 * m.m11);
    }
}
