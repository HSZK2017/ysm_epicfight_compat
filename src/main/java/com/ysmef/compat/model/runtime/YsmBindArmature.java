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

    /**
     * The already-built re-bound armature for a model (its CURRENT pose
     * matrices reflect the latest combat animation, updated by YSMMesh#draw),
     * or null when the model's mesh has not been drawn yet. Used by the
     * weapon-coordinate correction to position held weapons with the MODEL's
     * proportions instead of the entity's biped armature.
     */
    public static HumanoidArmature getBuiltArmature(String modelId) {
        Entry entry = BY_MODEL.get(modelId);
        return entry == null ? null : entry.armature;
    }

    /** Forget every re-bound armature and captured pose (model reload paths). */
    public static void invalidateAll() {
        BY_MODEL.clear();
        FIST_BY_MODEL.clear();
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

    /**
     * Directly-mapped ACCESSORY bones (YSMJointMapper maps cape/elytra/backpack
     * to Chest): they must still be driven by Epic Fight's animations, but their
     * geometry extends far beyond the body (a cape/wing reaches above the head,
     * a backpack behind/above the shoulders), so they must NOT contribute to the
     * segment-pivot computation - otherwise the "neck" (top of the chest
     * geometry) lands on the accessory and the head detaches from the body.
     */
    private static final Set<String> PIVOT_EXCLUDED_BONE_NAMES = new HashSet<>(List.of(
            "cape", "elytra", "elytralocator", "backpack"));

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

        GeometryData geometry = collectGeometry(runtime, mesh);

        // Segment pivots derived from the model's own geometry (Minecraft frame).
        Vector3f thighR = topOf(geometryOf(geometry.byJoint(), runtime, JOINT_THIGH_R));
        Vector3f thighL = topOf(geometryOf(geometry.byJoint(), runtime, JOINT_THIGH_L));
        Vector3f hip = midpoint(thighR, thighL);
        Vector3f neck = topOf(geometryOf(geometry.byJoint(), runtime, JOINT_CHEST));
        Vector3f chest = midpoint(hip, neck);
        Vector3f kneeR = topOf(geometryOf(geometry.byJoint(), runtime, JOINT_LEG_R));
        Vector3f kneeL = topOf(geometryOf(geometry.byJoint(), runtime, JOINT_LEG_L));
        Vector3f shoulderR = topOf(geometryOf(geometry.byJoint(), runtime, JOINT_ARM_R));
        Vector3f shoulderL = topOf(geometryOf(geometry.byJoint(), runtime, JOINT_ARM_L));
        Vector3f elbowR = topOf(geometryOf(geometry.byJoint(), runtime, JOINT_HAND_R));
        Vector3f elbowL = topOf(geometryOf(geometry.byJoint(), runtime, JOINT_HAND_L));
        Vector3f wristR = handPivot(runtime, geometry.byBone(), JOINT_HAND_R);
        Vector3f wristL = handPivot(runtime, geometry.byBone(), JOINT_HAND_L);

        // Geometric fist positions for the weapon-coordinate correction
        // (RenderItemBaseMixin): the model's actual hand, located from geometry
        // regardless of bone naming (covers Chinese-named hand bones). The fist
        // is the centroid of the hand joint's most distal body bone (the bone
        // farthest from the elbow), weapon bones excluded.
        Vector3f fistR = geometricFist(geometry.byBone(), runtime, JOINT_HAND_R, elbowR);
        Vector3f fistL = geometricFist(geometry.byBone(), runtime, JOINT_HAND_L, elbowL);
        if (fistR != null || fistL != null) {
            FIST_BY_MODEL.put(modelId, new Vector3f[]{fistR, fistL});
        }

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
        // The Tool joint is where held weapons attach: anchor it at the model's
        // geometric fist (the hand's far end). A bare wrist/elbow fallback
        // leaves the weapon floating above the model's actual hand for models
        // without a hand-named bone.
        putPivot(pivots, JOINT_TOOL_R, fistR != null ? fistR : (wristR != null ? wristR : elbowR));
        putPivot(pivots, JOINT_TOOL_L, fistL != null ? fistL : (wristL != null ? wristL : elbowL));

        if (BIND_PIVOT_LOG_LOGGED.add(modelId)) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [bind] model='{}' pivots root={},torso={},chest={},head={},shoulderR={},shoulderL={},elbowR={},elbowL={},wristR={},wristL={}",
                    modelId,
                    fmt(hip), fmt(hip), fmt(chest), fmt(neck), fmt(shoulderR), fmt(shoulderL),
                    fmt(elbowR), fmt(elbowL), fmt(wristR), fmt(wristL));
        }

        Map<String, Joint> jointMap = new HashMap<>();
        Joint newRoot = copyHierarchy(ref.rootJoint, new OpenMatrix4f(), pivots, jointMap, true);
        newRoot.initOriginTransform(new OpenMatrix4f());
        if (DIAG_JOINTS_LOGGED.add(modelId) && com.ysmef.compat.YsmDiag.isEnabled()) {
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
        if (DIAG_LOGGED.add(modelId) && com.ysmef.compat.YsmDiag.isEnabled()) {
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
    private static final java.util.Set<String> BIND_PIVOT_LOG_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String fmt(Vector3f v) {
        if (v == null) {
            return "null";
        }
        return String.format("(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }

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
     * Collect the bind-pose geometry of every mesh part, keyed by EF joint id.
     * At runtime the mesh positions are in Minecraft frame (up = +Y, Epic
     * Fight's loader rotated them out of the JSON's Blender frame while
     * loading), the same frame the pivot matrices are expressed in.
     *
     * Inclusion rule: every bone that resolves to the joint (the runtime JSON's
     * joint field already walked the parent chain) contributes its geometry,
     * EXCEPT trailing-digit variant bones and accessory bones (cape/elytra/
     * backpack). But the mapped/unmapped status matters: a joint that HAS
     * directly-mapped geometry uses only that - this keeps hand-carried items
     * (swords, brooms, key tools: unmapped bones parented under the hand/chest
     * bones) out of the pivot computation. Only when a joint has NO mapped
     * geometry at all (models whose body geometry sits on non-English bones -
     * the Yamashiro shipgirl's "youdabi" arms, the momo wine fox's
     * "RightArm_Default") does the joint fall back to its unmapped bones, so
     * such models no longer lose every segment pivot and fall back to Steve
     * proportions.
     */
    private static GeometryData collectGeometry(YSMRuntimeModel runtime, YSMMesh mesh) {
        Map<Integer, List<Vector3f>> mappedByJoint = new HashMap<>();
        Map<Integer, List<Vector3f>> unmappedByJoint = new HashMap<>();
        float[] positions = mesh.positions();
        // Bones hidden in the default (battle-mode) form never render, so their
        // geometry must not pollute the segment pivots - e.g. the Yukikaze
        // shipgirl's rigging (hidden by default) sits forward of the chest and
        // would otherwise drag the neck pivot off the body.
        java.util.Set<String> hiddenBones = runtime.defaultHiddenBoneNames();
        Map<Integer, List<Vector3f>> byBone = new HashMap<>();
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
            if (hiddenBones.contains(bone.name)) {
                continue;
            }
            // Alternate-form variant bones ("RightLeg2", "Head2"...) carry bind
            // geometry at the base form's (or a completely different) position;
            // they would pollute the per-joint pivot computation.
            if (!bone.name.isEmpty() && Character.isDigit(bone.name.charAt(bone.name.length() - 1))) {
                continue;
            }
            // Accessory bones (cape/elytra/backpack) animate with the body but
            // their geometry extends beyond it - exclude them from the pivot
            // computation so they don't drag the segment pivots.
            if (PIVOT_EXCLUDED_BONE_NAMES.contains(normalize(bone.name))) {
                continue;
            }
            // Held-item bones (swords, spoons, phones, ...) resolve to the Hand
            // joint but extend far past the fist; they must not pollute the
            // elbow/fist computation or the weapon ends up anchored to the
            // held item instead of the hand.
            if (isWeaponBone(normalize(bone.name))) {
                continue;
            }
            List<Vector3f> boneList = byBone.computeIfAbsent(boneIdx, k -> new ArrayList<>());
            Map<Integer, List<Vector3f>> target = bone.mapped ? mappedByJoint : unmappedByJoint;
            List<Vector3f> list = target.computeIfAbsent(bone.joint, k -> new ArrayList<>());
            for (VertexBuilder vb : entry.getValue().getVertices()) {
                int p = vb.position * 3;
                if (p + 2 < positions.length) {
                    Vector3f v = new Vector3f(positions[p], positions[p + 1], positions[p + 2]);
                    boneList.add(v);
                    list.add(v);
                }
            }
        }
        // Per joint: prefer the mapped geometry; fall back to the unmapped
        // bones only when the joint has no mapped geometry at all.
        Map<Integer, List<Vector3f>> byJoint = new HashMap<>(unmappedByJoint);
        for (Map.Entry<Integer, List<Vector3f>> entry : mappedByJoint.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                byJoint.put(entry.getKey(), entry.getValue());
            }
        }
        return new GeometryData(byJoint, byBone);
    }

    /** byJoint: joint-indexed geometry (mapped preferred) for segment pivots; byBone: bone-indexed for the hand-bone lookup. */
    private record GeometryData(Map<Integer, List<Vector3f>> byJoint, Map<Integer, List<Vector3f>> byBone) {}

    /** All bind-pose vertices bound to one EF joint (mapped geometry preferred). */
    private static List<Vector3f> geometryOf(Map<Integer, List<Vector3f>> byJoint,
                                             YSMRuntimeModel runtime, int joint) {
        return byJoint.getOrDefault(joint, List.of());
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

    // ------------------------------------------------------------------
    // Geometric fist positions (weapon-coordinate correction)
    // ------------------------------------------------------------------

    /** modelId -> {fistR, fistL} geometric fist positions (bind space), used by RenderItemBaseMixin. */
    private static final Map<String, Vector3f[]> FIST_BY_MODEL = new ConcurrentHashMap<>();

    /** Weapon-ish name fragments excluded from the fist pick (a held item is not the fist itself). */
    private static final String[] WEAPON_NAME_PARTS = {
            "sword", "gun", "weapon", "blade", "knife", "axe", "bow", "tool", "item", "besom", "key", "wand",
            "staff", "spear", "dagger", "katana", "shield", "scythe", "sickle", "hammer", "pole", "stick",
            "spoon", "fork", "cup", "coaster", "phone", "computer", "book", "grimoire", "lantern", "circle",
            "arrow", "quiver", "mic"
    };

    /**
     * The model's geometric fist position for a hand ("right" = main hand,
     * "left" = off hand), in model bind space, or null when the model has no
     * usable hand geometry. Consumed by the weapon-coordinate correction
     * (RenderItemBaseMixin) to anchor the weapon in the model's actual hand.
     */
    public static Vector3f fistPosition(String modelId, boolean leftHand) {
        Vector3f[] fists = FIST_BY_MODEL.get(modelId);
        if (fists == null) {
            return null;
        }
        return leftHand ? fists[1] : fists[0];
    }

    /** Hand/finger name fragments that mark the bone as the fist itself (any language). */
    private static final String[] HAND_NAME_PARTS = {"hand", "shou", "zhi", "finger", "fist", "palm"};

    /**
     * The geometric fist of one hand joint: the centroid of the joint's
     * hand/finger bones (the hand region's center - where a held weapon grips),
     * weapon/held-item bones excluded. When the model has no hand-named bones,
     * falls back to the centroid of the non-weapon geometry in the far half from
     * the elbow. Returns null when no usable body geometry exists.
     */
    private static Vector3f geometricFist(Map<Integer, List<Vector3f>> byBone, YSMRuntimeModel runtime, int joint, Vector3f elbow) {
        if (elbow == null) {
            return null;
        }
        // Pass 1: the center of the hand region (all hand/finger-named bones combined).
        Vector3f handCenter = handRegionCentroid(byBone, runtime, joint, elbow);
        if (handCenter != null) {
            return handCenter;
        }
        // Pass 2: the center of the far half of the joint's non-weapon geometry.
        return farHalfCentroid(byBone, runtime, joint, elbow);
    }

    /** The combined centroid of the joint's hand/finger-named bones (the hand region's center). */
    private static Vector3f handRegionCentroid(Map<Integer, List<Vector3f>> byBone, YSMRuntimeModel runtime, int joint, Vector3f elbow) {
        Vector3f acc = new Vector3f();
        int count = 0;
        for (Map.Entry<Integer, List<Vector3f>> entry : byBone.entrySet()) {
            if (runtime.bones[entry.getKey()].joint != joint) {
                continue;
            }
            String normalized = normalize(runtime.bones[entry.getKey()].name);
            if (isWeaponBone(normalized) || !isHandBone(normalized)) {
                continue;
            }
            for (Vector3f v : entry.getValue()) {
                acc.add(v);
                count++;
            }
        }
        return count == 0 ? null : acc.div(count);
    }

    /** The centroid of the joint's non-weapon geometry in the far half from the elbow (the hand region). */
    private static Vector3f farHalfCentroid(Map<Integer, List<Vector3f>> byBone, YSMRuntimeModel runtime, int joint, Vector3f elbow) {
        float maxDist = 0.0f;
        Map<Integer, Float> distByBone = new HashMap<>();
        for (Map.Entry<Integer, List<Vector3f>> entry : byBone.entrySet()) {
            if (runtime.bones[entry.getKey()].joint != joint) {
                continue;
            }
            String normalized = normalize(runtime.bones[entry.getKey()].name);
            if (isWeaponBone(normalized)) {
                continue;
            }
            Vector3f centroid = centroidOf(entry.getValue());
            if (centroid == null) {
                continue;
            }
            float dist = centroid.distance(elbow);
            distByBone.put(entry.getKey(), dist);
            maxDist = Math.max(maxDist, dist);
        }
        if (maxDist <= 0.0f) {
            return null;
        }
        // The far half: bones whose centroid is beyond half the max distance from the elbow.
        Vector3f acc = new Vector3f();
        int count = 0;
        for (Map.Entry<Integer, List<Vector3f>> entry : byBone.entrySet()) {
            if (runtime.bones[entry.getKey()].joint != joint) {
                continue;
            }
            String normalized = normalize(runtime.bones[entry.getKey()].name);
            if (isWeaponBone(normalized)) {
                continue;
            }
            Float dist = distByBone.get(entry.getKey());
            if (dist == null || dist < maxDist * 0.5f) {
                continue;
            }
            for (Vector3f v : entry.getValue()) {
                acc.add(v);
                count++;
            }
        }
        return count == 0 ? null : acc.div(count);
    }

    private static boolean isHandBone(String normalized) {
        for (String part : HAND_NAME_PARTS) {
            if (normalized.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWeaponBone(String normalized) {
        for (String part : WEAPON_NAME_PARTS) {
            if (normalized.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** The centroid (mean position) of a vertex list, or null when empty. */
    private static Vector3f centroidOf(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        Vector3f acc = new Vector3f();
        for (Vector3f v : vertices) {
            acc.add(v);
        }
        return acc.div(vertices.size());
    }

    /** Mirrors YSMJointMapper's name normalization (lower case, no spaces/underscores, no trailing digits, no "_Default" form suffix). */
    private static String normalize(String boneName) {
        String normalized = boneName.toLowerCase().replace("_", "").replace(" ", "");
        int end = normalized.length();
        while (end > 0 && Character.isDigit(normalized.charAt(end - 1))) {
            end--;
        }
        normalized = normalized.substring(0, end);
        // YSM's default-form bones may carry a "_Default" form suffix (the momo
        // wine fox's "RightArm_Default"); strip it so the default form's geometry
        // counts for the joint's pivot computation (matches YSMJointMapper).
        if (normalized.endsWith("default")) {
            normalized = normalized.substring(0, normalized.length() - "default".length());
        }
        return normalized;
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
