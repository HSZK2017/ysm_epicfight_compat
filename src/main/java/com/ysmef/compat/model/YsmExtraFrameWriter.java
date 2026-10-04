package com.ysmef.compat.model;

import com.google.gson.JsonObject;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.ysm.YsmModelPackage;
import com.ysmef.compat.ysm.script.ScriptAnim;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Samples YSM wheel-selectable extra animations (the "extra" GEO animation file)
 * at 60 FPS and converts every sample into the frame-animation matrix format used
 * by Epic Fight / Avalon animmodels/animations JSONs.
 *
 * The conversion mirrors YsmBindArmature: the sampled animation is expressed as
 * local animation deltas against the model's own YSM-pivot armature, then encoded
 * relative to Epic Fight's reference biped joint locals. The resulting frames
 * depend on the model's pivots; template reuse must compare the emitted matrices.
 */
public final class YsmExtraFrameWriter {

    /** Frames per second of the generated animation JSONs (Avalon convention). */
    public static final float SAMPLE_STEP = 1.0f / 60.0f;

    /** Longest wheel animation that is converted (guards corrupt/infinite lengths). */
    private static final Set<String> FRAME_PIVOT_LOG = ConcurrentHashMap.newKeySet();

    private static String fmtPivot(OpenMatrix4f m) {
        if (m == null) {
            return "null";
        }
        return String.format("(%.3f,%.3f,%.3f)", m.m30, m.m31, m.m32);
    }

    // Joint ids/names/parents alias the shared JointTable (single source of
    // truth - see JointTable for the biped layout).
    private static final int JOINT_COUNT = JointTable.COUNT;
    private static final int JOINT_ROOT = JointTable.ROOT;
    private static final int JOINT_THIGH_R = JointTable.THIGH_R;
    private static final int JOINT_LEG_R = JointTable.LEG_R;
    private static final int JOINT_KNEE_R = JointTable.KNEE_R;
    private static final int JOINT_THIGH_L = JointTable.THIGH_L;
    private static final int JOINT_LEG_L = JointTable.LEG_L;
    private static final int JOINT_KNEE_L = JointTable.KNEE_L;
    private static final int JOINT_TORSO = JointTable.TORSO;
    private static final int JOINT_CHEST = JointTable.CHEST;
    private static final int JOINT_HEAD = JointTable.HEAD;
    private static final int JOINT_SHOULDER_R = JointTable.SHOULDER_R;
    private static final int JOINT_ARM_R = JointTable.ARM_R;
    private static final int JOINT_HAND_R = JointTable.HAND_R;
    private static final int JOINT_TOOL_R = JointTable.TOOL_R;
    private static final int JOINT_ELBOW_R = JointTable.ELBOW_R;
    private static final int JOINT_SHOULDER_L = JointTable.SHOULDER_L;
    private static final int JOINT_ARM_L = JointTable.ARM_L;
    private static final int JOINT_HAND_L = JointTable.HAND_L;
    private static final int JOINT_TOOL_L = JointTable.TOOL_L;
    private static final int JOINT_ELBOW_L = JointTable.ELBOW_L;

    private static final int[] JOINT_PARENTS = JointTable.PARENTS;

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
        /**
         * Joint id -> source descriptor rows (frame-major, 9 floats: Bedrock
         * rotation degrees, position pixels and scale). Kept for persisted
         * descriptor compatibility; this is not the template identity.
         */
        public final Map<Integer, float[]> sourceDescriptor;
        /** Joint id -> per-frame local animation matrices (OpenMatrix4f representation). */
        public final Map<Integer, OpenMatrix4f[]> localFrames;
        public final JsonObject json;

        Clip(String animationName, int loop, float length, int frameCount,
             Map<Integer, float[]> sourceDescriptor,
             Map<Integer, OpenMatrix4f[]> localFrames, JsonObject json) {
            this.animationName = animationName;
            this.loop = loop;
            this.length = length;
            this.frameCount = frameCount;
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
            bindWorldOf(bones, i, 0);
        }
        ArmatureTables tables = buildArmatureTables(pkg, bones);
        if (FRAME_PIVOT_LOG.add(pkg.modelId)) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [framepivots] model='{}' root={},torso={},chest={},head={},shoulderR={},shoulderL={}",
                    pkg.modelId,
                    fmtPivot(tables.ysmWorlds[JOINT_ROOT]),
                    fmtPivot(tables.ysmWorlds[JOINT_TORSO]),
                    fmtPivot(tables.ysmWorlds[JOINT_CHEST]),
                    fmtPivot(tables.ysmWorlds[JOINT_HEAD]),
                    fmtPivot(tables.ysmWorlds[JOINT_SHOULDER_R]),
                    fmtPivot(tables.ysmWorlds[JOINT_SHOULDER_L]));
        }
        RepresentativeSelection selection = selectRepresentatives(bones, anim);
        if (!selection.hasAnimatedJoint()) {
            return null;
        }

        float length = YsmWheelSampler.animationLength(anim);
        if (!Float.isFinite(length) || length < SAMPLE_STEP * 0.5f) {
            return null;
        }
        int frameCount = Math.min(Math.max(2, Math.round(length * 60.0f) + 1), 9000);
        final int sampleCount = frameCount;

        YsmWheelSampler.Env env = new YsmWheelSampler.Env();
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
            YsmWheelSampler.fireTimelines(anim, t, fired, env);

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
                        local = jointLocalFor(bones[boneIdx], tables.ysmWorlds[joint], x,
                                pkg.widthScale, pkg.heightScale);
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
            }
        }
        for (Integer joint : removeJoints) {
            localFrames.remove(joint);
        }

        Clip clip = new Clip(animationName, anim.loop, length, frameCount, sourceFrames, localFrames,
                YsmWheelFrameEncoder.toJson(anim.loop, length, localFrames));
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

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0f;
    }

    private static void sanitize(float[] values) {
        for (int i = 0; i < values.length; i++) {
            values[i] = finite(values[i]);
        }
    }

    private static boolean isFinite(OpenMatrix4f m) {
        float[] values = YsmWheelFrameEncoder.toArray(m);
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
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
            collectBone(root, geoModel, -1, bones, byName, 0);
        }
        return bones.toArray(new SampleBone[0]);
    }

    private static void collectBone(YSMGeoModel.Bone bone, YSMGeoModel geoModel, int parent,
                                    List<SampleBone> out, Map<String, Integer> byName, int depth) {
        if (depth > YSMGeoModel.MAX_BONE_DEPTH) {
            throw new IllegalStateException(
                    "bone hierarchy deeper than " + YSMGeoModel.MAX_BONE_DEPTH + " while sampling wheel animation");
        }
        SampleBone sample = new SampleBone();
        sample.bone = bone;
        sample.parent = parent;
        // The model-aware overload, deliberately: `joint` decides which Epic Fight joint this bone's
        // sampled motion is attributed to (`selectRepresentatives` picks one representative per
        // joint, and `jointLocalFor` expresses the sample in that joint's frame), and the mesh's
        // vertices are skinned to the same joint ({@link EFMeshJsonWriter#bakedJointId}). A cloth
        // bone the garment rule redirects therefore animates the Torso it is drawn on, instead of
        // writing a skirt panel's motion onto the Chest joint that no longer carries it.
        sample.joint = YSMJointMapper.resolveJointId(bone, geoModel);
        sample.direct = YSMJointMapper.isDirectlyMapped(bone);
        int index = out.size();
        out.add(sample);
        byName.put(bone.name, index);
        for (YSMGeoModel.Bone child : bone.children) {
            collectBone(child, geoModel, index, out, byName, depth + 1);
        }
    }

    private static Matrix4f bindWorldOf(SampleBone[] bones, int index, int depth) {
        if (depth > YSMGeoModel.MAX_BONE_DEPTH) {
            throw new IllegalStateException(
                    "bone hierarchy deeper than " + YSMGeoModel.MAX_BONE_DEPTH + " while computing bind worlds");
        }
        SampleBone bone = bones[index];
        Matrix4f bind = bone.bind;
        if (bone.parent >= 0) {
            bindWorldOf(bones, bone.parent, depth + 1);
            bind.set(bones[bone.parent].bind);
        } else {
            bind.identity();
        }
        YsmWheelBoneTransform.apply(bind, bone.bone, 0, 0, 0,
                bone.bone.rotX, bone.bone.rotY, bone.bone.rotZ, 1.0f, 1.0f, 1.0f);
        return bind;
    }

    private static Vector3f scaledPos(Vector3f source, float scaleW, float scaleH) {
        return new Vector3f(source.x * scaleW, source.y * scaleH, source.z * scaleW);
    }

    private static void computeAnimatedBoneWorld(SampleBone[] bones, ScriptAnim anim, SampleBone bone,
                                                 YsmWheelSampler.Env env) {
        float rx = bone.bone.rotX;
        float ry = bone.bone.rotY;
        float rz = bone.bone.rotZ;
        float tx = 0.0f;
        float ty = 0.0f;
        float tz = 0.0f;
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
                YsmWheelSampler.evalChannel(channels.rotation, env.animTime, env, rot);
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
                YsmWheelSampler.evalChannel(channels.position, env.animTime, env, pos);
                sanitize(pos);
                raw[3] = pos[0];
                raw[4] = pos[1];
                raw[5] = pos[2];
                tx = -pos[0] / 16.0f;
                ty = pos[1] / 16.0f;
                tz = pos[2] / 16.0f;
            }
            if (channels.scale != null) {
                float[] scale = new float[3];
                YsmWheelSampler.evalChannel(channels.scale, env.animTime, env, scale);
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
        YsmWheelBoneTransform.apply(world, bone.bone, tx, ty, tz, rx, ry, rz, sx, sy, sz);
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
        Map<Integer, List<Vector3f>> byJoint = collectGeometryByJoint(bones, pkg);
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
        Vector3f wristR = handPivot(byJoint.get(JOINT_HAND_R), bones, pkg.widthScale, pkg.heightScale);
        Vector3f wristL = handPivot(byJoint.get(JOINT_HAND_L), bones, pkg.widthScale, pkg.heightScale);

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
        OpenMatrix4f local = new OpenMatrix4f(YsmWheelFrameEncoder.referenceLocal(joint));
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

    /** The base forms this model's bone table declares (see BoneAlternateForms). */
    private static Set<String> baseForms(SampleBone[] bones) {
        String[] names = new String[bones.length];
        for (int i = 0; i < bones.length; i++) {
            names[i] = bones[i].bone.name;
        }
        return BoneAlternateForms.baseFormsPresent(names);
    }

    private static Map<Integer, List<Vector3f>> collectGeometryByJoint(SampleBone[] bones, YsmModelPackage pkg) {
        // Alternate forms are decided against this model's own names, not per name (see
        // BoneAlternateForms): a trailing digit marks a variant only when the base form is another
        // bone HERE. Testing the name alone discarded whole skeletons - 兽耳酱x1.ysm names every bone
        // "X_T4_1", "X_yiqun1", ... with no base form anywhere - after which the pivots below fell
        // back to the reference biped's, the same fault YsmBindArmature had.
        Set<String> baseForms = baseForms(bones);
        Map<Integer, List<Vector3f>> byJoint = new HashMap<>();
        for (SampleBone sample : bones) {
            if (!sample.direct) {
                continue;
            }
            String name = sample.bone.name;
            if (BoneAlternateForms.isAlternateForm(name, baseForms)) {
                continue;
            }
            Matrix4f bind = sample.bind;
            List<Vector3f> list = byJoint.computeIfAbsent(sample.joint, k -> new ArrayList<>());
            for (YSMGeoModel.Quad quad : sample.bone.quads) {
                for (Vector3f pos : quad.positions) {
                    list.add(scaledPos(new Vector3f(pos).mulPosition(bind), pkg.widthScale, pkg.heightScale));
                }
            }
        }
        return byJoint;
    }

    private static Vector3f handPivot(List<Vector3f> vertices, SampleBone[] bones, float scaleW, float scaleH) {
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
            Matrix4f bind = sample.bind;
            List<Vector3f> handVerts = new ArrayList<>();
            for (YSMGeoModel.Quad quad : sample.bone.quads) {
                for (Vector3f pos : quad.positions) {
                    handVerts.add(scaledPos(new Vector3f(pos).mulPosition(bind), scaleW, scaleH));
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

    /** Single source of truth: YSMJointMapper's name normalization (see there). */
    private static String normalize(String boneName) {
        return YSMJointMapper.normalize(boneName);
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
        Set<String> baseForms = baseForms(bones);
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
                if (!BoneAlternateForms.isAlternateForm(name, baseForms)) {
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

    private static OpenMatrix4f jointLocalFor(SampleBone bone, OpenMatrix4f ysmBindWorld,
                                              OpenMatrix4f parentLocalProduct, float scaleW, float scaleH) {
        Matrix4f bind = new Matrix4f(bone.bind);
        scaleMatrixTranslation(bind, scaleW, scaleH);
        Matrix4f invBind = new Matrix4f(bind).invert();
        Matrix4f animated = new Matrix4f(bone.animWorld);
        scaleMatrixTranslation(animated, scaleW, scaleH);
        Matrix4f d = animated.mul(invBind).mul(toJoml(ysmBindWorld));
        OpenMatrix4f desired = toOpen(d);
        OpenMatrix4f xInverse = OpenMatrix4f.invert(parentLocalProduct, new OpenMatrix4f());
        return OpenMatrix4f.mul(xInverse, desired, null);
    }

    private static void scaleMatrixTranslation(Matrix4f matrix, float scaleW, float scaleH) {
        matrix.m30(matrix.m30() * scaleW);
        matrix.m31(matrix.m31() * scaleH);
        matrix.m32(matrix.m32() * scaleW);
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

}
