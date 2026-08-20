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
        map.put("Root", 0);
        map.put("Thigh_R", 1);
        map.put("Leg_R", 2);
        map.put("Knee_R", 3);
        map.put("Thigh_L", 4);
        map.put("Leg_L", 5);
        map.put("Knee_L", 6);
        map.put("Torso", 7);
        map.put("Chest", 8);
        map.put("Head", 9);
        map.put("Shoulder_R", 10);
        map.put("Arm_R", 11);
        map.put("Hand_R", 12);
        map.put("Tool_R", 13);
        map.put("Elbow_R", 14);
        map.put("Shoulder_L", 15);
        map.put("Arm_L", 16);
        map.put("Hand_L", 17);
        map.put("Tool_L", 18);
        map.put("Elbow_L", 19);
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

    /**
     * Whether the bone's own name maps directly to an EF joint (without walking up
     * to ancestors). Directly mapped bones are driven by Epic Fight's animations;
     * other bones follow YSM's evaluated bone transforms on top.
     */
    public static boolean isDirectlyMapped(YSMGeoModel.Bone bone) {
        return STANDARD_MAPPING.containsKey(normalize(bone.name));
    }

    // [decommissioned] dead method: never referenced anywhere in the codebase.
    //
    // /**
    //  * The EF joint id -> EF joint name, for sanity checks.
    //  */
    // public static String jointNameOf(int jointId) {
    //     for (Map.Entry<String, Integer> entry : JOINT_IDS.entrySet()) {
    //         if (entry.getValue() == jointId) {
    //             return entry.getKey();
    //         }
    //     }
    //     return "Root";
    // }

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
    private static String normalize(String boneName) {
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
