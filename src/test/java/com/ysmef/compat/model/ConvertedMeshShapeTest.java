package com.ysmef.compat.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of a converted mesh, pinned against a real one.
 *
 * <p>The fixtures are a converted model taken from the test instance's cache. They are the only
 * place in the test tree where the converter's real output can be inspected, and they are here
 * because two of this mod's faults were only visible in that output: the geometry is far coarser
 * where a body bends than where it is detailed, and every vertex was bound rigidly to one joint.
 *
 * <p>This test asserts the <b>before</b> state, deliberately. It is the baseline the joint
 * blending change is measured against, and it fails the moment the fixture is replaced with a
 * mesh from the newer converter - which is the reminder to re-measure rather than assume.
 */
class ConvertedMeshShapeTest {

    private static JsonObject fixture(String name) {
        InputStream in = ConvertedMeshShapeTest.class.getResourceAsStream("/cloth/" + name);
        assertNotNull(in, "fixture missing from the test resources: " + name);
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /**
     * The mesh's topology: one rigid influence per vertex, and one vertex per entry of every
     * per-vertex array.
     *
     * <p>Worth pinning because a mesh whose arrays disagree does not fail loudly - it draws
     * wrong, which is what "the model is distorted" looks like from outside.
     */
    @Test
    void everyVertexCarriesExactlyOneInfluence() {
        JsonObject root = fixture("taisho_mesh.json");
        JsonObject vertices = root.getAsJsonObject("vertices");

        int count = vertices.getAsJsonObject("positions").get("count").getAsInt();
        assertTrue(count > 1000, "a converted player model should have real geometry, got " + count);

        assertEquals(count * 3, vertices.getAsJsonObject("positions").getAsJsonArray("array").size(),
                "positions carry three floats per vertex");
        assertEquals(count * 2, vertices.getAsJsonObject("vindices").getAsJsonArray("array").size(),
                "vindices carry a joint pair per vertex");
        assertEquals(count, vertices.getAsJsonObject("vcounts").getAsJsonArray("array").size());
        assertEquals(count * 2, vertices.getAsJsonObject("uvs").getAsJsonArray("array").size());

        JsonArray weights = vertices.getAsJsonObject("weights").getAsJsonArray("array");
        assertEquals(1, weights.size(),
                "the converter emitted a single influence slot: one joint per vertex, weight 1");
        assertEquals(1.0F, weights.get(0).getAsFloat(), 1.0E-6F);
    }

    /**
     * The coarseness that makes a bend tear: the model's detail is in its hair, and the parts
     * that have to bend are the sparse ones.
     *
     * <p>Measured from the vertices themselves rather than from the parts table, because the
     * parts table describes the source model's bones and not what was emitted.
     */
    @Test
    void theGeometryThatHasToBendIsTheSparsePart() {
        JsonObject root = fixture("taisho_mesh.json");
        JsonObject vertices = root.getAsJsonObject("vertices");
        JsonArray vindices = vertices.getAsJsonObject("vindices").getAsJsonArray("array");

        // EF joint ids, from JointTable: 9 Head, 8 Chest, 7 Torso, 1/2 right leg, 4/5 left leg.
        int head = 0;
        int chest = 0;
        int legs = 0;
        int total = vindices.size() / 2;
        for (int i = 0; i < total; i++) {
            int joint = vindices.get(i * 2).getAsInt();
            if (joint == 9) {
                head++;
            } else if (joint == 8) {
                chest++;
            } else if (joint == 1 || joint == 2 || joint == 4 || joint == 5) {
                legs++;
            }
        }

        assertTrue(head > chest, "the head and its hair carry more geometry than the torso: "
                + head + " vs " + chest);
        assertTrue(chest > legs, "and the torso more than the legs: " + chest + " vs " + legs);
        assertTrue(legs > 0, "the legs are drawn at all");
    }

    /** The runtime table the cloth reads: bones with parents, pivots and resolved joints. */
    @Test
    void theRuntimeTableDescribesEveryBone() {
        JsonObject root = fixture("taisho_runtime.json");
        JsonArray bones = root.getAsJsonArray("bones");

        assertTrue(bones.size() > 100, "a converted model carries its whole bone table, got " + bones.size());
        for (int i = 0; i < bones.size(); i++) {
            JsonObject bone = bones.get(i).getAsJsonObject();
            assertNotNull(bone.get("name"), "bone " + i + " has a name");
            assertNotNull(bone.get("parent"), "bone " + i + " has a parent field");
            assertEquals(3, bone.getAsJsonArray("pivot").size(), "bone " + i + " has a pivot");
            assertTrue(bone.has("joint"), "bone " + i + " has a resolved joint");
        }
    }
}
