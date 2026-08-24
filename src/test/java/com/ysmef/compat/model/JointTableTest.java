package com.ysmef.compat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the shared biped joint table (the single source of truth for the 20
 * Epic Fight reference joints; previously duplicated in three classes).
 */
public class JointTableTest {

    @Test
    void layoutMatchesEpicFightBipedJson() {
        assertEquals(20, JointTable.COUNT);
        assertEquals(20, JointTable.NAMES.length);
        assertEquals(20, JointTable.PARENTS.length);
        assertEquals("Root", JointTable.NAMES[JointTable.ROOT]);
        assertEquals("Thigh_R", JointTable.NAMES[JointTable.THIGH_R]);
        assertEquals("Knee_R", JointTable.NAMES[JointTable.KNEE_R]);
        assertEquals("Torso", JointTable.NAMES[JointTable.TORSO]);
        assertEquals("Chest", JointTable.NAMES[JointTable.CHEST]);
        assertEquals("Head", JointTable.NAMES[JointTable.HEAD]);
        assertEquals("Shoulder_R", JointTable.NAMES[JointTable.SHOULDER_R]);
        assertEquals("Tool_R", JointTable.NAMES[JointTable.TOOL_R]);
        assertEquals("Elbow_L", JointTable.NAMES[JointTable.ELBOW_L]);
    }

    @Test
    void parentsFormATreeRootedAtRoot() {
        assertEquals(-1, JointTable.PARENTS[JointTable.ROOT]);
        for (int joint = 0; joint < JointTable.COUNT; joint++) {
            int parent = JointTable.PARENTS[joint];
            if (joint != JointTable.ROOT) {
                assertTrue(parent >= 0 && parent < JointTable.COUNT && parent != joint,
                        "joint " + JointTable.NAMES[joint] + " must have a valid parent");
            }
        }
    }

    @Test
    void idNameRoundTrip() {
        for (int i = 0; i < JointTable.COUNT; i++) {
            assertEquals(i, JointTable.idOf(JointTable.NAMES[i]));
        }
        assertEquals(-1, JointTable.idOf("not_a_joint"));
        assertEquals("Joint99", JointTable.nameOf(99));
        assertEquals("Joint-1", JointTable.nameOf(-1));
    }
}
