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
        for (YSMGeoModel.Bone current = bone; current != null; current = current.parent) {
            String normalized = normalize(current.name);
            String efJointName = STANDARD_MAPPING.get(normalized);
            if (efJointName != null) {
                return JOINT_IDS.get(efJointName);
            }
        }
        return JOINT_IDS.get("Root");
    }

    /** Epic Fight's Chest joint, the one cloth hanging below the waist must not bind to. */
    static final int JOINT_CHEST = 8;
    /** Epic Fight's Torso joint: the waist and hips, where a skirt actually hangs from. */
    public static final int JOINT_TORSO = 7;

    /**
     * The joint a bone's <b>geometry</b> belongs to, judged by where the geometry is and not only
     * by what its ancestors happen to be called.
     *
     * <h2>Why the name walk alone is not enough</h2>
     *
     * <p>A garment container is wherever the author put it in the rig, and the rig does not have
     * to agree with the anatomy. On the maid skirt this model is built from, every one of its
     * twenty-four panels descends from {@code clothe -> UpBody}, and {@code UpBody} is the chest:
     *
     * <pre>
     *   FM1 -> FM -> FrontClothe -> clothe -> UpBody [Chest]
     *   RM  -> RightClothe -> clothe -> UpBody [Chest]
     * </pre>
     *
     * <p>So the whole skirt was skinned to the <b>upper body</b>. YSM itself does not mind - it
     * animates {@code clothe} directly, so the skirt follows whatever the author's animation does
     * - but this mod binds every vertex rigidly to one Epic Fight joint, and once the container's
     * animation is gone the skirt simply rides the chest. In combat that is exactly what it looks
     * like: Epic Fight's attacks twist the chest hard, and a skirt bolted to the chest is thrown
     * away from the hips on every swing. No amount of physics tuning can compensate, because the
     * pose the physics is asked to follow is the wrong body part's.
     *
     * <h2>The rule</h2>
     *
     * <p>Cloth whose geometry sits at or below the hips belongs to the lower body. The hip height
     * is taken from the model's own mapped thigh bones rather than from a constant, so it holds
     * for any proportions; the geometry is the bone's own cube centroid, in the same model space
     * the mesh is written in.
     *
     * <p>What is deliberately <b>not</b> the rule is a list of container names. {@code clothe} is
     * the name this model uses; the next model calls it {@code qunzi} or {@code SkirtGroup} or
     * nothing at all, which is the same reason this mod classifies physics parts from the model's
     * own declarations instead of from its vocabulary.
     *
     * @param model the model the bone belongs to, or null to keep the plain name walk
     */
    public static int resolveJointId(YSMGeoModel.Bone bone, YSMGeoModel model) {
        int joint = resolveJointId(bone);
        if (model == null || joint != JOINT_CHEST) {
            return joint;
        }
        if (isDirectlyMapped(bone)) {
            // The bone is a body part itself, named as one; its joint is the author's word and not
            // an inheritance to be second-guessed. This is what keeps UpBody, UpperBody, Elytra and
            // the arm bones where they belong - the rule exists only for cloth that picked a joint
            // up from an ancestor on its way down the hierarchy.
            return joint;
        }
        YSMGeoModel.Bone anchor = mappedAncestor(bone);
        if (anchor == null) {
            return joint;
        }
        float anchorHeight = geometryOrPivotHeight(anchor);
        float height = geometryOrPivotHeight(bone);
        if (!Float.isFinite(anchorHeight) || !Float.isFinite(height)) {
            return joint;
        }
        return height <= anchorHeight + GARMENT_ABOVE_MARGIN ? JOINT_TORSO : joint;
    }

    /**
     * How far above the body part it hangs from a piece of cloth may sit and still count as
     * lower-body cloth, in model-space blocks.
     *
     * <p>A tolerance rather than a threshold, because on a real rig the two are at the same height
     * and no threshold can separate them. Measured on the maid model: its waist <i>is</i> the chest
     * bone's height - {@code UpBody} pivots at 1.2313 and {@code DownBody}, the bone mapped to the
     * Torso, pivots at 1.2313 as well - and the skirt's own top row is centred at 1.2957 against
     * the chest geometry's 1.2483, a difference of five hundredths of a block. Demanding a clear
     * drop would leave that top row and every container on the chest while the lower panels moved,
     * which is a skirt torn in half rather than a skirt fixed.
     *
     * <p>What the tolerance still excludes is what must stay: gear genuinely mounted <i>above</i>
     * the chest - a collar, pauldrons, a backpack, an elytra - sits well clear of the chest's own
     * geometry and keeps its joint.
     */
    private static final float GARMENT_ABOVE_MARGIN = 0.25F;

    /** The first ancestor (or the bone itself) whose name maps directly to an EF joint. */
    static YSMGeoModel.Bone mappedAncestor(YSMGeoModel.Bone bone) {
        for (YSMGeoModel.Bone current = bone; current != null; current = current.parent) {
            if (isDirectlyMapped(current)) {
                return current;
            }
        }
        return null;
    }

    /** A bone's own geometry height, falling back to its pivot when it carries no geometry. */
    private static float geometryOrPivotHeight(YSMGeoModel.Bone bone) {
        float geometry = centroidHeight(bone);
        return Float.isFinite(geometry) ? geometry : bone.pivotY;
    }

    /**
     * The mean height of a bone's own cube corners in model space, or NaN when it carries no
     * geometry.
     *
     * <p>The cube corners are model-space, which is where the mesh's vertices end up, so this is
     * directly comparable with {@link #hipHeight}.
     */
    static float centroidHeight(YSMGeoModel.Bone bone) {
        if (bone == null || bone.quads.isEmpty()) {
            return Float.NaN;
        }
        double sum = 0.0;
        int count = 0;
        for (YSMGeoModel.Quad quad : bone.quads) {
            for (org.joml.Vector3f corner : quad.positions) {
                if (corner != null) {
                    sum += corner.y;
                    count++;
                }
            }
        }
        return count == 0 ? Float.NaN : (float) (sum / count);
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
