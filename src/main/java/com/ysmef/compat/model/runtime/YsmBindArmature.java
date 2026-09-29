package com.ysmef.compat.model.runtime;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.YsmDiag;
import com.ysmef.compat.model.BoneAlternateForms;
import com.ysmef.compat.model.JointTable;
import com.ysmef.compat.model.YSMJointMapper;
import com.ysmef.compat.model.YSMMesh;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.model.armature.HumanoidArmature;

/**
 * Builds the per-model armature the converted mesh is drawn on, with one thing changed from Epic
 * Fight's reference biped: <b>every joint's pivot comes from this model's own geometry</b>.
 *
 * <h2>Why the pivots have to be geometry-derived</h2>
 *
 * <p>Epic Fight rotates a joint about its pivot, and a joint whose pivot is absent falls back to the
 * reference biped's - Steve's - silently. That is what the game log showed for {@code 兽耳酱x1} and
 * {@code NagaU_Kemomimi} ({@code [bind] ... pivots root=null,torso=null,...} for every joint) and,
 * for the arm chain, for {@code STRESSTEST_SMT_Nahobino}: the model's limbs then rotated about
 * origins its own geometry does not have, and the geometry visibly came apart. So a null pivot is not
 * a harmless default here; it is the defect, and the two WARNs described below exist to make it
 * impossible for a model to reach one quietly.
 *
 * <h2>Which geometry a joint is measured from: four nested relaxation tiers</h2>
 *
 * <p>Every bone is placed in one of four tiers by {@link #tierOf}, and {@link #collectGeometry}
 * settles each joint on the <i>strictest</i> tier that has any geometry for it:
 *
 * <ol>
 *   <li><b>tier 0, filtered.</b> Every bone the model has, minus nothing: the standard set. This is
 *       what almost every model answers with.</li>
 *   <li><b>tier 1, + alternate forms.</b> A bone named as a variant of another bone that exists
 *       ({@code RightLeg2} beside {@code RightLeg}) is normally excluded: its bind geometry sits
 *       somewhere else on the model - a spare part, a second form - and letting it serve the joint
 *       moves the pivot by however far the author parked it. When the variant is the <i>only</i>
 *       geometry a joint has, an imperfect pivot still beats one in empty space, so it is taken
 *       back.</li>
 *   <li><b>tier 2, + accessories and held items.</b> A bone whose name reads as a cape, an elytra or
 *       a weapon is excluded from the standard set for the same reason: its geometry reaches past the
 *       body it is worn on. Taken back only when the joint has nothing else.</li>
 *   <li><b>tier 3, + hidden bones.</b> A bone the model's default form hides (a variant body, an
 *       accessory, the Yukikaze shipgirl's rigging) never renders, so its geometry must not place a
 *       visible joint's pivot. It is the last thing given up, because a pivot on hidden geometry is
 *       still better than a pivot on the reference biped.</li>
 * </ol>
 *
 * <p>Relaxing is never silent: a joint that had to give up a filter is reported once per model
 * ({@link #relaxationWarning}, WARN #1) and handed back as data in {@link GeometryData#relaxation()},
 * so callers and tests can see it without parsing a log. {@code YsmBindArmatureTest} pins each tier
 * on the failing models' own bones: a skeleton whose every bone ends in a digit is <i>not</i> a set of
 * variants - the trailing digit is a variant marker only when the base form is present, which is what
 * {@link com.ysmef.compat.model.BoneAlternateForms} decides, and reading it absolutely is the
 * regression that broke both all-null models - a real variant is still excluded, and no joint, and no
 * model, is ever left without geometry.
 *
 * <h2>Where each pivot comes from</h2>
 *
 * <p>{@link #computePivots} walks the joints in the order their references depend on each other: the
 * hip is the midpoint of the two thigh joints' top rings, the neck is the top of the Chest joint's
 * settled geometry (with the ornament correction described on {@link #neckPivot}), the chest is
 * halfway between the two, and the limb joints come from {@link #segmentPivot} - the joint's own
 * <i>directly-mapped</i> segment geometry when that geometry reaches further up towards the parent,
 * the settled geometry otherwise. {@code YsmBindArmatureSegmentPivotTest} is that rule's
 * calibration: a shin that reaches the hip beats an ankle decal, a spare part parked above the hips
 * loses to it, and a hidden segment bone never wins. Wrists come from a bone named for a hand, and
 * fists from the centroid of the hand-region geometry or - when the hand is modelled as a
 * weapon-shaped mesh - from the centroid of its far half. A model that yields no pivot at all is
 * reported ({@link #noPivotWarning}, WARN #2).
 *
 * <h2>The seams, so nothing here has to know about the game</h2>
 *
 * <ul>
 *   <li>{@link GeometryInput} - what the filter is given: the bone table (names, joints, the
 *       directly-mapped flag), the set of bones hidden in the default form, and the vertex list of
 *       every bone's own mesh part.</li>
 *   <li>{@link GeometryData} - what it produces: the settled vertices per joint, per bone and the
 *       mapped-only segment geometry per joint, plus the {@link Relaxation} record.</li>
 *   <li>{@link BindPivots} - the joint-to-pivot map the armature is built from, plus the wrist and
 *       fist points the renderer and the physics ask for later.</li>
 *   <li>{@link #warningSink()} - the production consumer of the two WARNs, which is the mod log. The
 *       tests pass their own consumer instead, which is why the rules above can be asserted on a
 *       machine with no game running.</li>
 * </ul>
 *
 * <p>The armatures are cached per model and rebuilt when the model's runtime table is replaced
 * ({@link #getArmature}); {@link #invalidateAll} clears that cache, the pose cache and the fist
 * points on a resource reload.
 *
 * <p><b>Provenance.</b> This file was zeroed by an editing accident and restored by decompiling its
 * own pre-incident bytecode, so its logic is the shipped logic while every comment had to be
 * reconstructed - from the tests that encode the rules ({@code YsmBindArmatureTest},
 * {@code YsmBindArmatureSegmentPivotTest}, {@code YsmBindArmatureRealModelTest}), from
 * {@code CHANGELOG.md}, and from the round reports under {@code build/reports/}. Where this prose and
 * the code disagree, the code is the truth and the comment is the bug.
 */
public final class YsmBindArmature {
   private static final Map<String, com.ysmef.compat.model.runtime.YsmBindArmature.Entry> BY_MODEL = new ConcurrentHashMap<>();
   private static final Map<Armature, Pose> POSES = new HashMap<>();
   private static final int POSE_MAP_CAP = 256;
   /**
    * Epic Fight's joint ids, spelled out because this class builds an armature joint by joint and
    * reads pivots back by id. They are catalogue numbers, not names: {@code JointTable} is the single
    * source of truth for what each one is called, and the two must agree.
    */
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
   /** The set of names consulted by {@link #handPivot}: a bone named for a hand places the wrist. */
   private static final Set<String> HAND_BONE_NAMES = new HashSet<>(List.of("righthand", "handright", "lefthand", "handleft"));

   /**
    * The names excluded from the standard tier (tier 2): a cape, an elytra or a backpack reaches past
    * the body it hangs from, so its geometry is the wrong shape to place a body joint's pivot - unless
    * it is all the joint has, which is what tier 2 exists for.
    */
   private static final Set<String> PIVOT_EXCLUDED_BONE_NAMES = new HashSet<>(List.of("cape", "elytra", "elytralocator", "backpack"));

   /** How thick the "top ring" band is, in blocks: see {@link #topOf}. */
   private static final float TOP_RING_EPSILON = 0.05F;
   private static final int TIER_FILTERED = 0;
   private static final int TIER_VARIANTS = 1;
   private static final int TIER_ACCESSORIES = 2;
   private static final int TIER_UNFILTERED = 3;
   private static final Set<String> DIAG_LOGGED = ConcurrentHashMap.newKeySet();
   private static final Set<String> DIAG_JOINTS_LOGGED = ConcurrentHashMap.newKeySet();
   private static final Set<String> BIND_PIVOT_LOG_LOGGED = ConcurrentHashMap.newKeySet();
   private static final String[] TIER_GAVE_UP = new String[]{
      "no filter",
      "the alternate-form-bone filter",
      "the alternate-form-bone and accessory/held-item filters",
      "every filter, including the hidden-bone filter"
   };
   private static final int RELAXED_BONES_LOGGED = 8;
   private static final Map<String, Vector3f[]> FIST_BY_MODEL = new ConcurrentHashMap<>();
   private static final String[] WEAPON_NAME_PARTS = new String[]{
      "sword",
      "gun",
      "weapon",
      "blade",
      "knife",
      "axe",
      "bow",
      "tool",
      "item",
      "besom",
      "key",
      "wand",
      "staff",
      "spear",
      "dagger",
      "katana",
      "shield",
      "scythe",
      "sickle",
      "hammer",
      "pole",
      "stick",
      "spoon",
      "fork",
      "cup",
      "coaster",
      "phone",
      "computer",
      "book",
      "grimoire",
      "lantern",
      "circle",
      "arrow",
      "quiver",
      "mic"
   };
   private static final String[] HAND_NAME_PARTS = new String[]{"hand", "shou", "zhi", "finger", "fist", "palm"};

   private YsmBindArmature() {
   }

   public static void onArmatureSetPose(Armature armature, Pose pose) {
      if (armature != null && pose != null) {
         synchronized (POSES) {
            if (POSES.size() >= 256) {
               POSES.clear();
            }

            POSES.put(armature, pose);
         }
      }
   }

   public static Pose findPose(Armature armature) {
      if (armature == null) {
         return null;
      } else {
         synchronized (POSES) {
            return POSES.get(armature);
         }
      }
   }

   public static HumanoidArmature getArmature(String modelId, YSMMesh mesh) {
      if (modelId == null) {
         return null;
      } else {
         com.ysmef.compat.model.runtime.YSMRuntimeModel runtime = com.ysmef.compat.model.runtime.YSMRuntimeModel.get(modelId);
         if (runtime != null && runtime.bones.length != 0) {
            com.ysmef.compat.model.runtime.YsmBindArmature.Entry entry = BY_MODEL.get(modelId);
            if (entry != null && entry.runtime == runtime) {
               return entry.armature;
            } else {
               HumanoidArmature built = build(modelId, runtime, mesh);
               if (built == null) {
                  return null;
               } else {
                  BY_MODEL.put(modelId, new com.ysmef.compat.model.runtime.YsmBindArmature.Entry(runtime, built));
                  return built;
               }
            }
         } else {
            return null;
         }
      }
   }

   public static HumanoidArmature getBuiltArmature(String modelId) {
      com.ysmef.compat.model.runtime.YsmBindArmature.Entry entry = BY_MODEL.get(modelId);
      return entry == null ? null : entry.armature;
   }

   public static void invalidateAll() {
      BY_MODEL.clear();
      FIST_BY_MODEL.clear();
      synchronized (POSES) {
         POSES.clear();
      }
   }

   private static HumanoidArmature build(String modelId, com.ysmef.compat.model.runtime.YSMRuntimeModel runtime, YSMMesh mesh) {
      HumanoidArmature ref;
      try {
         ref = (HumanoidArmature)Armatures.BIPED.get();
      } catch (Throwable var29) {
         YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: cannot load the biped armature, bind retarget disabled for '{}'", modelId);
         return null;
      }

      if (ref == null) {
         return null;
      } else {
         com.ysmef.compat.model.runtime.YsmBindArmature.GeometryInput input = inputOf(runtime, mesh);
         com.ysmef.compat.model.runtime.YsmBindArmature.GeometryData geometry = collectGeometry(input, warningSink());
         com.ysmef.compat.model.runtime.YsmBindArmature.BindPivots bind = computePivots(input, geometry, warningSink());
         Vector3f hip = bind.byJoint().get(0);
         Vector3f chest = bind.byJoint().get(8);
         Vector3f neck = bind.byJoint().get(9);
         Vector3f shoulderR = bind.byJoint().get(11);
         Vector3f shoulderL = bind.byJoint().get(16);
         Vector3f elbowR = bind.byJoint().get(12);
         Vector3f elbowL = bind.byJoint().get(17);
         Vector3f wristR = bind.wristR();
         Vector3f wristL = bind.wristL();
         Vector3f fistR = bind.fistR();
         Vector3f fistL = bind.fistL();
         if (fistR != null || fistL != null) {
            FIST_BY_MODEL.put(modelId, new Vector3f[]{fistR, fistL});
         }

         Map<Integer, OpenMatrix4f> pivots = new HashMap<>();

         for (Map.Entry<Integer, Vector3f> entry : bind.byJoint().entrySet()) {
            putPivot(pivots, entry.getKey(), entry.getValue());
         }

         if (BIND_PIVOT_LOG_LOGGED.add(modelId)) {
            YSMEpicFightCompat.LOGGER
               .info(
                  "YSM-EF Compat: [bind] model='{}' pivots root={},torso={},chest={},head={},shoulderR={},shoulderL={},elbowR={},elbowL={},wristR={},wristL={}",
                  new Object[]{
                     modelId, fmt(hip), fmt(hip), fmt(chest), fmt(neck), fmt(shoulderR), fmt(shoulderL), fmt(elbowR), fmt(elbowL), fmt(wristR), fmt(wristL)
                  }
               );
         }

         Map<String, Joint> jointMap = new HashMap<>();
         Joint newRoot = copyHierarchy(ref.rootJoint, new OpenMatrix4f(), pivots, jointMap, true);
         newRoot.initOriginTransform(new OpenMatrix4f());
         if (DIAG_JOINTS_LOGGED.add(modelId) && YsmDiag.isEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append("YSM-EF Compat: [diag] bind armature joints: model=").append(modelId);
            ArrayDeque<Joint> queue = new ArrayDeque<>();
            queue.add(newRoot);

            while (!queue.isEmpty()) {
               Joint minY = queue.poll();
               OpenMatrix4f pivot = pivots.get(minY.getId());
               sb.append(" ").append(minY.getName()).append("=");
               if (pivot == null) {
                  sb.append("ref");
               } else {
                  sb.append(String.format("(%.3f,%.3f,%.3f)", pivot.m30, pivot.m31, pivot.m32));
               }

               queue.addAll(minY.getSubJoints());
            }

            YSMEpicFightCompat.LOGGER.info(sb.toString());
         }

         if (DIAG_LOGGED.add(modelId) && YsmDiag.isEnabled()) {
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;

            for (OpenMatrix4f pivot : pivots.values()) {
               if (pivot != null) {
                  minX = Math.min(minX, pivot.m30);
                  minY = Math.min(minY, pivot.m31);
                  minZ = Math.min(minZ, pivot.m32);
                  maxX = Math.max(maxX, pivot.m30);
                  maxY = Math.max(maxY, pivot.m31);
                  maxZ = Math.max(maxZ, pivot.m32);
               }
            }

            YSMEpicFightCompat.LOGGER
               .info(
                  "YSM-EF Compat: [diag] bind armature built: model={} bones={} joints={} pivotRange=([{},{}],[{},{}],[{},{}])",
                  modelId,
                  runtime.bones.length,
                  ref.getJointNumber(),
                  minX,
                  maxX,
                  minY,
                  maxY,
                  minZ,
                  maxZ
               );
         }

         return new HumanoidArmature("ysm_bind_" + modelId, ref.getJointNumber(), newRoot, jointMap);
      }
   }

   private static String fmt(Vector3f v) {
      return v == null ? "null" : String.format("(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
   }

   private static void putPivot(Map<Integer, OpenMatrix4f> pivots, int joint, Vector3f pos) {
      if (pos != null) {
         OpenMatrix4f matrix = new OpenMatrix4f();
         matrix.m30 = pos.x;
         matrix.m31 = pos.y;
         matrix.m32 = pos.z;
         pivots.put(joint, matrix);
      }
   }

   /**
    * Sorts every bone's vertices into the four relaxation tiers and settles each joint on the
    * strictest tier that has any geometry for it.
    *
    * <p>Two splits are made on the way, and both matter downstream:
    *
    * <ul>
    *   <li><b>Directly mapped bones first.</b> Within a tier, geometry from a bone the model itself
    *       maps onto this joint is preferred to geometry that merely resolved to it: a shirt bound to
    *       the Chest says more about where the Chest is than a decoration that happens to hang there.
    *       The settled list is therefore one list per joint, not two - the preference is applied while
    *       the tiers are merged, so a joint's geometry is never doubled up.</li>
    *   <li><b>A segment list beside the settled one.</b> {@link GeometryData#segmentByJoint()} keeps
    *       the mapped-only geometry of tiers 0-2, which is what {@link #segmentPivot} prefers for a
    *       limb joint: the bone that actually draws the limb, not the decal nearest the joint.</li>
    * </ul>
    *
    * <p>The relaxation record is built from the same walk: a joint whose settled bones came from a
    * tier above 0 is reported once per model ({@link #relaxationWarning}), because a pivot that had to
    * relax is a pivot measured on geometry the author marked as a spare part, a costume or a hidden
    * variant - legitimate when there is nothing else, and worth knowing about when there is.
    *
    * @param in   the bones, joints, mapping flags, hidden set and mesh parts to filter
    * @param warn receives WARN #1, once, when any joint had to relax
    */
   static com.ysmef.compat.model.runtime.YsmBindArmature.GeometryData collectGeometry(
      com.ysmef.compat.model.runtime.YsmBindArmature.GeometryInput in, Consumer<String> warn
   ) {
      String[] names = in.boneNames();
      Set<String> baseForms = BoneAlternateForms.baseFormsPresent(names);
      int[] tierOfBone = new int[names.length];

      for (int i = 0; i < names.length; i++) {
         tierOfBone[i] = tierOf(names[i], baseForms, in.hiddenBones());
      }

      int tiers = 4;
      List<Map<Integer, List<Vector3f>>> mappedByTier = new ArrayList<>(tiers);
      List<Map<Integer, List<Vector3f>>> unmappedByTier = new ArrayList<>(tiers);

      for (int tier = 0; tier < tiers; tier++) {
         mappedByTier.add(new HashMap<>());
         unmappedByTier.add(new HashMap<>());
      }

      Map<Integer, List<Vector3f>> verticesByBone = new HashMap<>();

      for (com.ysmef.compat.model.runtime.YsmBindArmature.BoneGeometry part : in.parts()) {
         int boneIdx = part.boneIndex();
         if (boneIdx >= 0 && boneIdx < names.length) {
            List<Vector3f> boneList = verticesByBone.computeIfAbsent(boneIdx, k -> new ArrayList<>());
            Map<Integer, List<Vector3f>> target = (in.boneMapped()[boneIdx] ? mappedByTier : unmappedByTier).get(tierOfBone[boneIdx]);
            List<Vector3f> jointList = target.computeIfAbsent(in.boneJoints()[boneIdx], k -> new ArrayList<>());

            for (Vector3f v : part.vertices()) {
               boneList.add(v);
               jointList.add(v);
            }
         }
      }

      List<Map<Integer, List<Vector3f>>> byJointByTier = new ArrayList<>(tiers);

      for (int tier = 0; tier < tiers; tier++) {
         Map<Integer, List<Vector3f>> merged = new HashMap<>(unmappedByTier.get(tier));

         for (Map.Entry<Integer, List<Vector3f>> entry : mappedByTier.get(tier).entrySet()) {
            if (!entry.getValue().isEmpty()) {
               merged.put(entry.getKey(), entry.getValue());
            }
         }

         byJointByTier.add(merged);
      }

      Map<Integer, List<Vector3f>> byJoint = new HashMap<>();
      Map<Integer, Integer> tierByJoint = new HashMap<>();

      for (int tier = 0; tier < tiers; tier++) {
         for (Map.Entry<Integer, List<Vector3f>> entryx : byJointByTier.get(tier).entrySet()) {
            if (!entryx.getValue().isEmpty() && !tierByJoint.containsKey(entryx.getKey())) {
               byJoint.put(entryx.getKey(), entryx.getValue());
               tierByJoint.put(entryx.getKey(), tier);
            }
         }
      }

      Map<Integer, List<Vector3f>> segmentByJoint = new HashMap<>();

      for (int tier = 0; tier <= 2; tier++) {
         for (Map.Entry<Integer, List<Vector3f>> entryxx : mappedByTier.get(tier).entrySet()) {
            if (!entryxx.getValue().isEmpty()) {
               segmentByJoint.putIfAbsent(entryxx.getKey(), entryxx.getValue());
            }
         }
      }

      Map<Integer, List<String>> relaxedBonesByJoint = new LinkedHashMap<>();
      Map<Integer, List<Vector3f>> byBone = new HashMap<>();

      for (Map.Entry<Integer, List<Vector3f>> entryxxx : verticesByBone.entrySet()) {
         int boneIdx = entryxxx.getKey();
         Integer tier = tierByJoint.get(in.boneJoints()[boneIdx]);
         if (tier != null && tierOfBone[boneIdx] <= tier) {
            byBone.put(boneIdx, entryxxx.getValue());
            if (tierOfBone[boneIdx] != 0 && !entryxxx.getValue().isEmpty()) {
               relaxedBonesByJoint.computeIfAbsent(in.boneJoints()[boneIdx], k -> new ArrayList<>()).add(names[boneIdx]);
            }
         }
      }

      com.ysmef.compat.model.runtime.YsmBindArmature.Relaxation relaxation = null;
      if (!relaxedBonesByJoint.isEmpty()) {
         Map<Integer, Integer> relaxedTierByJoint = new LinkedHashMap<>();

         for (Integer joint : relaxedBonesByJoint.keySet()) {
            relaxedTierByJoint.put(joint, tierByJoint.get(joint));
         }

         relaxation = new com.ysmef.compat.model.runtime.YsmBindArmature.Relaxation(relaxedTierByJoint, relaxedBonesByJoint);
         warn.accept(relaxationWarning(in.modelId(), relaxedTierByJoint, relaxedBonesByJoint));
      }

      return new com.ysmef.compat.model.runtime.YsmBindArmature.GeometryData(byJoint, byBone, segmentByJoint, relaxation);
   }

   /**
    * Which relaxation tier a bone belongs to: 0 standard, 1 alternate form, 2 accessory or held item,
    * 3 hidden in the model's default form.
    *
    * <p>The tier is the <i>depth of filter</i> a bone needs before it may place a pivot, and
    * {@link #collectGeometry} settles each joint on the lowest-numbered tier that has geometry for it.
    * The order is the order of confidence: a bone the model itself marks as a hidden variant is the
    * least trustworthy reference for a visible joint, so it is the last one taken back.
    *
    * <p>{@code hiddenBones} is computed from the model's parallel animations (the default,
    * battle-mode form), so it is data about <i>this</i> model rather than a naming convention.
    * {@code baseForms} is the other half - see {@link com.ysmef.compat.model.BoneAlternateForms}: a
    * trailing digit is a variant marker only when the base form is a bone of this same model.
    */
   static int tierOf(String name, Set<String> baseForms, Set<String> hiddenBones) {
      if (hiddenBones.contains(name)) {
         return 3;
      } else {
         String normalized = normalize(name);
         if (!PIVOT_EXCLUDED_BONE_NAMES.contains(normalized) && !isWeaponBone(normalized)) {
            return BoneAlternateForms.isAlternateForm(name, baseForms) ? 1 : 0;
         } else {
            return 2;
         }
      }
   }

   /**
    * Where the two WARNs go in production: the mod log.
    *
    * <p>It is a seam rather than a call to the logger inside the rules so that the tests can assert
    * what a model reports - the relaxation tier a joint gave up, and the model that produced no pivot
    * at all - on a machine with no game running. Both messages are worth their volume: the first says
    * a pivot was measured on geometry the author marked as a spare part or a hidden variant, and the
    * second says the model is about to rotate its limbs about the reference biped's joints.
    */
   static Consumer<String> warningSink() {
      return message -> YSMEpicFightCompat.LOGGER.warn(message);
   }

   /**
    * WARN #1: a model whose joints needed the relaxed tiers, named joint by joint with the filter
    * each one gave up and the bones it fell back on.
    *
    * <p>Reported once per model, after the whole filter has run, because the interesting fact is the
    * set - "this model's legs and one arm had to relax" - and a line per joint would be noise on the
    * models that legitimately need it. {@code TIER_GAVE_UP} turns the tier number back into the
    * filter's name so the message says which exclusion was dropped rather than which row was reached.
    */
   private static String relaxationWarning(String modelId, Map<Integer, Integer> tierByJoint, Map<Integer, List<String>> bonesByJoint) {
      StringBuilder sb = new StringBuilder(192);
      sb.append("YSM-EF Compat: [bind] model='")
         .append(modelId)
         .append("' had no geometry for ")
         .append(tierByJoint.size())
         .append(" joint(s) under the standard bind filters; relaxed so their pivots stay geometry-derived instead of falling back to the reference biped's: ");
      boolean first = true;

      for (Map.Entry<Integer, Integer> entry : tierByJoint.entrySet()) {
         if (!first) {
            sb.append("; ");
         }

         first = false;
         sb.append(JointTable.nameOf(entry.getKey()))
            .append(" gave up ")
            .append(TIER_GAVE_UP[entry.getValue()])
            .append(" (")
            .append(boneList(bonesByJoint.get(entry.getKey())))
            .append(')');
      }

      return sb.toString();
   }

   private static String boneList(List<String> names) {
      if (names != null && !names.isEmpty()) {
         StringBuilder sb = new StringBuilder(64);

         for (int i = 0; i < names.size() && i < 8; i++) {
            if (i > 0) {
               sb.append(", ");
            }

            sb.append(names.get(i));
         }

         if (names.size() > 8) {
            sb.append(", +").append(names.size() - 8).append(" more");
         }

         return sb.toString();
      } else {
         return "no geometry-carrying bone";
      }
   }

   private static com.ysmef.compat.model.runtime.YsmBindArmature.GeometryInput inputOf(com.ysmef.compat.model.runtime.YSMRuntimeModel runtime, YSMMesh mesh) {
      float[] positions = mesh.positions();
      List<com.ysmef.compat.model.runtime.YsmBindArmature.BoneGeometry> parts = new ArrayList<>();

      for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
         String partName = entry.getKey();
         if (partName.startsWith("y/")) {
            Integer boneIdx = runtime.boneIndex.get(partName.substring("y/".length()));
            if (boneIdx != null && boneIdx < runtime.bones.length) {
               List<Vector3f> vertices = new ArrayList<>();

               for (VertexBuilder vb : entry.getValue().getVertices()) {
                  int p = vb.position * 3;
                  if (p + 2 < positions.length) {
                     vertices.add(new Vector3f(positions[p], positions[p + 1], positions[p + 2]));
                  }
               }

               parts.add(new com.ysmef.compat.model.runtime.YsmBindArmature.BoneGeometry(boneIdx, vertices));
            }
         }
      }

      String[] names = new String[runtime.bones.length];
      int[] joints = new int[runtime.bones.length];
      boolean[] mapped = new boolean[runtime.bones.length];

      for (int i = 0; i < runtime.bones.length; i++) {
         names[i] = runtime.bones[i].name;
         joints[i] = runtime.bones[i].joint;
         mapped[i] = runtime.bones[i].mapped;
      }

      return new com.ysmef.compat.model.runtime.YsmBindArmature.GeometryInput(runtime.modelId, names, joints, mapped, runtime.defaultHiddenBoneNames(), parts);
   }

   private static List<Vector3f> geometryOf(Map<Integer, List<Vector3f>> byJoint, int joint) {
      return byJoint.getOrDefault(joint, List.of());
   }

   /**
    * Every pivot this model can supply, in the order the references between them require.
    *
    * <p>The order is the method's contract: the hip is read from the thighs, the neck from the chest
    * geometry, the chest from the hip and the neck, and every limb joint from a comparison against
    * its already-staged parent. Reading them in any other order changes the answer, which is why the
    * two shipped fixture tests assert both the pivots and the parents they were measured against.
    *
    * <p>{@code hip} is also the body's vertical axis: the neck rule and the walk that finds the
    * mirrored side both use it as "the middle of the model", because it is the one horizontal
    * reference here that is measured from other geometry rather than from the joint being placed.
    */
   static com.ysmef.compat.model.runtime.YsmBindArmature.BindPivots computePivots(
      com.ysmef.compat.model.runtime.YsmBindArmature.GeometryInput in,
      com.ysmef.compat.model.runtime.YsmBindArmature.GeometryData geometry,
      Consumer<String> warn
   ) {
      Map<Integer, List<Vector3f>> byJoint = geometry.byJoint();
      Vector3f thighR = topOf(geometryOf(byJoint, 1));
      Vector3f thighL = topOf(geometryOf(byJoint, 4));
      Vector3f hip = midpoint(thighR, thighL);
      Vector3f neck = neckPivot(geometryOf(byJoint, 8), hip, in.modelId(), warn);
      Vector3f chest = midpoint(hip, neck);
      Vector3f kneeR = segmentPivot(geometry, 2, thighR != null ? thighR : hip);
      Vector3f kneeL = segmentPivot(geometry, 5, thighL != null ? thighL : hip);
      Vector3f shoulderR = segmentPivot(geometry, 11, chest != null ? chest : hip);
      Vector3f shoulderL = segmentPivot(geometry, 16, chest != null ? chest : hip);
      Vector3f elbowR = segmentPivot(geometry, 12, shoulderR);
      Vector3f elbowL = segmentPivot(geometry, 17, shoulderL);
      Vector3f wristR = handPivot(in, geometry.byBone(), 12);
      Vector3f wristL = handPivot(in, geometry.byBone(), 17);
      Vector3f fistR = geometricFist(geometry.byBone(), in, 12, elbowR);
      Vector3f fistL = geometricFist(geometry.byBone(), in, 17, elbowL);
      Map<Integer, Vector3f> pivots = new HashMap<>();
      put(pivots, 0, hip);
      put(pivots, 7, hip);
      put(pivots, 8, chest);
      put(pivots, 9, neck);
      put(pivots, 1, thighR);
      put(pivots, 4, thighL);
      put(pivots, 2, kneeR);
      put(pivots, 5, kneeL);
      put(pivots, 3, kneeR);
      put(pivots, 6, kneeL);
      put(pivots, 11, shoulderR);
      put(pivots, 16, shoulderL);
      put(pivots, 12, elbowR);
      put(pivots, 17, elbowL);
      put(pivots, 10, shoulderR);
      put(pivots, 15, shoulderL);
      put(pivots, 14, elbowR);
      put(pivots, 19, elbowL);
      put(pivots, 13, fistR != null ? fistR : (wristR != null ? wristR : elbowR));
      put(pivots, 18, fistL != null ? fistL : (wristL != null ? wristL : elbowL));
      if (pivots.isEmpty()) {
         warn.accept(noPivotWarning(in, geometry));
      }

      return new com.ysmef.compat.model.runtime.YsmBindArmature.BindPivots(pivots, wristR, wristL, fistR, fistL);
   }

   /**
    * WARN #2: a model from which no geometry-derived pivot could be read at all.
    *
    * <p>This is the guard that makes the {@code [bind] ...=null} line impossible to reach quietly.
    * Every null pivot means "keep the reference biped's joint" in Epic Fight - the detachment this
    * whole class exists to prevent - so the one state that used to be silent (a model whose filter
    * found nothing) now names the model and reports what the filter saw: how many bones, how many
    * mesh parts, how many bones with usable geometry.
    */
   private static String noPivotWarning(
      com.ysmef.compat.model.runtime.YsmBindArmature.GeometryInput in, com.ysmef.compat.model.runtime.YsmBindArmature.GeometryData geometry
   ) {
      return String.format(
         "YSM-EF Compat: [bind] model='%s' produced NO geometry-derived pivot (bones=%d, mesh parts=%d, bones with usable geometry=%d): every joint keeps the reference biped's pivot, so this model's limbs rotate about origins its own geometry does not have",
         in.modelId(),
         in.boneNames().length,
         in.parts().size(),
         geometry.byBone().size()
      );
   }

   private static void put(Map<Integer, Vector3f> pivots, int joint, Vector3f pos) {
      if (pos != null) {
         pivots.put(joint, pos);
      }
   }

   /**
    * The pivot of a limb joint: the top of its own settled geometry, <b>unless</b> the joint's
    * directly-mapped segment geometry reaches further up towards the joint's parent.
    *
    * <p>The criterion is a comparison of two candidate tops, measured against the parent's pivot
    * rather than against anything absolute: the candidate whose top ring sits closer to the parent
    * wins, and the settled geometry is the fallback when the joint has no mapped segment geometry at
    * all. That is the shipped shape - {@code RightLowerLeg2} (the shin that reaches the hip) beating
    * {@code ysmGlowxiegen} (an ankle decal the strict tier preferred) on Nahobino, and losing to a
    * spare part parked above the hips on the models that author their second form that way. The
    * parent is the joint's <i>staged</i> parent - the arm's pivot for an elbow, not the chest's -
    * because the answer differs between the two and only one of them is the joint the limb hangs
    * from. {@code YsmBindArmatureSegmentPivotTest} pins all four cases.
    */
   private static Vector3f segmentPivot(com.ysmef.compat.model.runtime.YsmBindArmature.GeometryData geometry, int joint, Vector3f parent) {
      Vector3f chosen = topOf(geometryOf(geometry.byJoint(), joint));
      List<Vector3f> segment = geometry.segmentByJoint().get(joint);
      if (segment != null && !segment.isEmpty()) {
         Vector3f candidate = topOf(segment);
         if (chosen != null && parent != null) {
            return Math.abs(candidate.y - parent.y) < Math.abs(chosen.y - parent.y) ? candidate : chosen;
         } else {
            return chosen == null ? candidate : chosen;
         }
      } else {
         return chosen;
      }
   }

   /**
    * The mean of the vertices within {@link #TOP_RING_EPSILON} of a set's highest one - "the top of
    * this geometry", and the measurement every joint pivot above is read from.
    *
    * <p>A band rather than the single highest vertex, because a ring of geometry has one vertex per
    * corner and the top face of a box is four of them: averaging the band gives the middle of the
    * top surface whatever the model's tessellation, which is what a joint should rotate about. The
    * band is also why an ornament can move a pivot so far - see {@link #neckPivot}, where the band is
    * one corner of a strip rather than a collar.
    */
   private static Vector3f topOf(List<Vector3f> vertices) {
      if (vertices != null && !vertices.isEmpty()) {
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

         return n == 0 ? new Vector3f((Vector3fc)vertices.get(0)) : acc.div((float)n);
      } else {
         return null;
      }
   }

   /**
    * The Head joint's pivot - the neck - from the geometry bound to the Chest joint: the mean of
    * the chest geometry's top ring, <b>unless that ring is an ornament rather than the body's own
    * collar</b>, in which case the ring's height is kept and its horizontal position comes from the
    * body's own vertical axis.
    *
    * <h2>What the plain ring mean does when the top of the chest is a decoration</h2>
    *
    * <p>{@code topOf} was the whole rule, and on {@code STRESSTEST_SMT_Nahobino} it puts the Head
    * pivot at <b>(0.121, 1.553, -0.069)</b>: 0.138 blocks off the model's own axis (the author's
    * {@code AllHead2} pivot is {@code (0.000, 1.540, 0.000)}), which is where a 30-degree head turn
    * swings the base of the skull about 0.11 blocks and tears the head off the body. The reason is
    * that the Chest joint's settled geometry on that model is a thin emissive strip (the
    * {@code ysmGlowbody}/{@code ysmGlowback} layer, 2160 vertex slots, x -0.123..0.156, z
    * -0.096..0.092), and its highest point is the top corner of that strip: 27 of the 2160 slots lie
    * within {@link #TOP_RING_EPSILON} of y=1.569, all of them between x 0.089..0.152 and z
    * -0.096..0.092. Their mean is a point on the strip's +x edge, not the centre of a collar. The
    * <i>height</i> is not wrong: 1.569 sits between the authored {@code AllHead2} pivot (1.540) and
    * the base of the visible head mesh (1.626) - only the horizontal position is.
    *
    * <h2>The rule, and why it is gated rather than always applied</h2>
    *
    * <p>Two general replacements were measured over the model corpus and rejected, and this method
    * exists in the shape it does because of them: taking the horizontal position from the chest
    * geometry's bounding-box centre moves 458 of 554 models (mean 0.082, worst 1.673 blocks,
    * {@code HSR_Tribbie(maid)}), and taking it from the closest approach between the Chest- and
    * Head-bound geometry moves 700 of 703 (mean 0.196, worst 1.676). Both are too blunt: the ring
    * mean is right on the overwhelming majority of models, and it is only wrong where the ring is
    * not a ring at all. So the replacement is <b>gated on the ring being an outlier</b>:
    *
    * <ul>
    *   <li><b>It holds few slots</b> - no more than {@link #ORNAMENT_RING_FRACTION} of the joint's
    *       settled geometry. A collar is a band: it carries a real share of the chest's vertices. An
    *       ornament is a corner: on Nahobino 27 of 2160 slots, 1.25 per cent.</li>
    *   <li><b>And it sits off the body's axis</b> - at least {@link #ORNAMENT_RING_OFFSET} blocks
    *       horizontally from the axis this method is given. This is the condition that does the
    *       work: a small top ring is <i>common</i> (the flat top face of a box torso is four slots),
    *       but a small top ring that is also 0.05 blocks to one side of the body's axis is not a
    *       collar. On Nahobino the offset is 0.111 blocks.</li>
    * </ul>
    *
    * <p>The axis comes from the caller as the <b>hip</b> - the midpoint of the two thigh joints'
    * geometry-derived tops - and that choice is deliberate: it is the one horizontal reference in
    * this computation that is measured from <i>other</i> geometry, so it cannot inherit the error it
    * is being used to detect. The chest geometry's own centre of mass is <i>not</i> usable here for
    * exactly that reason: on Nahobino the settled set is the one-sided glow strip, whose centre of
    * mass is (0.081, -0.031) - only 0.055 blocks from the ornament it is supposed to expose, and
    * 0.081 blocks off the model's axis, so correcting to it would leave most of the error in place.
    * The hips are built from both legs, so a one-sided leg ornament largely cancels; when it does
    * not, the offset test fails and this method leaves the ring mean alone (which is the safe
    * direction: an unchanged pivot is the shipped behaviour).
    *
    * <p><b>Calibration</b> ({@code DefectCalibrationCorpusSweepTest}, report
    * {@code build/reports/ysm-neck-ornament-corpus.md}, 730 parseable packages, 708 with Chest
    * geometry, 701 of those with an authored {@code AllHead}/{@code MHead}/{@code Head} control to
    * compare against). With the gates above and the hip as the axis - and with the correction capped
    * at {@link #ORNAMENT_RING_MAX_OFFSET}, which is what excludes the two shapes where the hip itself
    * is the outlier - the rule moves <b>10 models</b>, by 0.070 to 0.238 blocks, and <b>every one of
    * them ends closer to its own authored head control</b> than it started (mean distance 0.012
    * blocks afterwards, against 0.055 for the shipped rule over all 701). The same gate with the
    * chest geometry's own centre of mass as the axis moves 13 - 16 models of which 7 - 8 end
    * <i>further</i> from the authored control, which is why the hip is the axis. Loosening the ring
    * share to five per cent keeps all ten and adds three more that get worse; the two blunt rules
    * below were rejected outright for the same reason, at a far larger scale.
    *
    * @param chestGeometry the vertices settled on the Chest joint (tier-filtered, see
    *                      {@link #collectGeometry}); may be null or empty
    * @param bodyAxis      the horizontal axis to fall back to, or null; only its x and z are read
    * @param modelId       the model, for the warning
    * @param warn          receives the warning when the ring was read as an ornament
    */
   static Vector3f neckPivot(List<Vector3f> chestGeometry, Vector3f bodyAxis, String modelId, Consumer<String> warn) {
      Vector3f ring = topOf(chestGeometry);
      if (ring == null || bodyAxis == null || chestGeometry == null || chestGeometry.isEmpty()) {
         return ring;
      }

      float maxY = -Float.MAX_VALUE;

      for (Vector3f v : chestGeometry) {
         maxY = Math.max(maxY, v.y);
      }

      int ringSlots = 0;

      for (Vector3f v : chestGeometry) {
         if (v.y >= maxY - TOP_RING_EPSILON) {
            ringSlots++;
         }
      }

      float offset = (float)Math.hypot(ring.x - bodyAxis.x, ring.z - bodyAxis.z);
      if (ringSlots > (int)(chestGeometry.size() * ORNAMENT_RING_FRACTION)
              || offset < ORNAMENT_RING_MIN_OFFSET
              || offset > ORNAMENT_RING_MAX_OFFSET
              || ringAtRim(chestGeometry, ring) < ORNAMENT_RING_MIN_RIM) {
         return ring;
      }

      if (warn != null) {
         warn.accept(String.format(
            "YSM-EF Compat: [bind] model='%s' has an ornament for the top of its chest geometry: %d of %d vertex slots within %.2f of y=%.3f, centred (%.3f,%.3f) = %.3f blocks off the body's own axis, so the Head pivot's height is kept and its horizontal position comes from the body axis (%.3f,%.3f) instead of the ornament",
            modelId,
            ringSlots,
            chestGeometry.size(),
            TOP_RING_EPSILON,
            maxY,
            ring.x,
            ring.z,
            offset,
            bodyAxis.x,
            bodyAxis.z
         ));
      }

      return new Vector3f(bodyAxis.x, ring.y, bodyAxis.z);
   }

   /**
    * How far the top ring sits from the centre of the chest geometry's own x footprint, as a share of
    * that footprint's half-width: 0 means the ring is centred on the geometry it was read from, 1
    * means it is out at the edge of it. Zero when the geometry has no width to speak of.
    *
    * <p>The condition is on x only, and that is deliberate: x is the model's mirror axis - the one
    * direction in which "the middle of the body" is a fact about the model rather than a guess - while
    * a torso is legitimately deeper at the front than at the back, so its z centre moves with the pose
    * the author modelled and means nothing on its own.
    */
   private static float ringAtRim(List<Vector3f> chestGeometry, Vector3f ring) {
      float minX = Float.MAX_VALUE;
      float maxX = -Float.MAX_VALUE;

      for (Vector3f v : chestGeometry) {
         minX = Math.min(minX, v.x);
         maxX = Math.max(maxX, v.x);
      }

      float halfWidth = (maxX - minX) * 0.5F;
      return halfWidth > 1.0E-5F ? Math.abs(ring.x - (minX + maxX) * 0.5F) / halfWidth : 0.0F;
   }

   /**
    * How far the top ring's own x may sit from the centre of the chest geometry's x footprint, as a
    * share of that footprint's half-width, and still be read as a collar rather than as a decoration
    * at the geometry's edge.
    *
    * <p>This is the condition that carries the rule, and it was chosen after the other two were
    * measured: on the shipped Nahobino the ring's mean x is 0.121 while the settled geometry's own x
    * footprint is -0.123..0.156 (a 0.14 half-width centred on 0.017), so the ring sits at 0.75 of the
    * way out to the edge, while a real collar is centred on its own footprint and measures near zero.
    * A ring that holds a large share of the vertices can still be an ornament - Nahobino's holds 432
    * of 2160 - which is why the share gate alone could not separate this case.
    */
   private static final float ORNAMENT_RING_MIN_RIM = 0.5F;

   /**
    * How large a share of a joint's settled geometry its top ring may hold and still be read as an
    * ornament rather than as the body's own collar.
    *
    * <p>Twenty-five per cent: the shipped Nahobino's ring is 432 of 2160 slots (20 per cent), and a
    * collar that is a real share of the chest is larger than that. This gate is the loosest of the
    * three on purpose - the rim and axis conditions are what identify an ornament, and this one only
    * keeps a model whose top ring holds most of its chest geometry (a cape over a bare torso, say)
    * out of the rule.
    */
   private static final float ORNAMENT_RING_FRACTION = 0.25F;

   /**
    * How far a top ring must sit from the body's axis, horizontally, before its distance is evidence
    * that it is not a collar. Four centimetres: the axis is measured from the hips rather than
    * derived from the chest, so the tolerance has to absorb a model whose legs are not modelled
    * symmetrically (the shipped Nahobino's hip pivot is 0.033 blocks off its own axis) while still
    * being well under the 0.138-block error this rule exists to remove.
    */
   private static final float ORNAMENT_RING_MIN_OFFSET = 0.04F;

   /**
    * The furthest a top ring may sit from the body's axis and still be corrected.
    *
    * <p>Twenty-five centimetres, and it is a guard rather than a threshold: past it the ring is not an
    * ornament beside a collar but a sign that the model's proportions or skeleton are not what this
    * rule assumes (a golem whose thigh tops are 1.4 blocks from its chest top, a model authored at a
    * scale nothing else here expects), and the honest answer then is to leave the geometry's own mean
    * alone. It also bounds the correction: no model's Head pivot can be moved more than this by the
    * rule, and the ten models it does move on the corpus move 0.070 - 0.238 blocks.
    */
   private static final float ORNAMENT_RING_MAX_OFFSET = 0.25F;

   private static Vector3f handPivot(com.ysmef.compat.model.runtime.YsmBindArmature.GeometryInput in, Map<Integer, List<Vector3f>> byBone, int joint) {
      for (Map.Entry<Integer, List<Vector3f>> entry : byBone.entrySet()) {
         if (in.boneJoints()[entry.getKey()] == joint) {
            String normalized = normalize(in.boneNames()[entry.getKey()]);
            if (HAND_BONE_NAMES.contains(normalized)) {
               return topOf(entry.getValue());
            }
         }
      }

      return null;
   }

   public static Vector3f fistPosition(String modelId, boolean leftHand) {
      Vector3f[] fists = FIST_BY_MODEL.get(modelId);
      if (fists == null) {
         return null;
      } else {
         return leftHand ? fists[1] : fists[0];
      }
   }

   private static Vector3f geometricFist(
      Map<Integer, List<Vector3f>> byBone, com.ysmef.compat.model.runtime.YsmBindArmature.GeometryInput in, int joint, Vector3f elbow
   ) {
      if (elbow == null) {
         return null;
      } else {
         Vector3f handCenter = handRegionCentroid(byBone, in, joint, elbow);
         return handCenter != null ? handCenter : farHalfCentroid(byBone, in, joint, elbow);
      }
   }

   private static Vector3f handRegionCentroid(
      Map<Integer, List<Vector3f>> byBone, com.ysmef.compat.model.runtime.YsmBindArmature.GeometryInput in, int joint, Vector3f elbow
   ) {
      Vector3f acc = new Vector3f();
      int count = 0;

      for (Map.Entry<Integer, List<Vector3f>> entry : byBone.entrySet()) {
         if (in.boneJoints()[entry.getKey()] == joint) {
            String normalized = normalize(in.boneNames()[entry.getKey()]);
            if (!isWeaponBone(normalized) && isHandBone(normalized)) {
               for (Vector3f v : entry.getValue()) {
                  acc.add(v);
                  count++;
               }
            }
         }
      }

      return count == 0 ? null : acc.div((float)count);
   }

   private static Vector3f farHalfCentroid(
      Map<Integer, List<Vector3f>> byBone, com.ysmef.compat.model.runtime.YsmBindArmature.GeometryInput in, int joint, Vector3f elbow
   ) {
      float maxDist = 0.0F;
      Map<Integer, Float> distByBone = new HashMap<>();

      for (Map.Entry<Integer, List<Vector3f>> entry : byBone.entrySet()) {
         if (in.boneJoints()[entry.getKey()] == joint) {
            String normalized = normalize(in.boneNames()[entry.getKey()]);
            if (!isWeaponBone(normalized)) {
               Vector3f centroid = centroidOf(entry.getValue());
               if (centroid != null) {
                  float dist = centroid.distance(elbow);
                  distByBone.put(entry.getKey(), dist);
                  maxDist = Math.max(maxDist, dist);
               }
            }
         }
      }

      if (maxDist <= 0.0F) {
         return null;
      } else {
         Vector3f acc = new Vector3f();
         int count = 0;

         for (Map.Entry<Integer, List<Vector3f>> entryx : byBone.entrySet()) {
            if (in.boneJoints()[entryx.getKey()] == joint) {
               String normalized = normalize(in.boneNames()[entryx.getKey()]);
               if (!isWeaponBone(normalized)) {
                  Float dist = distByBone.get(entryx.getKey());
                  if (dist != null && !(dist < maxDist * 0.5F)) {
                     for (Vector3f v : entryx.getValue()) {
                        acc.add(v);
                        count++;
                     }
                  }
               }
            }
         }

         return count == 0 ? null : acc.div((float)count);
      }
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

   private static Vector3f centroidOf(List<Vector3f> vertices) {
      if (vertices != null && !vertices.isEmpty()) {
         Vector3f acc = new Vector3f();

         for (Vector3f v : vertices) {
            acc.add(v);
         }

         return acc.div((float)vertices.size());
      } else {
         return null;
      }
   }

   private static String normalize(String boneName) {
      return YSMJointMapper.normalize(boneName);
   }

   private static Vector3f midpoint(Vector3f a, Vector3f b) {
      if (a == null) {
         return b == null ? null : new Vector3f(b);
      } else {
         return b == null ? new Vector3f(a) : new Vector3f((a.x + b.x) * 0.5F, (a.y + b.y) * 0.5F, (a.z + b.z) * 0.5F);
      }
   }

   private static Joint copyHierarchy(Joint refJoint, OpenMatrix4f newParentWorld, Map<Integer, OpenMatrix4f> pivots, Map<String, Joint> out, boolean root) {
      OpenMatrix4f refLocal = refJoint.getLocalTransform();
      OpenMatrix4f newLocal = new OpenMatrix4f(refLocal);
      OpenMatrix4f pivot = pivots.get(refJoint.getId());
      if (pivot != null) {
         if (root) {
            newLocal.m30 = pivot.m30;
            newLocal.m31 = pivot.m31;
            newLocal.m32 = pivot.m32;
         } else {
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
         joint.addSubJoints(new Joint[]{copyHierarchy(child, newWorld, pivots, out, false)});
      }

      return joint;
   }

   /**
    * The joints whose pivots are derived from this model's geometry, and the wrist/fist points the
    * rest of the mod reads back.
    *
    * <p>The map is keyed by Epic Fight's joint id and is sparse by design: a joint with no entry
    * keeps the reference biped's pivot, and {@code build} is what turns the entries into matrices. The
    * wrist and fist points travel beside the map because they are consumed by other systems - the
    * item renderer and the physics - that need the point, not the joint.
    */
   static record BindPivots(Map<Integer, Vector3f> byJoint, Vector3f wristR, Vector3f wristL, Vector3f fistR, Vector3f fistL) {
   }

   static record BoneGeometry(int boneIndex, List<Vector3f> vertices) {
   }

   private static record Entry(com.ysmef.compat.model.runtime.YSMRuntimeModel runtime, HumanoidArmature armature) {
   }

   /**
    * The filter's output: one vertex list per joint (the settled set the pivots are read from), one
    * per bone (for the hand and fist searches), the mapped-only segment geometry per joint (which
    * {@link #segmentPivot} prefers), and the {@link Relaxation} record when any joint had to relax.
    */
   static record GeometryData(
      Map<Integer, List<Vector3f>> byJoint,
      Map<Integer, List<Vector3f>> byBone,
      Map<Integer, List<Vector3f>> segmentByJoint,
      com.ysmef.compat.model.runtime.YsmBindArmature.Relaxation relaxation
   ) {
   }

   /**
    * The filter's input, assembled from the runtime bone table and the converted mesh by
    * {@link #inputOf}: parallel arrays indexed by bone, the set of bones the model's default form
    * hides, and one {@link BoneGeometry} per mesh part that belongs to a bone.
    */
   static record GeometryInput(
      String modelId,
      String[] boneNames,
      int[] boneJoints,
      boolean[] boneMapped,
      Set<String> hiddenBones,
      List<com.ysmef.compat.model.runtime.YsmBindArmature.BoneGeometry> parts
   ) {
   }

   /**
    * Which joints needed a relaxed tier, and which bones were taken back for each.
    *
    * <p>Data rather than only a log line: {@code YsmBindArmatureTest} asserts it, and a caller that
    * wants to explain a model's pivots to a user has the reason without re-running the filter.
    */
   static record Relaxation(Map<Integer, Integer> tierByJoint, Map<Integer, List<String>> bonesByJoint) {
   }
}
