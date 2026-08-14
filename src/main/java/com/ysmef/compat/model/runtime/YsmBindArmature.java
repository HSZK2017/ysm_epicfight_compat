package com.ysmef.compat.model.runtime;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import org.joml.Vector3f;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.model.armature.HumanoidArmature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-model bind-pose retarget for converted YSM meshes.
 *
 * Epic Fight's combat animations are joint-local rotations that pivot around the
 * bind pose joints of the biped armature (Steve proportions). Converted YSM
 * meshes carry their own joint pivots (shoulder width, limb lengths, torso
 * height...), and every vertex is rigidly bound to one EF joint, so a swung limb
 * rigidly rotates around a Steve joint whose pivot does not sit inside the YSM
 * geometry - the visible arm/leg detachment during weapon swings.
 *
 * The skinning pipeline uploads poseWorld x invBindWorld (see
 * VanillaComputeShaderSetup#drawWithShader); the bind pose is therefore fully
 * retargetable: a per-model armature with the SAME 20-joint topology/names/ids
 * but joint translations derived from the model's own geometry makes every
 * combat rotation pivot at the model's own joints, while the bind pose (identity
 * animation) still maps to the authored mesh shape unchanged (pose x toOrigin
 * degenerates to identity).
 *
 * Pivot selection is GEOMETRY-DRIVEN, not name-driven: bone names are unreliable
 * (alternate-form bones like "RightArm2" carry bind pivots in the base form's
 * space, and locator/decoration bones resolve to the same joint), so each joint's
 * pivot is computed from the converted mesh itself:
 *
 * - Thigh_R/L: top of the thigh geometry (the hip); the hip = their midpoint.
 *   Root and Torso both pivot at the hip (the reference biped has them there).
 * - Head: top of the chest geometry (the neck).
 * - Chest: midpoint between the hip and the neck (the reference biped's Chest
 *   joint sits at the chest center, halfway between the waist and the neck).
 * - Arm_R/L: top of the upper-arm geometry (the shoulder).
 * - Hand_R/L: top of the forearm+hand geometry (the elbow - Epic Fight's
 *   Hand_R joint is the forearm joint).
 * - Leg_R/L: top of the lower-leg geometry (the knee).
 * - Shoulder_R/L follow Arm_R/L, Elbow_R/L and Knee_R/L follow Hand_R/L and
 *   Leg_R/L (the duplicated joints of the reference armature).
 * - Tool_R/L: top of the hand-bone geometry (the wrist) when a separately
 *   named hand bone exists, otherwise the Hand_R/L elbow pivot.
 *
 * All positions are read from the RUNTIME mesh: Epic Fight's JsonAssetLoader
 * applies the Blender-to-Minecraft rotation (BLENDER_TO_MINECRAFT_COORD, -90 deg
 * about X) while loading, so at runtime the mesh positions, the biped armature's
 * joint local transforms and its pose matrices all live in the SAME Minecraft
 * frame (up = +Y). The pivots below are therefore Minecraft-frame world
 * positions; copyHierarchy expresses them in the parent joint's (Minecraft
 * frame) local space. The reference rotation part of every local transform is
 * preserved, so the joint frames (and thus every animation arc) keep the exact
 * orientation Epic Fight expects.
 *
 * Per frame Epic Fight applies the animation pose to the entity's own armature
 * (captured by YsmArmaturePoseMixin). YSMMesh#draw then re-evaluates that pose
 * on this model's YSM-bind armature and draws with its pose matrices. Only the
 * translation components change, so the animations stay intact. YSM script
 * deltas (part transforms) are bind-space and orthogonal to this retarget.
 */
public final class YsmBindArmature {

    private record Entry(YSMRuntimeModel runtime, HumanoidArmature armature) {}

    /** modelId -> re-bound armature (rebuilt when the runtime model is recompiled). */
    private static final Map<String, Entry> BY_MODEL = new ConcurrentHashMap<>();

    /** entity armature instance -> the pose Epic Fight last applied to it. */
    private static final Map<Armature, Pose> POSES = new HashMap<>();

    /** Stale-entry guard: armatures of unloaded entities accumulate otherwise. */
    private static final int POSE_MAP_CAP = 256;

    private YsmBindArmature() {}

    // ------------------------------------------------------------------
    // Per-frame pose capture
    // ------------------------------------------------------------------

    /** Called from YsmArmaturePoseMixin (Armature#setPose TAIL), render thread. */
    public static void onArmatureSetPose(Armature armature, Pose pose) {
        if (armature == null || pose == null) {
            return;
        }
        synchronized (POSES) {
            if (POSES.size() >= POSE_MAP_CAP) {
                POSES.clear();
            }
            POSES.put(armature, pose);
        }
    }

    /** The pose Epic Fight applied to this armature instance, or null. */
    public static Pose findPose(Armature armature) {
        if (armature == null) {
            return null;
        }
        synchronized (POSES) {
            return POSES.get(armature);
        }
    }

    // ------------------------------------------------------------------
    // Per-model re-bound armature
    // ------------------------------------------------------------------

    /**
     * The YSM-bind armature of a model, built lazily. Stale entries (the model
     * was re-converted, a new YSMRuntimeModel instance replaced the old one) are
     * detected by runtime-instance identity and rebuilt.
     */
    public static HumanoidArmature getArmature(String modelId, YSMMesh mesh) {
        if (modelId == null) {
            return null;
        }
        YSMRuntimeModel runtime = YSMRuntimeModel.get(modelId);
        if (runtime == null || runtime.bones.length == 0) {
            return null;
        }
        Entry entry = BY_MODEL.get(modelId);
        if (entry != null && entry.runtime == runtime) {
            return entry.armature;
        }
        HumanoidArmature built = build(modelId, runtime, mesh);
        if (built == null) {
            return null;
        }
        BY_MODEL.put(modelId, new Entry(runtime, built));
        return built;
    }

    /** Forget every re-bound armature and captured pose (model reload paths). */
    public static void invalidateAll() {
        BY_MODEL.clear();
        synchronized (POSES) {
            POSES.clear();
        }
    }

    // ------------------------------------------------------------------
    // Geometry-driven pivot computation
    // ------------------------------------------------------------------

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

    /** Normalized bone names that denote the hand bone itself (its top = the wrist). */
    private static final Set<String> HAND_BONE_NAMES = new HashSet<>(List.of(
            "righthand", "handright", "lefthand", "handleft"));

    /** Ring height tolerance for the segment-top centroid (Minecraft frame, up = +Y). */
    private static final float TOP_RING_EPSILON = 0.05f;

    private static HumanoidArmature build(String modelId, YSMRuntimeModel runtime, YSMMesh mesh) {
        HumanoidArmature ref;
        try {
            ref = Armatures.BIPED.get();
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: cannot load the biped armature, bind retarget disabled for '{}'", modelId);
            return null;
        }
        if (ref == null) {
            return null;
        }

        Map<Integer, List<Vector3f>> byBone = collectGeometry(runtime, mesh);

        // Segment pivots derived from the model's own geometry (Minecraft frame).
        Vector3f thighR = topOf(geometryOf(byBone, runtime, JOINT_THIGH_R));
        Vector3f thighL = topOf(geometryOf(byBone, runtime, JOINT_THIGH_L));
        Vector3f hip = midpoint(thighR, thighL);
        Vector3f neck = topOf(geometryOf(byBone, runtime, JOINT_CHEST));
        Vector3f chest = midpoint(hip, neck);
        Vector3f kneeR = topOf(geometryOf(byBone, runtime, JOINT_LEG_R));
        Vector3f kneeL = topOf(geometryOf(byBone, runtime, JOINT_LEG_L));
        Vector3f shoulderR = topOf(geometryOf(byBone, runtime, JOINT_ARM_R));
        Vector3f shoulderL = topOf(geometryOf(byBone, runtime, JOINT_ARM_L));
        Vector3f elbowR = topOf(geometryOf(byBone, runtime, JOINT_HAND_R));
        Vector3f elbowL = topOf(geometryOf(byBone, runtime, JOINT_HAND_L));
        Vector3f wristR = handPivot(runtime, byBone, JOINT_HAND_R);
        Vector3f wristL = handPivot(runtime, byBone, JOINT_HAND_L);

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

        Map<String, Joint> jointMap = new HashMap<>();
        Joint newRoot = copyHierarchy(ref.rootJoint, new OpenMatrix4f(), pivots, jointMap, true);
        newRoot.initOriginTransform(new OpenMatrix4f());
        if (DIAG_JOINTS_LOGGED.add(modelId)) {
            // Per-joint pivot diag: the exact world position every joint was
            // re-anchored to (Minecraft frame, up = +Y). A head that swings
            // around the chest instead of the neck is immediately visible here.
            StringBuilder sb = new StringBuilder();
            sb.append("YSM-EF Compat: [diag] bind armature joints: model=").append(modelId);
            Joint joint = newRoot;
            java.util.ArrayDeque<Joint> queue = new java.util.ArrayDeque<>();
            queue.add(joint);
            while (!queue.isEmpty()) {
                joint = queue.poll();
                OpenMatrix4f pivot = pivots.get(joint.getId());
                sb.append(" ").append(joint.getName()).append("=");
                if (pivot == null) {
                    sb.append("ref");
                } else {
                    sb.append(String.format("(%.3f,%.3f,%.3f)", pivot.m30, pivot.m31, pivot.m32));
                }
                queue.addAll(joint.getSubJoints());
            }
            YSMEpicFightCompat.LOGGER.info(sb.toString());
        }
        if (DIAG_LOGGED.add(modelId)) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (OpenMatrix4f pivot : pivots.values()) {
                if (pivot == null) {
                    continue;
                }
                minX = Math.min(minX, pivot.m30);
                minY = Math.min(minY, pivot.m31);
                minZ = Math.min(minZ, pivot.m32);
                maxX = Math.max(maxX, pivot.m30);
                maxY = Math.max(maxY, pivot.m31);
                maxZ = Math.max(maxZ, pivot.m32);
            }
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [diag] bind armature built: model={} bones={} joints={} pivotRange=([{},{}],[{},{}],[{},{}])",
                    modelId, runtime.bones.length, ref.getJointNumber(),
                    minX, maxX, minY, maxY, minZ, maxZ);
        }
        return new HumanoidArmature("ysm_bind_" + modelId, ref.getJointNumber(), newRoot, jointMap);
    }

    private static final java.util.Set<String> DIAG_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> DIAG_JOINTS_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Armature-frame world position of a pivot, or null to keep the reference translation. */
    private static void putPivot(Map<Integer, OpenMatrix4f> pivots, int joint, Vector3f pos) {
        if (pos == null) {
            return;
        }
        OpenMatrix4f matrix = new OpenMatrix4f();
        // OpenMatrix4f is row-vector convention: the translation lives in the
        // LAST ROW (m30/m31/m32), not m03/m13/m23.
        matrix.m30 = pos.x;
        matrix.m31 = pos.y;
        matrix.m32 = pos.z;
        pivots.put(joint, matrix);
    }

    /**
     * Collect the bind-pose geometry of every mesh part, keyed by the runtime
     * bone index. At runtime the mesh positions are in Minecraft frame (up = +Y,
     * Epic Fight's loader rotated them out of the JSON's Blender frame while
     * loading), the same frame the pivot matrices are expressed in.
     */
    private static Map<Integer, List<Vector3f>> collectGeometry(YSMRuntimeModel runtime, YSMMesh mesh) {
        Map<Integer, List<Vector3f>> byBone = new HashMap<>();
        float[] positions = mesh.positions();
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            Integer boneIdx = runtime.boneIndex.get(partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (boneIdx == null || boneIdx >= runtime.bones.length) {
                continue;
            }
            YSMRuntimeModel.BoneRt bone = runtime.bones[boneIdx];
            // Only directly-mapped body bones define segment pivots: decorations
            // (tails, bows, capes...) resolve to a joint by walking up their
            // ancestor chain (mapped=false) and their geometry can extend far
            // beyond the body part - e.g. the wine_fox's up-curled tail reaches
            // ABOVE the head and would become the "neck".
            if (!bone.mapped) {
                continue;
            }
            // Alternate-form variant bones ("RightLeg2", "Head2"...) carry bind
            // geometry at the base form's (or a completely different) position;
            // they would pollute the per-joint pivot computation.
            if (!bone.name.isEmpty() && Character.isDigit(bone.name.charAt(bone.name.length() - 1))) {
                continue;
            }
            List<Vector3f> list = byBone.computeIfAbsent(boneIdx, k -> new ArrayList<>());
            for (VertexBuilder vb : entry.getValue().getVertices()) {
                int p = vb.position * 3;
                if (p + 2 < positions.length) {
                    list.add(new Vector3f(positions[p], positions[p + 1], positions[p + 2]));
                }
            }
        }
        return byBone;
    }

    /** All bind-pose vertices bound to one EF joint. */
    private static List<Vector3f> geometryOf(Map<Integer, List<Vector3f>> byBone,
                                             YSMRuntimeModel runtime, int joint) {
        List<Vector3f> merged = new ArrayList<>();
        for (Map.Entry<Integer, List<Vector3f>> entry : byBone.entrySet()) {
            if (runtime.bones[entry.getKey()].joint == joint) {
                merged.addAll(entry.getValue());
            }
        }
        return merged;
    }

    /**
     * The proximal end of a limb segment: the centroid of the vertices at the
     * segment's top (the ring within {@link #TOP_RING_EPSILON} of the maximum Y).
     * The runtime mesh is in Minecraft frame, so "up" = +Y (hip, knee, shoulder,
     * elbow, neck...). Returns null when the segment has no geometry.
     */
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
            if (v.y >= maxY - TOP_RING_EPSILON) {
                acc.add(v);
                n++;
            }
        }
        if (n == 0) {
            return new Vector3f(vertices.get(0));
        }
        return acc.div(n);
    }

    /**
     * The wrist: the top of the geometry of the separately named hand bone
     * ("righthand"/"lefthand" and their mirrors), or null when the model has no
     * such bone (the Tool joints then fall back to the Hand elbow pivot).
     */
    private static Vector3f handPivot(YSMRuntimeModel runtime, Map<Integer, List<Vector3f>> byBone, int joint) {
        for (Map.Entry<Integer, List<Vector3f>> entry : byBone.entrySet()) {
            if (runtime.bones[entry.getKey()].joint != joint) {
                continue;
            }
            String normalized = normalize(runtime.bones[entry.getKey()].name);
            if (HAND_BONE_NAMES.contains(normalized)) {
                return topOf(entry.getValue());
            }
        }
        return null;
    }

    /** Mirrors YSMJointMapper's name normalization (lower case, no spaces/underscores, no trailing digits). */
    private static String normalize(String boneName) {
        String normalized = boneName.toLowerCase().replace("_", "").replace(" ", "");
        int end = normalized.length();
        while (end > 0 && Character.isDigit(normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end);
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

    /**
     * Copy the reference hierarchy, replacing the translation (m30/m31/m32 - the
     * last row of OpenMatrix4f's row-vector convention) of every joint that has
     * a computed pivot with the pivot offset in the parent's frame. The reference
     * rotation part of each local transform is preserved, so the joint frames
     * (and thus every animation arc) are identical to the biped's. Joints
     * without a computed pivot keep the reference local transform entirely.
     */
    private static Joint copyHierarchy(Joint refJoint, OpenMatrix4f newParentWorld,
                                       Map<Integer, OpenMatrix4f> pivots, Map<String, Joint> out, boolean root) {
        OpenMatrix4f refLocal = refJoint.getLocalTransform();
        OpenMatrix4f newLocal = new OpenMatrix4f(refLocal);
        OpenMatrix4f pivot = pivots.get(refJoint.getId());
        if (pivot != null) {
            if (root) {
                // The root has no parent: its world transform IS its local, so
                // the pivot (the model's hip) becomes the local translation directly.
                newLocal.m30 = pivot.m30;
                newLocal.m31 = pivot.m31;
                newLocal.m32 = pivot.m32;
            } else {
                // local translation = (new parent world)^-1 x pivot world, i.e. the
                // pivot offset expressed in the parent's (already re-positioned) frame.
                OpenMatrix4f parentInv = OpenMatrix4f.invert(newParentWorld, null);
                OpenMatrix4f offset = OpenMatrix4f.mul(parentInv, pivot, null);
                newLocal.m30 = offset.m30;
                newLocal.m31 = offset.m31;
                newLocal.m32 = offset.m32;
            }
        }
        Joint joint = new Joint(refJoint.getName(), refJoint.getId(), newLocal);
        out.put(joint.getName(), joint);
        OpenMatrix4f newWorld = OpenMatrix4f.mul(newParentWorld, newLocal, null);
        for (Joint child : refJoint.getSubJoints()) {
            joint.addSubJoints(copyHierarchy(child, newWorld, pivots, out, false));
        }
        return joint;
    }
}
