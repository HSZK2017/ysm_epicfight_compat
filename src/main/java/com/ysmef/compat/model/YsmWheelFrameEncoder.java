package com.ysmef.compat.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ysmef.compat.ysm.script.ScriptAnim;
import yesman.epicfight.api.asset.JsonAssetLoader;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.Map;

/** Encodes sampled local joint matrices for Epic Fight's JSON animation loader. */
final class YsmWheelFrameEncoder {
    private static final int JOINT_COUNT = JointTable.COUNT;
    private static final int JOINT_ROOT = JointTable.ROOT;
    private static final String[] JOINT_NAMES = JointTable.NAMES;

    private YsmWheelFrameEncoder() {}

    static OpenMatrix4f referenceLocal(int joint) {
        return REF_LOCALS[joint];
    }

    /** Raw reference-biped joint transforms from assets/epicfight/animmodels/entity/biped.json. */
    private static final float[][] REF_RAW = {
            {1.0f, 0.0f, 0.0f, -5e-06f, 0.0f, 0.0f, -1.0f, 0.000946f, 0.0f, 1.0f, 0.0f, 0.763972f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, -0.0f, -0.0f, 0.124994f, 0.0f, -1.0f, 1e-06f, -0.002831f, -0.0f, -0.0f, -1.0f, -1.2e-05f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, 1e-06f, 0.0f, -0.0f, 1.0f, 0.0f, 0.37472f, -1e-06f, -0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, -0.0f, 0.0f, -0.0f, 1e-06f, -1.0f, 0.37472f, -0.0f, 1.0f, 1e-06f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, -0.0f, -0.0f, -0.125006f, 0.0f, -1.0f, 1e-06f, -0.002831f, -0.0f, -0.0f, -1.0f, -1.2e-05f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, 1e-06f, -0.0f, -0.0f, 1.0f, 0.0f, 0.37472f, -1e-06f, -0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, -0.0f, -0.0f, -0.0f, 1e-06f, -1.0f, 0.37472f, -0.0f, 1.0f, 1e-06f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.05f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.3f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.4f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {0.0f, 0.952114f, 0.305743f, 0.0f, 0.0f, -0.305743f, 0.952114f, 0.4f, 1.0f, 0.0f, -0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {0.0f, -0.0f, -1.0f, -0.0f, 0.952114f, 0.305743f, 0.0f, 0.39386f, 0.305743f, -0.952114f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, 0.0f, 0.0f, -0.0f, 0.993938f, 0.109947f, 0.3f, -0.0f, -0.109947f, 0.993937f, -0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, 0.0f, -0.0f, 0.0f, -0.999836f, 0.018122f, 0.272858f, 0.0f, -0.018122f, -0.999244f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {-1.0f, 0.0f, -0.0f, 0.0f, 0.0f, -0.0f, -1.0f, 0.3f, -0.0f, -1.0f, 0.0f, -0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {-0.0f, -0.952114f, -0.305743f, 0.0f, 0.0f, -0.305743f, 0.952114f, 0.4f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {-0.0f, -0.0f, 1.0f, -0.0f, -0.952114f, 0.305743f, -0.0f, 0.39386f, -0.305743f, -0.952114f, -0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, 0.0f, -0.0f, -0.0f, 0.993937f, 0.109947f, 0.3f, -0.0f, -0.109947f, 0.993937f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, 0.0f, -0.0f, 0.0f, -0.999836f, 0.018122f, 0.272858f, 0.0f, -0.018122f, -0.999247f, -0.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {-1.0f, 0.0f, -0.0f, -0.0f, 0.0f, -0.0f, -1.0f, 0.3f, -0.0f, -1.0f, -0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f}
    };

    /** Reference joint locals after JsonAssetLoader processing (load + transpose + root correction). */
    private static final OpenMatrix4f[] REF_LOCALS = buildRefLocals();

    static JsonObject toJson(int loop, float length, Map<Integer, OpenMatrix4f[]> localFrames) {
        JsonObject root = new JsonObject();
        JsonObject constructor = new JsonObject();
        boolean isRepeat = loop == ScriptAnim.LOOP_REPEAT;
        constructor.addProperty("invocation_command",
                "(0.15F#F," + isRepeat + "#Z,ysm_epicfight_compat:public/PLACEHOLDER#java.lang.String,"
                        + "epicfight:entity/biped#yesman.epicfight.api.model.Armature,0#I)"
                        + "#com.ysmef.compat.animation.YsmWheelAnimation");
        root.add("constructor", constructor);

        JsonArray animation = new JsonArray();
        // Root must come first: the JSON loader applies the Blender -> Minecraft
        // coordinate correction to the first entry only.
        for (int joint : jointOrder()) {
            OpenMatrix4f[] frames = localFrames.get(joint);
            if (frames == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", JOINT_NAMES[joint]);
            JsonArray times = new JsonArray();
            JsonArray transforms = new JsonArray();
            for (int frame = 0; frame < frames.length; frame++) {
                float time = frame == frames.length - 1 ? length : frame * YsmExtraFrameWriter.SAMPLE_STEP;
                times.add((double) Math.round(time * 1_000_000.0) / 1_000_000.0);
                JsonArray raw = new JsonArray();
                float[] encoded = encode(frames[frame], REF_LOCALS[joint], joint == JOINT_ROOT);
                for (float value : encoded) {
                    raw.add((double) Math.round(value * 1_000_000.0) / 1_000_000.0);
                }
                transforms.add(raw);
            }
            entry.add("time", times);
            entry.add("transform", transforms);
            animation.add(entry);
        }
        root.add("animation", animation);
        return root;
    }

    private static int[] jointOrder() {
        int[] order = new int[JOINT_COUNT];
        for (int i = 0; i < JOINT_COUNT; i++) {
            order[i] = i;
        }
        // Root already id 0; children are in increasing id order.
        return order;
    }

    /**
     * Encode a desired local-animation matrix back into the raw matrix layout of an
     * Epic Fight animation JSON (inverse of JsonAssetLoader#getTransformSheet):
     * loader: raw -> transpose -> optional root B2M correction -> * inv(refLocal).
     */
    private static float[] encode(OpenMatrix4f desired, OpenMatrix4f refLocal, boolean root) {
        OpenMatrix4f m = OpenMatrix4f.mul(refLocal, desired, null);
        if (root) {
            m = OpenMatrix4f.mul(OpenMatrix4f.invert(JsonAssetLoader.BLENDER_TO_MINECRAFT_COORD, null), m, null);
        }
        m.transpose();
        return toArray(m);
    }

    static float[] toArray(OpenMatrix4f m) {
        return new float[]{
                m.m00, m.m01, m.m02, m.m03,
                m.m10, m.m11, m.m12, m.m13,
                m.m20, m.m21, m.m22, m.m23,
                m.m30, m.m31, m.m32, m.m33
        };
    }

    private static OpenMatrix4f[] buildRefLocals() {
        OpenMatrix4f[] locals = new OpenMatrix4f[JOINT_COUNT];
        for (int joint = 0; joint < JOINT_COUNT; joint++) {
            OpenMatrix4f local = OpenMatrix4f.load(null, REF_RAW[joint]);
            local.transpose();
            if (joint == JOINT_ROOT) {
                local.mulFront(JsonAssetLoader.BLENDER_TO_MINECRAFT_COORD);
            }
            locals[joint] = local;
        }
        return locals;
    }

}
