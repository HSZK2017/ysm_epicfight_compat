package com.ysmef.compat.model;

/**
 * The fixed 20-joint layout of Epic Fight's reference biped armature (the
 * "joints" array of assets/epicfight/animmodels/entity/biped.json):
 * Root, Thigh_R, Leg_R, Knee_R, Thigh_L, Leg_L, Knee_L, Torso, Chest, Head,
 * Shoulder_R, Arm_R, Hand_R, Tool_R, Elbow_R, Shoulder_L, Arm_L, Hand_L, Tool_L, Elbow_L.
 *
 * Single source of truth for the joint table - previously duplicated (with
 * drift risk) in YSMJointMapper, YsmExtraFrameWriter and
 * YsmExtraAnimationLibrary.
 */
public final class JointTable {

    public static final int COUNT = 20;

    public static final int ROOT = 0;
    public static final int THIGH_R = 1;
    public static final int LEG_R = 2;
    public static final int KNEE_R = 3;
    public static final int THIGH_L = 4;
    public static final int LEG_L = 5;
    public static final int KNEE_L = 6;
    public static final int TORSO = 7;
    public static final int CHEST = 8;
    public static final int HEAD = 9;
    public static final int SHOULDER_R = 10;
    public static final int ARM_R = 11;
    public static final int HAND_R = 12;
    public static final int TOOL_R = 13;
    public static final int ELBOW_R = 14;
    public static final int SHOULDER_L = 15;
    public static final int ARM_L = 16;
    public static final int HAND_L = 17;
    public static final int TOOL_L = 18;
    public static final int ELBOW_L = 19;

    public static final String[] NAMES = {
            "Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L", "Knee_L",
            "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R", "Tool_R",
            "Elbow_R", "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"
    };

    /** Parent joint id per joint (-1 for the root). */
    public static final int[] PARENTS = {
            -1, 0, 1, 1, 0, 4, 4, 0, 7, 8, 8, 10, 11, 12, 11, 8, 15, 16, 17, 16
    };

    private JointTable() {}

    /** The joint id of a biped joint name, or -1. */
    public static int idOf(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The biped joint name of an id, or "Joint" + id for out-of-range ids. */
    public static String nameOf(int joint) {
        return joint >= 0 && joint < NAMES.length ? NAMES[joint] : "Joint" + joint;
    }
}
