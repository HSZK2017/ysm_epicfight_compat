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

    /**
     * No second copy of the table in the source tree.
     *
     * <p>This class is the single source of truth only as long as nobody re-declares the table, and
     * someone had: {@code YSMPlayerRenderer}'s armature check carried a complete copy of the twenty
     * names and ids, so the one check whose job is to catch a joint-layout mismatch was validating
     * its own copy - if this table changed and that one did not, it passed while the mesh generator
     * used the new layout. The copy is gone (the check reads this table now); this test is the drift
     * guard that keeps it gone, because a source-text check is the only thing that can see a
     * re-declaration at all.
     *
     * <p>Only joint-name literals are looked for, not the word "joint": {@code RenderItemBaseMixin}
     * legitimately lists four hand joints as a policy table, which is a different thing from a copy
     * of the layout.
     */
    @Test
    void noSecondCopyOfTheLayoutInTheRenderPath() throws Exception {
        java.nio.file.Path renderer = locate("src/main/java/com/ysmef/compat/renderer");
        java.util.List<String> offenders = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(renderer)) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.filter(java.nio.file.Files::isRegularFile)::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")) {
                    continue;
                }
                String text = java.nio.file.Files.readString(file);
                // A node name plus the id it must have, in one line: the shape of the copy that was
                // removed. One such pair is enough - a real copy has twenty.
                if (text.matches("(?s).*\"(Thigh_R|Knee_L|Elbow_L)\"\\s*,\\s*\"?\\d+\"?.*")) {
                    offenders.add(name);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these renderer sources carry their own joint name/id table again instead of reading "
                        + "JointTable: " + offenders);
    }

    /** Resolve a path relative to the project directory, wherever the test is run from. */
    private static java.nio.file.Path locate(String relative) {
        java.nio.file.Path direct = java.nio.file.Paths.get(relative);
        if (java.nio.file.Files.isDirectory(direct)) {
            return direct;
        }
        java.nio.file.Path here = java.nio.file.Paths.get("").toAbsolutePath();
        for (java.nio.file.Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            java.nio.file.Path resolved = candidate.resolve(relative);
            if (java.nio.file.Files.isDirectory(resolved)) {
                return resolved;
            }
        }
        throw new IllegalStateException("could not locate " + relative + " from " + here);
    }
}
