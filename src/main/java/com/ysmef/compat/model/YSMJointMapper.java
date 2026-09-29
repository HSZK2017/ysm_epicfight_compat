package com.ysmef.compat.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps YSM bone names to Epic Fight biped armature joints, without needing a
 * runtime Armature instance (the biped armature layout is fixed).
 *
 * Joint ids follow the order of the "joints" array in Epic Fight's
 * assets/epicfight/animmodels/entity/biped.json:
 * Root, Thigh_R, Leg_R, Knee_R, Thigh_L, Leg_L, Knee_L, Torso, Chest, Head,
 * Shoulder_R, Arm_R, Hand_R, Tool_R, Elbow_R, Shoulder_L, Arm_L, Hand_L, Tool_L, Elbow_L.
 */
public final class YSMJointMapper {

    private static final Map<String, Integer> JOINT_IDS = createJointIds();
    private static final Map<String, String> STANDARD_MAPPING = createMapping();

    private static Map<String, Integer> createJointIds() {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < JointTable.COUNT; i++) {
            map.put(JointTable.NAMES[i], i);
        }
        return map;
    }

    private static Map<String, String> createMapping() {
        Map<String, String> map = new HashMap<>();
        map.put("root", "Root");
        map.put("allbody", "Torso");
        map.put("body", "Torso");
        map.put("waist", "Torso");
        map.put("torso", "Torso");
        map.put("downbody", "Torso");
        map.put("hip", "Torso");
        map.put("hips", "Torso");
        map.put("pelvis", "Torso");
        map.put("skirt", "Torso");
        map.put("upbody", "Chest");
        map.put("upperbody", "Chest");
        map.put("chest", "Chest");
        map.put("breast", "Chest");
        map.put("boob", "Chest");
        map.put("collar", "Chest");
        map.put("backpack", "Chest");
        map.put("cape", "Chest");
        map.put("elytra", "Chest");
        map.put("elytralocator", "Chest");
        map.put("arm", "Chest");
        map.put("leg", "Torso");
        map.put("center", "Root");

        map.put("allhead", "Head");
        map.put("head", "Head");

        map.put("leftarm", "Arm_L");
        map.put("armleft", "Arm_L");
        map.put("rightarm", "Arm_R");
        map.put("armright", "Arm_R");

        map.put("leftforearm", "Hand_L");
        map.put("forearmleft", "Hand_L");
        map.put("lefthand", "Hand_L");
        map.put("handleft", "Hand_L");
        map.put("rightforearm", "Hand_R");
        map.put("forearmright", "Hand_R");
        map.put("righthand", "Hand_R");
        map.put("handright", "Hand_R");

        map.put("lefthandlocator", "Tool_L");
        map.put("leftitem", "Tool_L");
        map.put("itemleft", "Tool_L");
        map.put("righthandlocator", "Tool_R");
        map.put("rightitem", "Tool_R");
        map.put("itemright", "Tool_R");

        map.put("leftleg", "Thigh_L");
        map.put("legleft", "Thigh_L");
        map.put("rightleg", "Thigh_R");
        map.put("legright", "Thigh_R");

        map.put("leftlowerleg", "Leg_L");
        map.put("lowerlegleft", "Leg_L");
        map.put("leftcalf", "Leg_L");
        map.put("leftfoot", "Leg_L");
        map.put("footleft", "Leg_L");
        map.put("rightlowerleg", "Leg_R");
        map.put("lowerlegright", "Leg_R");
        map.put("rightcalf", "Leg_R");
        map.put("rightfoot", "Leg_R");
        map.put("footright", "Leg_R");
        return map;
    }

    private YSMJointMapper() {}

    /**
     * Resolve the EF joint id for a YSM bone, walking up the bone hierarchy when
     * the bone name itself is not a standard body part.
     */
    public static int resolveJointId(YSMGeoModel.Bone bone) {
        YSMGeoModel.Bone source = mappedAncestor(bone);
        if (source == null) {
            return JOINT_IDS.get("Root");
        }
        return JOINT_IDS.get(STANDARD_MAPPING.get(normalize(source.name)));
    }

    /** Epic Fight's Chest joint, the one cloth hanging below the waist must not bind to. */
    static final int JOINT_CHEST = 8;
    /** Epic Fight's Torso joint: the waist and hips, where a skirt actually hangs from. */
    public static final int JOINT_TORSO = 7;

    /**
     * The joint a bone's <b>geometry</b> belongs to, judged by where the geometry is and not only
     * by what its ancestors happen to be called.
     *
     * <p>Two measured rules run here, both of them for the same reason: this mod binds every vertex
     * rigidly to <b>one</b> Epic Fight joint, so a joint the geometry does not belong to tears the
     * piece off its own body part the moment that part moves, and neither the name walk nor the
     * ancestor a name was inherited from can be trusted to know where the geometry actually is.
     *
     * <h2>1. A limb's side is where its geometry is</h2>
     *
     * <p>YSM rigs are authored by mirroring a template, and the copy's names do not have to be
     * renamed - or they are renamed by hand and the geometry is not. Both directions are in the
     * wild. On the shipped Nahobino, the <i>whole foot</i> of the left leg is named
     * {@code RightFoot3}, and the same bone name repeats on five bodies:
     *
     * <pre>
     *   RightFoot3  [Leg_R]  ... LeftLeg2 -> LeftLowerLeg2 -> LeftFoot2 -> RightFoot3
     *   bone73      [Leg_R]  ... LeftFoot2 -> RightFoot3 -> bone73
     *   ysmGlowxiegen [Leg_R] x = -0.155
     * </pre>
     *
     * <p>Every one of them sits at x = -0.15, i.e. on the <b>left</b>, inside the left leg's own
     * chain, while the name walk puts them on the right shin: 4920 of that model's 126592 vertices
     * follow the wrong leg, and a shoe that follows the other leg is exactly the reported "leg comes
     * apart from the body". The user's own model has the mirror-image case (a left-named foot bone
     * inside the right leg, carrying the right shoe).
     *
     * <p>So the side of a limb joint is decided by the geometry of the bone that <i>supplied</i> the
     * joint (its own cube centroid, or its pivot when it carries no cubes of its own), and by the
     * model's own side convention rather than a global assumption: the convention is the direction
     * the model's own directly-mapped right/left limb geometry overwhelmingly points to, so a model
     * that is mirrored as a whole keeps its binding exactly as it is - only the bones that
     * contradict <i>their own</i> model are corrected. Bones within
     * {@link #SIDE_MIDLINE_FRACTION} of the model's own limb scale of the midline are not judged at
     * all: geometry that sits on the centre line (a sash, a tail, a crossing strap) has no side to
     * be wrong about.
     *
     * <h2>2. Garment: cloth at the hips belongs to the lower body</h2>
     *
     * <p>A garment container is wherever the author put it in the rig, and the rig does not have
     * to agree with the anatomy. On the maid model this mod is built from, every one of its
     * twenty-four skirt panels descends from {@code clothe -> UpBody}, and {@code UpBody} is the
     * chest:
     *
     * <pre>
     *   FM1 -> FM -> FrontClothe -> clothe -> UpBody [Chest]
     *   RM  -> RightClothe -> clothe -> UpBody [Chest]
     * </pre>
     *
     * <p>So the whole skirt was skinned to the <b>upper body</b>. YSM itself does not mind - it
     * animates {@code clothe} directly, so the skirt follows whatever the author's animation does
     * - but once the container's animation is gone the skirt simply rides the chest. In combat that
     * is exactly what it looks like: Epic Fight's attacks twist the chest hard, and a skirt bolted
     * to the chest is thrown away from the hips on every swing.
     *
     * <p>The rule is therefore <b>hip-relative</b>: cloth whose geometry sits at or below the model's
     * own hip - plus the waist thickness a skirt band legitimately covers - belongs to the lower
     * body. The hip is read from the model's own mapped thigh bones (the same measurement
     * {@code YsmBindArmature} uses for its pivots), in the same model space as the geometry, so it
     * holds for any proportions.
     *
     * <p>What is deliberately <b>not</b> the rule is a list of container names: {@code clothe} is
     * the name this model uses; the next model calls it {@code qunzi} or {@code SkirtGroup} or
     * nothing at all.
     *
     * <p><b>What the hip reference fixed.</b> The reference used to be the mapped ancestor's own
     * geometry height, which is a different quantity: on the shipped models it redirected 76 of
     * Lilith's bones (10104 vertices, 7.5%) and 20 of the user's model (4272 vertices, 9.1%) to the
     * hips, and among them geometry sitting in the chest band - breasts, collars, a cloak, the upper
     * chest of five clone bodies - because those pieces hang from a {@code Chest}-mapped container
     * that happens to be authored low. Chest geometry on the hips is the same defect mirrored: Epic
     * Fight's arms and head are children of the Chest joint ({@code Shoulder_*} and {@code Head} hang
     * from it), so a breast plate that stays with the hips while the arm swings away from it is the
     * reported "the arm joint separates from the torso". The hip says where the geometry actually is;
     * an ancestor's height says only where its container was authored.
     *
     * @param model the model the bone belongs to, or null to keep the plain name walk
     */
    public static int resolveJointId(YSMGeoModel.Bone bone, YSMGeoModel model) {
        YSMGeoModel.Bone source = mappedAncestor(bone);
        int joint = source == null ? JOINT_IDS.get("Root") : JOINT_IDS.get(STANDARD_MAPPING.get(normalize(source.name)));
        if (model == null) {
            return joint;
        }

        joint = limbSideFromGeometry(source, joint, model);

        if (joint != JOINT_CHEST) {
            return joint;
        }
        if (isDirectlyMapped(bone)) {
            // The bone is a body part itself, named as one; its joint is the author's word and not
            // an inheritance to be second-guessed. This is what keeps UpBody, UpperBody, Elytra and
            // the arm bones where they belong - the rule exists only for cloth that picked a joint
            // up from an ancestor on its way down the hierarchy.
            return joint;
        }
        Geometry geometry = geometryOf(model);
        float height = geometry.heightOf(bone);
        if (!Float.isFinite(height)) {
            return joint;
        }
        float hip = geometry.hipHeight;
        if (Float.isFinite(hip)) {
            // The allowance is a fraction of this model's own thigh: a pelvis is proportionally the
            // same size whatever the model's build, and a constant number of blocks is not.
            float allowance = Float.isFinite(geometry.thighLength)
                    ? GARMENT_ABOVE_HIP_FRACTION * geometry.thighLength
                    : GARMENT_ABOVE_MARGIN;
            return height <= hip + allowance ? JOINT_TORSO : joint;
        }
        // No thigh bone this mapper can name, so the hip cannot be measured on this model; the
        // ancestor-relative comparison is what the rule did before and stays as the fallback rather
        // than leaving such a model's cloth on the chest.
        if (source == null) {
            return joint;
        }
        float anchorHeight = geometry.heightOf(source);
        if (!Float.isFinite(anchorHeight)) {
            return joint;
        }
        return height <= anchorHeight + GARMENT_ABOVE_MARGIN ? JOINT_TORSO : joint;
    }

    /**
     * How far above the model's own hip a piece of cloth may sit and still count as lower-body
     * cloth, as a fraction of the model's own thigh length.
     *
     * <p>The allowance is the waist: a skirt's own top band is rigged at the waist, which sits above
     * the hip joint by the thickness of the pelvis. Measured on the fixtures this rule was built
     * against, the maid's skirt panels are centred 0.11 blocks above its hips with a thigh of 0.48
     * blocks - 0.23 thigh lengths - and no threshold can separate "the top band of a skirt" from "a
     * garment hanging from the chest" by a clear drop alone. Demanding one would leave that top band
     * and every container on the chest while the lower panels moved, which is a skirt torn in half
     * rather than a skirt fixed.
     *
     * <p>Half a thigh is the allowance because that is what the reference biped's own regions
     * support: Epic Fight's Torso geometry occupies the hip plus 0.96 thigh lengths, and its Chest
     * region starts 1.30 thigh lengths above the hip, so everything this allowance keeps on the hips
     * is in the pelvis/waist half of the torso box, while the geometry this rule is there to move
     * back to the chest - a breast plate at 0.96 thigh lengths above the hip, a collar band at 0.96
     * to 1.38 - is plainly outside it.
     */
    private static final float GARMENT_ABOVE_HIP_FRACTION = 0.5F;

    /**
     * The allowance used when the model's own thigh length cannot be measured, and by the
     * ancestor-relative fallback. Kept at the value the rule shipped with so a model this mapper
     * cannot measure is not also re-tuned.
     */
    private static final float GARMENT_ABOVE_MARGIN = 0.25F;

    private static final float SIDE_MIDLINE_FRACTION = 0.5F;

    /**
     * Everything the model-aware rules measure on a model, in one pass: the hip height, the lateral
     * scale of each left/right limb pair, the model's side convention and its overall limb scale.
     *
     * <p>Measured once and cached per model instance, because {@code resolveJointId(bone, model)} is
     * called once per bone by both readers of the decision (the mesh bake and the runtime bone table)
     * and each rule is a function of the whole skeleton. The model is immutable after parsing -
     * conversion only reads it - so one measurement describes every call.
     */
    private static final class Geometry {
        final float hipHeight;
        /** Hip minus knee, i.e. the model's own thigh; NaN when the knee cannot be measured. */
        final float thighLength;
        final float limbUnit;
        final float sideConvention;
        /** Median |x| of the geometry each left/right joint pair owns, indexed by joint id. */
        final float[] pairScale = new float[JointTable.COUNT];
        /** Per bone: its own cubes in model space, as {sumX, sumY, maxY, count}. */
        final java.util.IdentityHashMap<YSMGeoModel.Bone, float[]> own = new java.util.IdentityHashMap<>();
        /** Per bone: its whole subtree's cubes in model space, as {sumX, sumY, count}. */
        final java.util.IdentityHashMap<YSMGeoModel.Bone, float[]> subtree = new java.util.IdentityHashMap<>();
        /** Per bone: where its pivot lands in model space, as {x, y}. */
        final java.util.IdentityHashMap<YSMGeoModel.Bone, float[]> pivot = new java.util.IdentityHashMap<>();
        /**
         * The alternate-form bones ("RightLeg2" beside "RightLeg"). They are measured like every
         * other bone - a rule asked about one of them still has to see its geometry - but they are
         * excluded from the model-level aggregates (hip, knee, limb scale, side vote), because a
         * variant form is authored at the base form's position or in a different pose and would move
         * them. Leaving them out of the measurement instead is what a first cut of this did, and the
         * shipped Lilith proved it wrong: her cape pieces are named {@code bone198}, {@code bone206}
         * ... beside a bone called {@code bone}, so the whole cape was measured by its pivots and half
         * of it landed on the hips.
         */
        final java.util.Set<YSMGeoModel.Bone> variant =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        Geometry(float hipHeight, float thighLength, float limbUnit, float sideConvention) {
            this.hipHeight = hipHeight;
            this.thighLength = thighLength;
            this.limbUnit = limbUnit;
            this.sideConvention = sideConvention;
            java.util.Arrays.fill(this.pairScale, Float.NaN);
        }

        float pairScaleOf(int joint) {
            // Both sides of a pair share one measurement, and it is stored under the pair's key, so
            // a lookup by either side's joint id has to go through the same key. (Looking up the
            // joint id directly reads an untouched slot and silently judges every bone.)
            float scale = pairScale[pairKey(joint)];
            if (Float.isFinite(scale)) {
                return scale;
            }
            // A pair with no geometry of its own (a model that names no hand bone, say) falls back to
            // the model's overall limb scale.
            return limbUnit;
        }

        /**
         * The bone's lateral position in <b>model space</b>: the mean x of its own cubes; for a bone
         * with no cubes of its own, the mean x of the cubes anywhere below it, but only when that
         * agrees with the side the bone itself sits on; otherwise its pivot. NaN when the model has
         * nothing to measure.
         *
         * <p>Model space, not the authored cube coordinates: a rig may place a limb with its parents'
         * rotations (the shipped plague doctor and Gojo give both hands the <i>same</i> authored pivot
         * x and let the mirrored arm chain put them on opposite sides), and then the authored number
         * says nothing about which side of the body the geometry is on.
         *
         * <p>And a container whose contents are on the other side of its own pivot is holding
         * something that crosses the body - the shipped Jane Doe's left forearm carries an arrow aimed
         * across her chest - so neither number describes the limb and no side is claimed. Correcting
         * the forearm there would have put her <i>arm</i> on the wrong hand to make room for a prop.
         */
        float lateralOf(YSMGeoModel.Bone bone) {
            float[] ownSum = own.get(bone);
            if (ownSum != null && ownSum[3] > 0.0F) {
                return ownSum[0] / ownSum[3];
            }
            float[] pivotPoint = pivot.get(bone);
            float[] subtreeSum = subtree.get(bone);
            if (subtreeSum != null && subtreeSum[2] > 0.0F && pivotPoint != null) {
                float contents = subtreeSum[0] / subtreeSum[2];
                return contents * pivotPoint[0] > 0.0F ? contents : Float.NaN;
            }
            return pivotPoint == null ? Float.NaN : pivotPoint[0];
        }

        /** The bone's own geometry height in model space, or its pivot height when it has none. */
        float heightOf(YSMGeoModel.Bone bone) {
            float[] ownSum = own.get(bone);
            if (ownSum != null && ownSum[3] > 0.0F) {
                return ownSum[1] / ownSum[3];
            }
            float[] pivotPoint = pivot.get(bone);
            return pivotPoint == null ? Float.NaN : pivotPoint[1];
        }

        /** The highest corner of the bone's own geometry in model space, or its pivot height. */
        float topOf(YSMGeoModel.Bone bone) {
            float[] ownSum = own.get(bone);
            if (ownSum != null && ownSum[3] > 0.0F) {
                return ownSum[2];
            }
            float[] pivotPoint = pivot.get(bone);
            return pivotPoint == null ? Float.NaN : pivotPoint[1];
        }
    }

    /** model instance -> its measured geometry. Weak keys: a converted model is dropped with its mesh. */
    private static final java.util.Map<YSMGeoModel, Geometry> GEOMETRY =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static Geometry geometryOf(YSMGeoModel model) {
        Geometry cached = GEOMETRY.get(model);
        if (cached != null) {
            return cached;
        }
        Geometry measured = measure(model);
        GEOMETRY.put(model, measured);
        return measured;
    }

    private static Geometry measure(YSMGeoModel model) {
        // Alternate-form bones ("RightLeg2" beside "RightLeg") carry a variant form's geometry,
        // authored at the base form's position or in a different pose entirely, and the maid fixture
        // is the proof that they matter: its second-form leg bones sit a third of a block lower than
        // the primary ones, and averaging both put the model's hip 0.3 blocks below its own hips -
        // which then dragged the whole skirt back onto the chest. Judged against the model's own
        // names (BoneAlternateForms), never by "ends in a digit", so a skeleton that simply numbers
        // its bones keeps every one of them.
        java.util.Set<String> alternateForms = BoneAlternateForms.baseFormsPresent(
                model.bonesByName.keySet().toArray(new String[0]));

        Geometry geometry = new Geometry(Float.NaN, Float.NaN, Float.NaN, 1.0F);
        java.util.List<YSMGeoModel.Bone> order = new java.util.ArrayList<>();
        for (YSMGeoModel.Bone root : model.topLevelBones) {
            collectGeometry(root, new org.joml.Matrix4f(), alternateForms, geometry, order);
        }
        // Children before parents, so one pass accumulates every subtree into its ancestors.
        for (int i = order.size() - 1; i >= 0; i--) {
            YSMGeoModel.Bone bone = order.get(i);
            if (bone.parent == null) {
                continue;
            }
            float[] child = geometry.subtree.get(bone);
            if (child == null || child[2] <= 0.0F) {
                continue;
            }
            float[] parent = geometry.subtree.computeIfAbsent(bone.parent, key -> new float[3]);
            parent[0] += child[0];
            parent[1] += child[1];
            parent[2] += child[2];
        }

        Map<Integer, java.util.List<Float>> pairValues = new HashMap<>();
        java.util.List<Float> limbValues = new java.util.ArrayList<>();
        double vote = 0.0;
        double mappedThighTop = 0.0;
        int mappedThighs = 0;
        double anyThighTop = 0.0;
        int anyThighs = 0;
        double mappedShinTop = -Double.MAX_VALUE;
        double anyShinTop = -Double.MAX_VALUE;

        for (YSMGeoModel.Bone bone : order) {
            if (geometry.variant.contains(bone)) {
                // Measured, but not part of the model's own proportions: see Geometry#variant.
                continue;
            }
            int joint = resolveJointId(bone);
            boolean hasGeometry = !bone.quads.isEmpty();
            float lateral = geometry.lateralOf(bone);

            if (isSidedLimbJoint(joint)) {
                if (hasGeometry && Float.isFinite(lateral)) {
                    float distance = Math.abs(lateral);
                    limbValues.add(distance);
                    pairValues.computeIfAbsent(pairKey(joint), key -> new java.util.ArrayList<>()).add(distance);
                    if (isDirectlyMapped(bone)) {
                        vote += (isRightSideJoint(joint) ? 1.0 : -1.0) * lateral * quadCount(bone);
                    }
                }
            }

            if (joint == JointTable.THIGH_L || joint == JointTable.THIGH_R) {
                float top = geometry.topOf(bone);
                if (Float.isFinite(top)) {
                    anyThighTop += top;
                    anyThighs++;
                    if (isDirectlyMapped(bone)) {
                        mappedThighTop += top;
                        mappedThighs++;
                    }
                }
            }

            if (joint == JointTable.LEG_L || joint == JointTable.LEG_R) {
                // The knee is the top of the shin geometry. The maximum over the joint's own
                // geometry, not an average: a bone named "...Foot" also resolves here and its
                // geometry is lower, so taking the highest is what picks the shin out.
                float top = topOfOwnGeometry(geometry, bone);
                if (Float.isFinite(top)) {
                    anyShinTop = Math.max(anyShinTop, top);
                    if (isDirectlyMapped(bone)) {
                        mappedShinTop = Math.max(mappedShinTop, top);
                    }
                }
            }
        }

        float hip = mappedThighs > 0 ? (float) (mappedThighTop / mappedThighs)
                : (anyThighs > 0 ? (float) (anyThighTop / anyThighs) : Float.NaN);
        float knee = mappedShinTop != -Double.MAX_VALUE ? (float) mappedShinTop
                : (anyShinTop != -Double.MAX_VALUE ? (float) anyShinTop : Float.NaN);
        Geometry measured = new Geometry(hip,
                Float.isFinite(hip) && Float.isFinite(knee) && hip - knee > 0.0F ? hip - knee : Float.NaN,
                median(limbValues), vote < 0.0 ? -1.0F : 1.0F);
        measured.own.putAll(geometry.own);
        measured.subtree.putAll(geometry.subtree);
        measured.pivot.putAll(geometry.pivot);
        for (Map.Entry<Integer, java.util.List<Float>> entry : pairValues.entrySet()) {
            measured.pairScale[entry.getKey()] = median(entry.getValue());
        }
        return measured;
    }

    /**
     * One top-down pass with the writer's own transform chain (see EFMeshJsonWriter#walkBone), so
     * every cube is measured where the mesh puts it: model space, up = +Y, and the model's own x.
     */
    private static void collectGeometry(YSMGeoModel.Bone bone, org.joml.Matrix4f parentTransform,
                                        java.util.Set<String> alternateForms, Geometry geometry,
                                        java.util.List<YSMGeoModel.Bone> order) {
        org.joml.Matrix4f transform = new org.joml.Matrix4f(parentTransform);
        transform.translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        transform.rotateZ(bone.rotZ);
        transform.rotateY(bone.rotY);
        transform.rotateX(bone.rotX);
        transform.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);

        org.joml.Vector3f pivot = new org.joml.Vector3f(bone.pivotX, bone.pivotY, bone.pivotZ)
                .mulPosition(parentTransform);
        geometry.pivot.put(bone, new float[]{pivot.x, pivot.y});

        if (BoneAlternateForms.isAlternateForm(bone.name, alternateForms)) {
            geometry.variant.add(bone);
        }
        order.add(bone);
        if (!bone.quads.isEmpty()) {
            float[] own = new float[4];
            own[2] = -Float.MAX_VALUE;
            for (YSMGeoModel.Quad quad : bone.quads) {
                for (org.joml.Vector3f corner : quad.positions) {
                    if (corner == null) {
                        continue;
                    }
                    org.joml.Vector3f p = new org.joml.Vector3f(corner).mulPosition(transform);
                    own[0] += p.x;
                    own[1] += p.y;
                    own[2] = Math.max(own[2], p.y);
                    own[3] += 1.0F;
                }
            }
            if (own[3] <= 0.0F) {
                own[2] = Float.NaN;
            }
            geometry.own.put(bone, own);
            float[] subtree = geometry.subtree.computeIfAbsent(bone, key -> new float[3]);
            subtree[0] += own[0];
            subtree[1] += own[1];
            subtree[2] += own[3];
        }

        for (YSMGeoModel.Bone child : bone.children) {
            collectGeometry(child, transform, alternateForms, geometry, order);
        }
    }

    /** The highest corner of a bone's own cubes in model space, or NaN when it has none. */
    private static float topOfOwnGeometry(Geometry geometry, YSMGeoModel.Bone bone) {
        float[] own = geometry.own.get(bone);
        return own == null || own[3] <= 0.0F ? Float.NaN : own[2];
    }

    /** The key a left/right pair shares, so both sides' geometry measures the same scale. */
    private static int pairKey(int joint) {
        return Math.min(joint, mirroredJoint(joint));
    }

    private static float median(java.util.List<Float> values) {
        if (values.isEmpty()) {
            return Float.NaN;
        }
        java.util.List<Float> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /**
     * The model's own hip height: the top of the geometry of the bones mapped to the thighs, which
     * is where the hip joint is on this rig, or their pivot when they carry no cubes of their own.
     * Directly-mapped thigh bones are preferred, so an unmapped accessory hanging high in the leg
     * chain cannot move the hip. NaN when the model has no nameable thigh bone at all.
     */
    static float hipHeight(YSMGeoModel model) {
        if (model == null || model.bonesByName.isEmpty()) {
            return Float.NaN;
        }
        return geometryOf(model).hipHeight;
    }

    /** The bone's own geometry height in model space, or where its pivot lands when it has none. */
    static float geometryHeight(YSMGeoModel.Bone bone, YSMGeoModel model) {
        return bone == null || model == null ? Float.NaN : geometryOf(model).heightOf(bone);
    }

    /** The bone's lateral position in model space: its own geometry, else its subtree, else pivot. */
    static float geometryLateral(YSMGeoModel.Bone bone, YSMGeoModel model) {
        return bone == null || model == null ? Float.NaN : geometryOf(model).lateralOf(bone);
    }

    /**
     * The highest corner of the bone's <b>own</b> cubes in model space, or NaN without geometry of
     * its own (a container's subtree does not count here: the knee has to be the shin's own top, not
     * a foot's).
     */
    static float geometryTop(YSMGeoModel.Bone bone, YSMGeoModel model) {
        return bone == null || model == null ? Float.NaN : topOfOwnGeometry(geometryOf(model), bone);
    }

    /**
     * The joint with its side taken from the geometry that carries it: the bone that supplied the
     * joint decides which side of <i>this model</i> the limb it names actually is on, and a limb
     * whose geometry is on the other side is bound to the other side's joint.
     *
     * <p>Only the joints that have a left/right pair are judged, and only when that geometry is
     * clearly off the midline - see {@link #SIDE_MIDLINE_FRACTION}. Everything else keeps the name's
     * answer.
     */
    private static int limbSideFromGeometry(YSMGeoModel.Bone source, int joint, YSMGeoModel model) {
        if (source == null || !isSidedLimbJoint(joint)) {
            return joint;
        }
        Geometry geometry = geometryOf(model);
        float lateral = geometry.lateralOf(source);
        if (!Float.isFinite(lateral)) {
            return joint;
        }
        float scale = geometry.pairScaleOf(joint);
        if (!Float.isFinite(scale) || scale <= 0.0F || Math.abs(lateral) <= SIDE_MIDLINE_FRACTION * scale) {
            return joint;
        }
        boolean geometryIsRight = lateral * geometry.sideConvention > 0.0F;
        return geometryIsRight == isRightSideJoint(joint) ? joint : mirroredJoint(joint);
    }

    /**
     * Which sign of model-space x this model's own skeleton puts the character's right-hand limbs
     * on: +1 when its directly-mapped right-side limb geometry sits at positive x, -1 when the
     * model is mirrored as a whole. Weighted by cube count so one stray bone cannot flip it.
     */
    static float sideConvention(YSMGeoModel model) {
        return geometryOf(model).sideConvention;
    }

    /** The model's own thigh length (hip height minus knee height), or NaN when unmeasurable. */
    static float thighLength(YSMGeoModel model) {
        if (model == null || model.bonesByName.isEmpty()) {
            return Float.NaN;
        }
        return geometryOf(model).thighLength;
    }

    /** The model's overall lateral limb scale (median |x| of its limb geometry), or NaN. */
    static float limbLateralUnit(YSMGeoModel model) {
        return geometryOf(model).limbUnit;
    }

    private static float quadCount(YSMGeoModel.Bone bone) {
        return bone.quads.isEmpty() ? 1.0F : bone.quads.size();
    }

    /** The joints that exist as a left/right pair, and are therefore the only ones a side can miss. */
    static boolean isSidedLimbJoint(int joint) {
        return isRightSideJoint(joint) || isLeftSideJoint(joint);
    }

    private static boolean isRightSideJoint(int joint) {
        return joint == JointTable.THIGH_R || joint == JointTable.LEG_R || joint == JointTable.KNEE_R
                || joint == JointTable.SHOULDER_R || joint == JointTable.ARM_R
                || joint == JointTable.HAND_R || joint == JointTable.TOOL_R
                || joint == JointTable.ELBOW_R;
    }

    private static boolean isLeftSideJoint(int joint) {
        return joint == JointTable.THIGH_L || joint == JointTable.LEG_L || joint == JointTable.KNEE_L
                || joint == JointTable.SHOULDER_L || joint == JointTable.ARM_L
                || joint == JointTable.HAND_L || joint == JointTable.TOOL_L
                || joint == JointTable.ELBOW_L;
    }

    /** The opposite side's joint: Arm_L for Arm_R, Leg_R for Leg_L, and so on. */
    static int mirroredJoint(int joint) {
        if (joint == JointTable.THIGH_R) return JointTable.THIGH_L;
        if (joint == JointTable.THIGH_L) return JointTable.THIGH_R;
        if (joint == JointTable.LEG_R) return JointTable.LEG_L;
        if (joint == JointTable.LEG_L) return JointTable.LEG_R;
        if (joint == JointTable.KNEE_R) return JointTable.KNEE_L;
        if (joint == JointTable.KNEE_L) return JointTable.KNEE_R;
        if (joint == JointTable.SHOULDER_R) return JointTable.SHOULDER_L;
        if (joint == JointTable.SHOULDER_L) return JointTable.SHOULDER_R;
        if (joint == JointTable.ARM_R) return JointTable.ARM_L;
        if (joint == JointTable.ARM_L) return JointTable.ARM_R;
        if (joint == JointTable.HAND_R) return JointTable.HAND_L;
        if (joint == JointTable.HAND_L) return JointTable.HAND_R;
        if (joint == JointTable.TOOL_R) return JointTable.TOOL_L;
        if (joint == JointTable.TOOL_L) return JointTable.TOOL_R;
        if (joint == JointTable.ELBOW_R) return JointTable.ELBOW_L;
        if (joint == JointTable.ELBOW_L) return JointTable.ELBOW_R;
        return joint;
    }

    /** The first ancestor (or the bone itself) whose name maps directly to an EF joint. */
    static YSMGeoModel.Bone mappedAncestor(YSMGeoModel.Bone bone) {
        for (YSMGeoModel.Bone current = bone; current != null; current = current.parent) {
            if (isDirectlyMapped(current)) {
                return current;
            }
        }
        return null;
    }

    /**
     * Whether the bone's own name maps directly to an EF joint (without walking up
     * to ancestors). Directly mapped bones are driven by Epic Fight's animations;
     * other bones follow YSM's evaluated bone transforms on top.
     */
    public static boolean isDirectlyMapped(YSMGeoModel.Bone bone) {
        return STANDARD_MAPPING.containsKey(normalize(bone.name));
    }

    /**
     * Normalizes a bone name for mapping lookup: lower case, underscores/spaces
     * removed, and trailing digits stripped so alternate-form subtrees of a model
     * (e.g. "LeftArm2" of a fox variant) map to the same EF joint as the primary
     * form ("LeftArm").
     *
     * YSM's default-form bones may carry a "_Default" form suffix (e.g. the momo
     * wine fox's "RightArm_Default"). After underscore removal that reads as
     * "rightarmdefault", which is not in the mapping table, so the bone counted
     * as an unmapped decoration: its geometry was skipped by the pivot
     * computation (YsmBindArmature) and the arm joints lost their real segment
     * geometry. Strip the form suffix so the default form maps to its joint.
     */
    public static String normalize(String boneName) {
        String normalized = boneName.toLowerCase().replace("_", "").replace(" ", "");
        int end = normalized.length();
        while (end > 0 && Character.isDigit(normalized.charAt(end - 1))) {
            end--;
        }
        normalized = normalized.substring(0, end);
        if (normalized.endsWith("default")) {
            normalized = normalized.substring(0, normalized.length() - "default".length());
        }
        return normalized;
    }
}
