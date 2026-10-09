package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.JointTable;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class YsmHeadContactConstraintTest {

    @Test
    void broadContactWithHeadStaysRigidWhileLocksAndRemoteShellsSwing() {
        List<Vector3f> skull = box(-0.15F, 0.15F, 1.4F, 1.7F, -0.1F, 0.1F);
        List<Vector3f> shell = new ArrayList<>(box(-0.15F, 0.15F, 1.705F, 1.8F,
                -0.1F, 0.1F));
        List<Vector3f> sideLock = box(0.12F, 0.17F, 1.3F, 1.705F, 0.08F, 0.12F);
        List<Vector3f> remoteShell = box(-0.15F, 0.15F, 1.77F, 1.86F, -0.1F, 0.1F);
        assertTrue(YsmHeadContactConstraint.restsAcrossSkull(shell, skull));
        assertFalse(YsmHeadContactConstraint.restsAcrossSkull(sideLock, skull));
        assertFalse(YsmHeadContactConstraint.restsAcrossSkull(remoteShell, skull));
        assertFalse(YsmHeadContactConstraint.restsAcrossSkull(List.of(), skull));
        List<Vector3f> longLock = new ArrayList<>();
        longLock.add(new Vector3f(-0.15F, 1.705F, 0.1F));
        longLock.add(new Vector3f(0.15F, 1.705F, 0.1F));
        for (int i = 0; i < 30; i++) {
            longLock.add(new Vector3f(-0.12F + i * 0.008F, 0.7F + i * 0.01F, 0.3F));
        }
        assertFalse(YsmHeadContactConstraint.restsAcrossSkull(longLock, skull),
                "two widely separated root vertices must not freeze a long hanging lock");

        // A non-mapped hair container may have geometry of its own. The mapped head behind it is
        // still the supporting body joint; the classification must not depend on either name.
        YSMRuntimeModel.BoneRt[] bones = {bone("A", -1, true, JointTable.HEAD),
                bone("B", 0, false, JointTable.HEAD),
                bone("Piece42", 1, false, JointTable.HEAD)};
        Map<Integer, List<Vector3f>> vertices = Map.of(0, skull, 1, sideLock, 2, shell);
        assertTrue(YsmHeadContactConstraint.holds(bones, 2, vertices));
        bones[0].joint = JointTable.CHEST;
        assertFalse(YsmHeadContactConstraint.holds(bones, 2, vertices));
    }

    @Test
    void wineFoxCapsContactTheHeadButSeparateStrandsKeepSwinging(@TempDir Path temporary)
            throws Exception {
        assumeTrue(System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY) != null
                        || System.getenv(YsmModelPackage.CONFIG_ROOT_ENV) != null,
                "requires an installed YSM config root");
        String[] ids = {"01_taisho_maid", "02_new_year", "03_astronaut", "04_kongfu",
                "05_magical", "06_hanfu", "07_jk", "09_hailuo", "11_salesperson",
                "13_matured", "14_momo", "16_tactics", "17_mini", "18_wedding",
                "19_nine_tailed", "20_survivor", "21_saint", "22_elf"};
        int checked = 0;
        StringBuilder report = new StringBuilder("model | head-contact pieces\n---|---\n");
        for (String id : ids) {
            String modelId = "wine_fox/" + id;
            YsmModelPackage pack = YsmModelPackage.load(modelId);
            assertTrue(pack != null && pack.geometry != null, modelId + " is missing");
            Path mesh = temporary.resolve(id + "-mesh.json");
            Path runtime = temporary.resolve(id + "-runtime.json");
            EFMeshJsonWriter.write(pack, mesh, runtime, "ysm_epicfight_compat:textures/audit.png");
            Converted converted = read(runtime, mesh);
            assertTrue(converted.holds("BaseHair"), modelId + " must keep its head cap fixed");
            List<String> held = new ArrayList<>();
            for (String name : converted.index.keySet()) {
                if (converted.holds(name)) {
                    held.add(name);
                }
            }
            held.sort(String::compareTo);
            report.append(modelId).append(" | ").append(held).append('\n');
            if (id.equals("01_taisho_maid")) {
                for (String hanging : List.of("Bangs", "LongHair", "RightSideHair", "LeftSideHair")) {
                    assertFalse(converted.holds(hanging), modelId + '/' + hanging
                            + " is a separate hanging piece, not the scalp shell");
                }
            }
            checked++;
        }
        Path output = Path.of("build", "reports", "ysm-head-contact-wine-fox.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        assertTrue(checked == 18);
    }

    private static YSMRuntimeModel.BoneRt bone(String name, int parent, boolean mapped, int joint) {
        YSMRuntimeModel.BoneRt bone = new YSMRuntimeModel.BoneRt();
        bone.name = name;
        bone.parent = parent;
        bone.mapped = mapped;
        bone.joint = joint;
        return bone;
    }

    private static List<Vector3f> box(float x0, float x1, float y0, float y1,
                                      float z0, float z1) {
        List<Vector3f> points = new ArrayList<>();
        for (float x : new float[]{x0, x1}) {
            for (float y : new float[]{y0, y1}) {
                for (float z : new float[]{z0, z1}) {
                    points.add(new Vector3f(x, y, z));
                }
            }
        }
        return points;
    }

    private record Converted(YSMRuntimeModel.BoneRt[] bones, Map<String, Integer> index,
                             Map<Integer, List<Vector3f>> vertices) {
        boolean holds(String name) {
            Integer at = index.get(name);
            return at != null && YsmHeadContactConstraint.holds(bones, at, vertices);
        }
    }

    private static Converted read(Path runtimeFile, Path meshFile) throws Exception {
        JsonObject runtime = JsonParser.parseString(Files.readString(runtimeFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject mesh = JsonParser.parseString(Files.readString(meshFile, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("vertices");
        JsonArray entries = runtime.getAsJsonArray("bones");
        YSMRuntimeModel.BoneRt[] bones = new YSMRuntimeModel.BoneRt[entries.size()];
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            bones[i] = bone(entry.get("name").getAsString(), -1,
                    entry.has("mapped") && entry.get("mapped").getAsBoolean(),
                    entry.get("joint").getAsInt());
            index.put(bones[i].name, i);
        }
        for (int i = 0; i < entries.size(); i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            if (entry.has("parent")) {
                bones[i].parent = index.getOrDefault(entry.get("parent").getAsString(), -1);
            }
        }
        JsonArray positions = mesh.getAsJsonObject("positions").getAsJsonArray("array");
        Map<Integer, List<Vector3f>> vertices = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : mesh.getAsJsonObject("parts").entrySet()) {
            String part = entry.getKey();
            if (!part.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            Integer bone = index.get(part.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (bone == null) {
                continue;
            }
            List<Vector3f> own = vertices.computeIfAbsent(bone, unused -> new ArrayList<>());
            for (JsonElement element : entry.getValue().getAsJsonObject().getAsJsonArray("array")) {
                int at = element.getAsInt() * 3;
                own.add(new Vector3f(positions.get(at).getAsFloat(),
                        positions.get(at + 1).getAsFloat(), positions.get(at + 2).getAsFloat()));
            }
        }
        return new Converted(bones, index, vertices);
    }
}
