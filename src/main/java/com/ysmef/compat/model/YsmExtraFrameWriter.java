package com.ysmef.compat.model;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.ysm.YsmModelPackage;
import com.ysmef.compat.ysm.script.Molang;
import com.ysmef.compat.ysm.script.ScriptAnim;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import yesman.epicfight.api.asset.JsonAssetLoader;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Samples YSM wheel-selectable extra animations (the "extra" GEO animation file)
 * at 60 FPS and converts every sample into the frame-animation matrix format used
 * by Epic Fight / Avalon animmodels/animations JSONs.
 *
 * The conversion mirrors YsmBindArmature: the sampled animation is expressed as
 * local animation deltas against the model's own YSM-pivot armature, then encoded
 * relative to Epic Fight's reference biped joint locals. The resulting clip is
 * therefore body-proportion independent and safe to share as a public template
 * between models with the same action (the per-model bind armature supplies the
 * model-specific pivots at draw time).
 */
public final class YsmExtraFrameWriter {

    /** Frames per second of the generated animation JSONs (Avalon convention). */
    public static final float SAMPLE_STEP = 1.0f / 60.0f;

    /** Longest wheel animation that is converted (guards corrupt/infinite lengths). */
    private static final float MAX_ANIMATION_LENGTH = 120.0f;

    private static final int JOINT_COUNT = 20;
    private static final int JOINT_ROOT = 0;
    private static final int JOINT_THIGH_R = 1;
    private static final int JOINT_LEG_R = 2;
    private static final int JOINT_KNEE_R = 3;
    private static final int JOINT_THIGH_L = 4;
    private static final int JOINT_LEG_L = 5;
    private static final int JOINT_KNEE_L = 6;
    private static final int JOINT_TORSO = 7;
    private static final int JOINT_CHEST = 8;
    private static final int JOINT_HEAD = 9;
    private static final int JOINT_SHOULDER_R = 10;
    private static final int JOINT_ARM_R = 11;
    private static final int JOINT_HAND_R = 12;
    private static final int JOINT_TOOL_R = 13;
    private static final int JOINT_ELBOW_R = 14;
    private static final int JOINT_SHOULDER_L = 15;
    private static final int JOINT_ARM_L = 16;
    private static final int JOINT_HAND_L = 17;
    private static final int JOINT_TOOL_L = 18;
    private static final int JOINT_ELBOW_L = 19;

    private static final String[] JOINT_NAMES = {
            "Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L", "Knee_L",
            "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R", "Tool_R",
            "Elbow_R", "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"
    };

    private static final int[] JOINT_PARENTS = {
            -1, 0, 1, 1, 0, 4, 4, 0, 7, 8, 8, 10, 11, 12, 11, 8, 15, 16, 17, 16
    };

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

    /** The converted model bone table, in DFS order (parents before children). */
    private static final class SampleBone {
        YSMGeoModel.Bone bone;
        int parent = -1;
        Matrix4f bind = new Matrix4f();
        final Matrix4f animWorld = new Matrix4f();
        /** Last evaluated source values: rot degrees (x,y,z), pos pixels, scale. */
        final float[] raw = new float[9];
        int joint;
        boolean direct;
    }

    /** Result of one sampled extra animation. */
    public static final class Clip {
        public final String animationName;
        public final int loop;
        public final float length;
        public final int frameCount;
        /** Joint id -> descriptor rows (frame-major, 7 floats: quaternion + translation). */
        public final Map<Integer, float[]> descriptor;
        /**
         * Joint id -> model-independent source descriptor rows (frame-major, 9
         * floats: Bedrock rotation degrees, position pixels and scale). This is
         * the similarity key used for public-template deduplication.
         */
        public final Map<Integer, float[]> sourceDescriptor;
        /** Joint id -> per-frame local animation matrices (OpenMatrix4f representation). */
        public final Map<Integer, OpenMatrix4f[]> localFrames;
        public final JsonObject json;

        Clip(String animationName, int loop, float length, int frameCount,
             Map<Integer, float[]> descriptor, Map<Integer, float[]> sourceDescriptor,
             Map<Integer, OpenMatrix4f[]> localFrames, JsonObject json) {
            this.animationName = animationName;
            this.loop = loop;
            this.length = length;
            this.frameCount = frameCount;
            this.descriptor = descriptor;
            this.sourceDescriptor = sourceDescriptor;
            this.localFrames = localFrames;
            this.json = json;
        }
    }

    private YsmExtraFrameWriter() {}

    /**
     * Convert one wheel animation of the model into a sampled clip.
     *
     * @return the clip, or null when the animation is missing or effectively empty
     */
    public static Clip convert(YsmModelPackage pkg, String animationName) {
        ScriptAnim anim = pkg.wheelAnim(animationName);
        if (anim == null) {
            return null;
        }
        SampleBone[] bones = collectBones(pkg.geometry);
        if (bones.length == 0) {
            return null;
        }
        for (int i = 0; i < bones.length; i++) {
            bindWorldOf(bones, i);
        }
        ArmatureTables tables = buildArmatureTables(pkg, bones);
        RepresentativeSelection selection = selectRepresentatives(bones, anim);
        if (!selection.hasAnimatedJoint()) {
            return null;
        }

        float length = animationLength(anim);
        if (!Float.isFinite(length) || length < SAMPLE_STEP * 0.5f) {
            return null;
        }
        int frameCount = Math.min(Math.max(2, Math.round(length * 60.0f) + 1), 9000);
        final int sampleCount = frameCount;

        SampleEnv env = new SampleEnv();
        Map<Integer, OpenMatrix4f[]> localFrames = new LinkedHashMap<>();
        Map<Integer, float[]> sourceFrames = new LinkedHashMap<>();
        for (int joint : selection.joints()) {
            localFrames.put(joint, new OpenMatrix4f[sampleCount]);
            if (selection.representative(joint) >= 0) {
                sourceFrames.put(joint, new float[sampleCount * 9]);
            }
        }
        // Root is always written so the JSON's first joint gets the loader's root correction.
        localFrames.computeIfAbsent(JOINT_ROOT, k -> new OpenMatrix4f[sampleCount]);

        int[] fired = new int[anim.timelines.size()];
        OpenMatrix4f[] pose = new OpenMatrix4f[JOINT_COUNT];
        for (int frame = 0; frame < frameCount; frame++) {
            float t = frame * SAMPLE_STEP;
            if (frame == frameCount - 1) {
                t = length;
            }
            env.animTime = t;
            fireTimelines(anim, t, fired, env);

            for (SampleBone bone : bones) {
                computeAnimatedBoneWorld(bones, anim, bone, env);
            }
            for (int joint = 0; joint < JOINT_COUNT; joint++) {
                int rep = selection.representative(joint);
                float[] source = sourceFrames.get(joint);
                if (rep >= 0 && source != null) {
                    System.arraycopy(bones[rep].raw, 0, source, frame * 9, 9);
                }
            }

            Arrays.fill(pose, null);
            for (int joint = 0; joint < JOINT_COUNT; joint++) {
                OpenMatrix4f parent = joint == JOINT_ROOT || JOINT_PARENTS[joint] < 0
                        ? new OpenMatrix4f()
                        : pose[JOINT_PARENTS[joint]];
                OpenMatrix4f x = OpenMatrix4f.mul(parent, tables.ysmLocals[joint], null);
                OpenMatrix4f local = null;
                if (localFrames.containsKey(joint)) {
                    int boneIdx = selection.representative(joint);
                    if (boneIdx >= 0) {
                        local = jointLocalFor(bones[boneIdx], tables.ysmWorlds[joint], x);
                    }
                }
                if (local == null || !isFinite(local)) {
                    local = new OpenMatrix4f();
                }
                localFrames.get(joint)[frame] = local;
                pose[joint] = OpenMatrix4f.mul(x, local, null);
            }
        }

        Map<Integer, float[]> descriptors = new LinkedHashMap<>();
        List<Integer> removeJoints = new ArrayList<>();
        for (Map.Entry<Integer, OpenMatrix4f[]> entry : localFrames.entrySet()) {
            int joint = entry.getKey();
            OpenMatrix4f[] frames = entry.getValue();
            boolean animated = false;
            if (joint != JOINT_ROOT) {
                for (OpenMatrix4f frame : frames) {
                    if (!isIdentity(frame)) {
                        animated = true;
                        break;
                    }
                }
            }
            if (!animated && joint != JOINT_ROOT) {
                removeJoints.add(joint);
                continue;
            }
            descriptors.put(joint, descriptorOf(frames));
        }
        for (Integer joint : removeJoints) {
            localFrames.remove(joint);
        }

        Clip clip = new Clip(animationName, anim.loop, length, frameCount, descriptors, sourceFrames, localFrames,
                toJson(anim.loop, length, localFrames));
        return clip;
    }

    /** Whether an OpenMatrix4f is (within tolerance) the identity local animation. */
    private static boolean isIdentity(OpenMatrix4f m) {
        float[] v = {
                m.m00 - 1.0f, m.m01, m.m02, m.m03,
                m.m10, m.m11 - 1.0f, m.m12, m.m13,
                m.m20, m.m21, m.m22 - 1.0f, m.m23,
                m.m30, m.m31, m.m32, m.m33 - 1.0f
        };
        for (float f : v) {
            if (Math.abs(f) > 1e-4f) {
                return false;
            }
        }
        return true;
    }

    private static float[] descriptorOf(OpenMatrix4f[] frames) {
        float[] out = new float[frames.length * 7];
        for (int i = 0; i < frames.length; i++) {
            OpenMatrix4f m = frames[i];
            yesman.epicfight.api.utils.math.Vec3f t = m.toTranslationVector();
            Quaternionf q = m.toQuaternion();
            int base = i * 7;
            out[base] = round3(q.x);
            out[base + 1] = round3(q.y);
            out[base + 2] = round3(q.z);
            out[base + 3] = round3(q.w);
            out[base + 4] = round3(t.x);
            out[base + 5] = round3(t.y);
            out[base + 6] = round3(t.z);
        }
        return out;
    }

    private static float round3(float value) {
        if (!Float.isFinite(value)) {
            return 0.0f;
        }
        return Math.round(value * 1000.0f) / 1000.0f;
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static void sanitize(float[] values) {
        for (int i = 0; i < values.length; i++) {
            values[i] = finite(values[i]);
        }
    }

    private static boolean isFinite(OpenMatrix4f m) {
        float[] values = toArray(m);
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject toJson(int loop, float length, Map<Integer, OpenMatrix4f[]> localFrames) {
        JsonObject root = new JsonObject();
        JsonObject constructor = new JsonObject();
        boolean isRepeat = loop == ScriptAnim.LOOP_REPEAT;
        constructor.addProperty("invocation_command",
                "(0.15F#F," + isRepeat + "#Z,ysm_epicfight_compat:public/PLACEHOLDER#java.lang.String,"
                        + "epicfight:biped#yesman.epicfight.api.model.Armature,0#I)"
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
                float time = frame == frames.length - 1 ? length : frame * SAMPLE_STEP;
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

    private static float[] toArray(OpenMatrix4f m) {
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

    // ------------------------------------------------------------------
    // YSM bone table
    // ------------------------------------------------------------------

    private static SampleBone[] collectBones(YSMGeoModel geoModel) {
        if (geoModel == null) {
            return new SampleBone[0];
        }
        List<SampleBone> bones = new ArrayList<>();
        Map<String, Integer> byName = new HashMap<>();
        for (YSMGeoModel.Bone root : geoModel.topLevelBones) {
            collectBone(root, -1, bones, byName);
        }
        return bones.toArray(new SampleBone[0]);
    }

    private static void collectBone(YSMGeoModel.Bone bone, int parent,
                                    List<SampleBone> out, Map<String, Integer> byName) {
        SampleBone sample = new SampleBone();
        sample.bone = bone;
        sample.parent = parent;
        sample.joint = YSMJointMapper.resolveJointId(bone);
        sample.direct = YSMJointMapper.isDirectlyMapped(bone);
        int index = out.size();
        out.add(sample);
        byName.put(bone.name, index);
        for (YSMGeoModel.Bone child : bone.children) {
            collectBone(child, index, out, byName);
        }
    }

    private static Matrix4f bindWorldOf(SampleBone[] bones, int index) {
        SampleBone bone = bones[index];
        Matrix4f bind = bone.bind;
        if (bone.parent >= 0) {
            bindWorldOf(bones, bone.parent);
            bind.set(bones[bone.parent].bind);
        } else {
            bind.identity();
        }
        applyLocal(bind, bone.bone.pivotX, bone.bone.pivotY, bone.bone.pivotZ,
                bone.bone.rotX, bone.bone.rotY, bone.bone.rotZ, 1.0f, 1.0f, 1.0f);
        return bind;
    }

    private static void applyLocal(Matrix4f m, float px, float py, float pz,
                                   float rx, float ry, float rz, float sx, float sy, float sz) {
        m.translate(px, py, pz);
        m.rotateZ(rz);
        m.rotateY(ry);
        m.rotateX(rx);
        m.scale(sx, sy, sz);
        m.translate(-px, -py, -pz);
    }

    private static void computeAnimatedBoneWorld(SampleBone[] bones, ScriptAnim anim, SampleBone bone, SampleEnv env) {
        float rx = bone.bone.rotX;
        float ry = bone.bone.rotY;
        float rz = bone.bone.rotZ;
        float px = bone.bone.pivotX;
        float py = bone.bone.pivotY;
        float pz = bone.bone.pivotZ;
        float sx = 1.0f;
        float sy = 1.0f;
        float sz = 1.0f;

        ScriptAnim.BoneChannels channels = anim.bones.get(bone.bone.name);
        float[] raw = bone.raw;
        raw[0] = raw[1] = raw[2] = 0.0f;
        raw[3] = raw[4] = raw[5] = 0.0f;
        raw[6] = raw[7] = raw[8] = 1.0f;
        if (channels != null) {
            if (channels.rotation != null) {
                float[] rot = new float[3];
                evalChannel(channels.rotation, env.animTime, env, rot);
                sanitize(rot);
                raw[0] = rot[0];
                raw[1] = rot[1];
                raw[2] = rot[2];
                rx = (float) Math.toRadians(-rot[0]);
                ry = (float) Math.toRadians(-rot[1]);
                rz = (float) Math.toRadians(rot[2]);
            }
            if (channels.position != null) {
                float[] pos = new float[3];
                evalChannel(channels.position, env.animTime, env, pos);
                sanitize(pos);
                raw[3] = pos[0];
                raw[4] = pos[1];
                raw[5] = pos[2];
                px += -pos[0] / 16.0f;
                py += pos[1] / 16.0f;
                pz += pos[2] / 16.0f;
            }
            if (channels.scale != null) {
                float[] scale = new float[3];
                evalChannel(channels.scale, env.animTime, env, scale);
                sanitize(scale);
                raw[6] = scale[0];
                raw[7] = scale[1];
                raw[8] = scale[2];
                sx = scale[0];
                sy = scale[1];
                sz = scale[2];
            }
        }

        Matrix4f world = bone.animWorld;
        if (bone.parent >= 0) {
            world.set(bones[bone.parent].animWorld);
        } else {
            world.identity();
        }
        applyLocal(world, px, py, pz, rx, ry, rz, sx, sy, sz);
    }

    private static void evalChannel(ScriptAnim.Channel channel, float t, Molang.Env env, float[] out) {
        List<ScriptAnim.Key> keys = channel.keys;
        int n = keys.size();
        if (n == 0) {
            out[0] = out[1] = out[2] = 0.0f;
            return;
        }
        int right = 1;
        while (right < n && keys.get(right).time <= t) {
            right++;
        }
        if (right >= n) {
            evalValue(keys.get(n - 1).post, env, out);
            return;
        }
        if (right == 0) {
            evalValue(keys.get(0).post, env, out);
            return;
        }
        int left = right - 1;
        ScriptAnim.Key leftKey = keys.get(left);
        ScriptAnim.Key rightKey = keys.get(right);
        if (rightKey.lerp == ScriptAnim.Key.LERP_STEP || rightKey.time <= leftKey.time) {
            evalValue(leftKey.post, env, out);
            return;
        }
        float alpha = Math.max(0.0f, Math.min(1.0f, (t - leftKey.time) / (rightKey.time - leftKey.time)));
        float[] l = new float[3];
        float[] r = new float[3];
        evalValue(leftKey.post, env, l);
        evalValue(rightKey.pre != null ? rightKey.pre : rightKey.post, env, r);
        if (rightKey.lerp == ScriptAnim.Key.LERP_CATMULLROM && n >= 2) {
            float[] p0 = new float[3];
            float[] p3 = new float[3];
            evalValue(keys.get(Math.max(0, left - 1)).post, env, p0);
            evalValue(keys.get(Math.min(n - 1, right + 1)).post, env, p3);
            for (int i = 0; i < 3; i++) {
                out[i] = catmullRom(p0[i], l[i], r[i], p3[i], alpha);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                out[i] = l[i] + (r[i] - l[i]) * alpha;
            }
        }
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2.0f * p1) + (-p0 + p2) * t + (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2
                + (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3);
    }

    private static void evalValue(ScriptAnim.Value value, Molang.Env env, float[] out) {
        for (int axis = 0; axis < 3; axis++) {
            if (value.expr[axis] != null) {
                out[axis] = finite((float) Molang.compile(value.expr[axis]).eval(env));
            } else {
                out[axis] = finite((float) value.num[axis]);
            }
        }
    }

    private static void fireTimelines(ScriptAnim anim, float t, int[] fired, Molang.Env env) {
        for (int i = 0; i < anim.timelines.size(); i++) {
            if (fired[i] == 0 && anim.timelines.get(i).time <= t + 1e-4f) {
                fired[i] = 1;
                for (String code : anim.timelines.get(i).code) {
                    Molang.compile(code).eval(env);
                }
            }
        }
    }

    private static float animationLength(ScriptAnim anim) {
        if (Float.isFinite(anim.length) && anim.length > 1e-4f) {
            return Math.min(anim.length, MAX_ANIMATION_LENGTH);
        }
        // Some packages carry a corrupt/infinite animation_length. Fall back to
        // the maximum keyframe/timeline time instead of producing an endless clip.
        float max = 0.0f;
        for (ScriptAnim.BoneChannels channels : anim.bones.values()) {
            max = Math.max(max, channelMaxTime(channels.rotation));
            max = Math.max(max, channelMaxTime(channels.position));
            max = Math.max(max, channelMaxTime(channels.scale));
        }
        for (ScriptAnim.Timeline timeline : anim.timelines) {
            max = Math.max(max, timeline.time);
        }
        return Float.isFinite(max) ? Math.min(max, MAX_ANIMATION_LENGTH) : 0.0f;
    }

    private static float channelMaxTime(ScriptAnim.Channel channel) {
        if (channel == null || channel.keys.isEmpty()) {
            return 0.0f;
        }
        return channel.keys.get(channel.keys.size() - 1).time;
    }

    // ------------------------------------------------------------------
    // Per-model bind armature (mirrors YsmBindArmature)
    // ------------------------------------------------------------------

    private static final class ArmatureTables {
        final OpenMatrix4f[] ysmLocals = new OpenMatrix4f[JOINT_COUNT];
        final OpenMatrix4f[] ysmWorlds = new OpenMatrix4f[JOINT_COUNT];
        final Matrix4f[] ysmWorldsJoml = new Matrix4f[JOINT_COUNT];
    }

    private static ArmatureTables buildArmatureTables(YsmModelPackage pkg, SampleBone[] bones) {
        ArmatureTables tables = new ArmatureTables();
        Map<Integer, List<Vector3f>> byJoint = collectGeometryByJoint(bones);
        Vector3f thighR = topOf(byJoint.get(JOINT_THIGH_R));
        Vector3f thighL = topOf(byJoint.get(JOINT_THIGH_L));
        Vector3f hip = midpoint(thighR, thighL);
        Vector3f neck = topOf(byJoint.get(JOINT_CHEST));
        Vector3f chest = midpoint(hip, neck);
        Vector3f kneeR = topOf(byJoint.get(JOINT_LEG_R));
        Vector3f kneeL = topOf(byJoint.get(JOINT_LEG_L));
        Vector3f shoulderR = topOf(byJoint.get(JOINT_ARM_R));
        Vector3f shoulderL = topOf(byJoint.get(JOINT_ARM_L));
        Vector3f elbowR = topOf(byJoint.get(JOINT_HAND_R));
        Vector3f elbowL = topOf(byJoint.get(JOINT_HAND_L));
        Vector3f wristR = handPivot(byJoint.get(JOINT_HAND_R), bones);
        Vector3f wristL = handPivot(byJoint.get(JOINT_HAND_L), bones);

        Map<Integer, OpenMatrix4f> pivots = new HashMap<>();
        putPivot(pivots, JOINT_ROOT, hip);
        putPivot(pivots, JOINT_TORSO, hip);
        putPivot(pivots, JOINT_CHEST, chest);
        putPivot(pivots, JOINT_HEAD, neck);
        putPivot(pivots, JOINT_THIGH_R, thighR);
        putPivot(pivots, JOINT_THIGH_L, thighL);
        putPivot(pivots, JOINT_LEG_R, kneeR);
        putPivot(pivots, JOINT_LEG_L, kneeL);
        putPivot(pivots, JOINT_KNEE_R, kneeR);
        putPivot(pivots, JOINT_KNEE_L, kneeL);
        putPivot(pivots, JOINT_ARM_R, shoulderR);
        putPivot(pivots, JOINT_ARM_L, shoulderL);
        putPivot(pivots, JOINT_HAND_R, elbowR);
        putPivot(pivots, JOINT_HAND_L, elbowL);
        putPivot(pivots, JOINT_SHOULDER_R, shoulderR);
        putPivot(pivots, JOINT_SHOULDER_L, shoulderL);
        putPivot(pivots, JOINT_ELBOW_R, elbowR);
        putPivot(pivots, JOINT_ELBOW_L, elbowL);
        putPivot(pivots, JOINT_TOOL_R, wristR != null ? wristR : elbowR);
        putPivot(pivots, JOINT_TOOL_L, wristL != null ? wristL : elbowL);

        for (int joint = 0; joint < JOINT_COUNT; joint++) {
            buildJointTables(joint, pivots, tables);
        }
        return tables;
    }

    private static void buildJointTables(int joint, Map<Integer, OpenMatrix4f> pivots, ArmatureTables tables) {
        int parent = JOINT_PARENTS[joint];
        OpenMatrix4f parentWorld = parent >= 0 ? tables.ysmWorlds[parent] : new OpenMatrix4f();
        OpenMatrix4f local = new OpenMatrix4f(REF_LOCALS[joint]);
        OpenMatrix4f pivot = pivots.get(joint);
        if (pivot != null) {
            if (parent < 0) {
                local.m30 = pivot.m30;
                local.m31 = pivot.m31;
                local.m32 = pivot.m32;
            } else {
                OpenMatrix4f parentInv = OpenMatrix4f.invert(parentWorld, null);
                OpenMatrix4f offset = OpenMatrix4f.mul(parentInv, pivot, null);
                local.m30 = offset.m30;
                local.m31 = offset.m31;
                local.m32 = offset.m32;
            }
        }
        tables.ysmLocals[joint] = local;
        tables.ysmWorlds[joint] = OpenMatrix4f.mul(parentWorld, local, null);
        tables.ysmWorldsJoml[joint] = toJoml(tables.ysmWorlds[joint]);
    }

    private static Map<Integer, List<Vector3f>> collectGeometryByJoint(SampleBone[] bones) {
        Map<Integer, List<Vector3f>> byJoint = new HashMap<>();
        for (SampleBone sample : bones) {
            if (!sample.direct) {
                continue;
            }
            String name = sample.bone.name;
            if (!name.isEmpty() && Character.isDigit(name.charAt(name.length() - 1))) {
                continue;
            }
            Matrix4f bind = bindWorldOf(bones, indexOf(bones, sample));
            List<Vector3f> list = byJoint.computeIfAbsent(sample.joint, k -> new ArrayList<>());
            for (YSMGeoModel.Quad quad : sample.bone.quads) {
                for (Vector3f pos : quad.positions) {
                    list.add(new Vector3f(pos).mulPosition(bind));
                }
            }
        }
        return byJoint;
    }

    private static int indexOf(SampleBone[] bones, SampleBone sample) {
        for (int i = 0; i < bones.length; i++) {
            if (bones[i] == sample) {
                return i;
            }
        }
        return -1;
    }

    private static Vector3f handPivot(List<Vector3f> vertices, SampleBone[] bones) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        for (SampleBone sample : bones) {
            if (!HAND_BONE_NAMES.contains(normalize(sample.bone.name))) {
                continue;
            }
            if (!sample.direct || (sample.joint != JOINT_HAND_R && sample.joint != JOINT_HAND_L)) {
                continue;
            }
            Matrix4f bind = bindWorldOf(bones, indexOf(bones, sample));
            List<Vector3f> handVerts = new ArrayList<>();
            for (YSMGeoModel.Quad quad : sample.bone.quads) {
                for (Vector3f pos : quad.positions) {
                    handVerts.add(new Vector3f(pos).mulPosition(bind));
                }
            }
            return topOf(handVerts);
        }
        return null;
    }

    private static final java.util.Set<String> HAND_BONE_NAMES = new java.util.HashSet<>(List.of(
            "righthand", "handright", "lefthand", "handleft"));

    private static Vector3f topOf(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        float maxY = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            maxY = Math.max(maxY, v.y);
        }
        Vector3f acc = new Vector3f();
        int n = 0;
        for (Vector3f v : vertices) {
            if (v.y >= maxY - 0.05f) {
                acc.add(v);
                n++;
            }
        }
        if (n == 0) {
            return new Vector3f(vertices.get(0));
        }
        return acc.div(n);
    }

    private static Vector3f midpoint(Vector3f a, Vector3f b) {
        if (a == null) {
            return b == null ? null : new Vector3f(b);
        }
        if (b == null) {
            return new Vector3f(a);
        }
        return new Vector3f((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f, (a.z + b.z) * 0.5f);
    }

    private static void putPivot(Map<Integer, OpenMatrix4f> pivots, int joint, Vector3f pos) {
        if (pos == null) {
            return;
        }
        OpenMatrix4f matrix = new OpenMatrix4f();
        matrix.m30 = pos.x;
        matrix.m31 = pos.y;
        matrix.m32 = pos.z;
        pivots.put(joint, matrix);
    }

    private static String normalize(String boneName) {
        String normalized = boneName.toLowerCase().replace("_", "").replace(" ", "");
        int end = normalized.length();
        while (end > 0 && Character.isDigit(normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end);
    }

    // ------------------------------------------------------------------
    // Representative selection + desired joint-local computation
    // ------------------------------------------------------------------

    private static final class RepresentativeSelection {
        private final int[] representatives = new int[JOINT_COUNT];
        private final boolean[] animated = new boolean[JOINT_COUNT];

        RepresentativeSelection() {
            Arrays.fill(representatives, -1);
        }

        int representative(int joint) {
            return representatives[joint];
        }

        boolean hasAnimatedJoint() {
            for (boolean b : animated) {
                if (b) {
                    return true;
                }
            }
            return false;
        }

        int[] joints() {
            int[] joints = new int[JOINT_COUNT];
            for (int i = 0; i < JOINT_COUNT; i++) {
                joints[i] = i;
            }
            return joints;
        }
    }

    private static RepresentativeSelection selectRepresentatives(SampleBone[] bones, ScriptAnim anim) {
        RepresentativeSelection selection = new RepresentativeSelection();
        for (int joint = 0; joint < JOINT_COUNT; joint++) {
            int best = -1;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < bones.length; i++) {
                if (bones[i].joint != joint) {
                    continue;
                }
                int score = 0;
                if (anim.bones.containsKey(bones[i].bone.name)) {
                    score += 16;
                }
                if (bones[i].direct) {
                    score += 8;
                }
                if (!bones[i].bone.quads.isEmpty()) {
                    score += 4;
                }
                String name = bones[i].bone.name;
                if (name.isEmpty() || !Character.isDigit(name.charAt(name.length() - 1))) {
                    score += 2;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best >= 0) {
                selection.representatives[joint] = best;
                selection.animated[joint] = anim.bones.containsKey(bones[best].bone.name);
            }
        }
        // Root can use the model's top-level root bone even when it is not directly
        // name-mapped; give it a representative whenever one exists.
        if (selection.representatives[JOINT_ROOT] < 0) {
            for (int i = 0; i < bones.length; i++) {
                if (bones[i].joint == JOINT_ROOT && bones[i].parent < 0) {
                    selection.representatives[JOINT_ROOT] = i;
                    selection.animated[JOINT_ROOT] = anim.bones.containsKey(bones[i].bone.name);
                    break;
                }
            }
        }
        return selection;
    }

    private static OpenMatrix4f jointLocalFor(SampleBone bone, OpenMatrix4f ysmBindWorld, OpenMatrix4f parentLocalProduct) {
        Matrix4f bind = bone.bind;
        Matrix4f invBind = new Matrix4f(bind).invert();
        Matrix4f d = new Matrix4f(bone.animWorld).mul(invBind).mul(toJoml(ysmBindWorld));
        OpenMatrix4f desired = toOpen(d);
        OpenMatrix4f xInverse = OpenMatrix4f.invert(parentLocalProduct, null);
        return OpenMatrix4f.mul(xInverse, desired, null);
    }

    private static Matrix4f toJoml(OpenMatrix4f m) {
        Matrix4f out = new Matrix4f();
        out.m00(m.m00).m01(m.m01).m02(m.m02).m03(m.m03);
        out.m10(m.m10).m11(m.m11).m12(m.m12).m13(m.m13);
        out.m20(m.m20).m21(m.m21).m22(m.m22).m23(m.m23);
        out.m30(m.m30).m31(m.m31).m32(m.m32).m33(m.m33);
        return out;
    }

    private static OpenMatrix4f toOpen(Matrix4f m) {
        OpenMatrix4f out = new OpenMatrix4f();
        out.m00 = m.m00();
        out.m01 = m.m01();
        out.m02 = m.m02();
        out.m03 = m.m03();
        out.m10 = m.m10();
        out.m11 = m.m11();
        out.m12 = m.m12();
        out.m13 = m.m13();
        out.m20 = m.m20();
        out.m21 = m.m21();
        out.m22 = m.m22();
        out.m23 = m.m23();
        out.m30 = m.m30();
        out.m31 = m.m31();
        out.m32 = m.m32();
        out.m33 = m.m33();
        return out;
    }

    // ------------------------------------------------------------------
    // Molang sampling environment
    // ------------------------------------------------------------------

    private static final class SampleEnv implements Molang.Env {
        float animTime;
        private final Map<Integer, Double> vars = new HashMap<>();

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
            if (id == Molang.queryIdOf("query.anim_time")) {
                return animTime;
            }
            if (id == Molang.queryIdOf("query.health") || id == Molang.queryIdOf("query.max_health")) {
                return 20.0;
            }
            if (id == Molang.queryIdOf("query.is_on_ground") || id == Molang.queryIdOf("query.is_alive")) {
                return 1.0;
            }
            if (id == Molang.queryIdOf("ctrl.playing_extra_animation")) {
                return 1.0;
            }
            return 0.0;
        }

        @Override
        public double callFunction(String name, double[] args) {
            switch (name) {
                case "math.sin": return finite(Math.sin(Math.toRadians(args[0])));
                case "math.cos": return finite(Math.cos(Math.toRadians(args[0])));
                case "math.tan": return finite(Math.tan(Math.toRadians(args[0])));
                case "math.asin": return finite(Math.toDegrees(Math.asin(args[0])));
                case "math.acos": return finite(Math.toDegrees(Math.acos(args[0])));
                case "math.atan": return finite(Math.toDegrees(Math.atan(args[0])));
                case "math.atan2": return finite(Math.toDegrees(Math.atan2(args[0], args[1])));
                case "math.abs": return finite(Math.abs(args[0]));
                case "math.floor": return finite(Math.floor(args[0]));
                case "math.ceil": return finite(Math.ceil(args[0]));
                case "math.round": return finite(Math.round(args[0]));
                case "math.trunc": return finite((long) (args[0] >= 0 ? Math.floor(args[0]) : Math.ceil(args[0])));
                case "math.sqrt": return finite(args[0] < 0 ? 0 : Math.sqrt(args[0]));
                case "math.pow": return finite(Math.pow(args[0], args[1]));
                case "math.exp": return finite(Math.exp(args[0]));
                case "math.ln": return finite(args[0] <= 0 ? 0 : Math.log(args[0]));
                case "math.log": return finite(args[0] <= 0 ? 0 : Math.log(args[0]));
                case "math.lerp": return finite(args[0] + (args[1] - args[0]) * args[2]);
                case "math.min": return finite(Math.min(args[0], args[1]));
                case "math.max": return finite(Math.max(args[0], args[1]));
                case "math.clamp": return finite(Math.max(args[1], Math.min(args[2], args[0])));
                case "math.mod": return finite(args[1] == 0 ? 0 : args[0] % args[1]);
                case "math.random": return finite((args[0] + args[1]) * 0.5);
                case "math.pi": return Math.PI;
                case "math.sign": return finite(Math.signum(args[0]));
                default: return 0.0;
            }
        }

        @Override
        public double callStringFunction(String name, String[] args) {
            return 0.0;
        }
    }

    /** Atomic write helper shared with the mesh writer. */
    static void writeAtomic(Path target, String json) {
        try {
            EFMeshJsonWriter.writeFileAtomic(target, json.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to write extra animation '{}'", target, e);
        }
    }
}
