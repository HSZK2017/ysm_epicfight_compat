package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.JointTable;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The bind pivot computation run over the real converted meshes of the models the game log showed
 * failing, through the production filter and the production pivot arithmetic.
 *
 * <p>{@code latest.log} recorded {@code [bind] model='兽耳酱x1.ysm' pivots root=null,torso=null,...} for
 * every joint, the same line for its twin {@code NagaU_Kemomimi.ysm}, an arm chain of nulls for
 * {@code STRESSTEST_SMT_Nahobino.ysm} and a missing wrist for {@code STRESSTEST_FGO_Lilith.ysm}. Null is
 * not an error in Epic Fight - it means "keep the reference biped's joint" - so those models rotated
 * their limbs about Steve's pivots and the geometry came apart.
 *
 * <p>What this test adds over the hand-built fixtures in {@link YsmBindArmatureTest} is the data: the
 * runtime bone table and the mesh the game actually converted, with its real bone names (fifteen
 * unnamed {@code X_T4_1}-style bones, {@code 0015}, {@code Cf2}, {@code joint1}...) and its real
 * geometry. Set {@code -Dysmef.golden.ysm_config_root=<.../config/yes_steve_model>} (or
 * {@code YSMEF_YSM_CONFIG_ROOT}) to run it against an install; it is skipped otherwise, like the other
 * install-dependent golden tests.
 *
 * <p>One approximation, stated: the game reads the set of bones hidden in the model's default form
 * from the model's parallel animations, which this offline read does not evaluate, so the hidden set
 * passed here is empty. That excludes less than the game does - it cannot hide a failure the game
 * would show, only the reverse.
 */
class YsmBindArmatureRealModelTest {

    /** The converted packages whose {@code [bind]} lines the log pinned: two all-null, one arm-null, one wrist-null. */
    private static final List<String> FIXTURES = List.of(
            "x1.ysm", // 兽耳酱x1.ysm (the mesh id keeps the hash; the Chinese name is sanitized away)
            "kemomimi",
            "nahobino",
            "lilith");

    /** The joints the [bind] line prints; null for any of these is the detachment this class is about. */
    private static final int[] LOGGED_JOINTS = {
            JointTable.ROOT, JointTable.CHEST, JointTable.HEAD,
            JointTable.SHOULDER_R, JointTable.SHOULDER_L, JointTable.ELBOW_R, JointTable.ELBOW_L};

    @Test
    @DisplayName("the models whose log line was all null resolve pivots from their own geometry")
    void theModelsTheLogShowedWithNullPivotsResolveTheirPivots() throws IOException {
        Path pack = convertedPackRoot();
        assumeTrue(pack != null,
                "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + "=<.../config/yes_steve_model> (or "
                        + YsmModelPackage.CONFIG_ROOT_ENV + ") to run this against a real install");

        StringBuilder report = new StringBuilder();
        report.append("model | bones | parts | joints with geometry | relaxed joints | "
                + "root | chest | head | shoulderR | elbowR | wristR\n");
        report.append("---|---|---|---|---|---|---|---|---|---|---|---\n");
        List<String> fellBackToTheReferenceBiped = new ArrayList<>();

        for (String fixture : FIXTURES) {
            Path runtimeFile = findFixture(pack.resolve("ysm_runtime/entity"), fixture);
            Path meshFile = runtimeFile == null ? null
                    : pack.resolve("animmodels/entity").resolve(runtimeFile.getFileName().toString());
            assertNotNull(runtimeFile, "the install has no converted runtime for '" + fixture + "': " + pack);
            assertNotNull(meshFile, "the converted mesh for '" + fixture + "' is missing: " + pack);
            assertTrue(Files.isRegularFile(meshFile), "not a file: " + meshFile);

            List<String> warnings = new ArrayList<>();
            YsmBindArmature.GeometryInput input = input(runtimeFile, meshFile);
            YsmBindArmature.GeometryData geometry = YsmBindArmature.collectGeometry(input, warnings::add);
            YsmBindArmature.BindPivots pivots = YsmBindArmature.computePivots(input, geometry, warnings::add);

            for (int joint : LOGGED_JOINTS) {
                if (pivots.byJoint().get(joint) == null) {
                    fellBackToTheReferenceBiped.add(input.modelId() + " " + JointTable.nameOf(joint));
                }
            }

            report.append(input.modelId())
                    .append(" | ").append(input.boneNames().length)
                    .append(" | ").append(input.parts().size())
                    .append(" | ").append(geometry.byJoint().size())
                    .append(" | ").append(geometry.relaxation() == null ? "none"
                            : geometry.relaxation().tierByJoint())
                    .append(" | ").append(fmt(pivots.byJoint().get(JointTable.ROOT)))
                    .append(" | ").append(fmt(pivots.byJoint().get(JointTable.CHEST)))
                    .append(" | ").append(fmt(pivots.byJoint().get(JointTable.HEAD)))
                    .append(" | ").append(fmt(pivots.byJoint().get(JointTable.SHOULDER_R)))
                    .append(" | ").append(fmt(pivots.byJoint().get(JointTable.ELBOW_R)))
                    .append(" | ").append(fmt(pivots.wristR()))
                    .append('\n');
            for (String warning : warnings) {
                report.append("  warn: ").append(warning).append('\n');
            }
        }

        // Written before the assertion, so a failing run still leaves the table that explains it.
        Path out = Paths.get("build", "reports", "ysm-bind-pivots.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);

        assertTrue(fellBackToTheReferenceBiped.isEmpty(),
                "these joints got no geometry-derived pivot and silently keep Epic Fight's reference biped"
                        + " joint - the detachment the game log showed: " + fellBackToTheReferenceBiped
                        + "\n" + report);
    }

    /** The runtime bone table and the converted mesh, read into the filter's plain input. */
    private static YsmBindArmature.GeometryInput input(Path runtimeFile, Path meshFile) throws IOException {
        JsonObject runtime = JsonParser.parseString(Files.readString(runtimeFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray bonesJson = runtime.getAsJsonArray("bones");
        int boneCount = bonesJson.size();
        String[] names = new String[boneCount];
        int[] joints = new int[boneCount];
        boolean[] mapped = new boolean[boneCount];
        Map<String, Integer> indexOfName = new HashMap<>();
        for (int i = 0; i < boneCount; i++) {
            JsonObject bone = bonesJson.get(i).getAsJsonObject();
            names[i] = bone.get("name").getAsString();
            joints[i] = bone.get("joint").getAsInt();
            mapped[i] = bone.get("mapped").getAsBoolean();
            indexOfName.put(names[i], i);
        }

        JsonObject mesh = JsonParser.parseString(Files.readString(meshFile, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray positionsJson = mesh.getAsJsonObject("vertices").getAsJsonObject("positions")
                .getAsJsonArray("array");
        float[] positions = new float[positionsJson.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = positionsJson.get(i).getAsFloat();
        }
        // Each bone part holds one triangle list of position indices (see EFMeshJsonWriter); the
        // runtime mesh hands the same indices to the filter, one VertexBuilder per corner. The parts
        // table lives inside the mesh's "vertices" object, beside the per-vertex arrays.
        List<YsmBindArmature.BoneGeometry> parts = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : mesh.getAsJsonObject("vertices")
                .getAsJsonObject("parts").entrySet()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            Integer boneIdx = indexOfName.get(partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (boneIdx == null) {
                continue;
            }
            JsonArray indices = entry.getValue().getAsJsonObject().getAsJsonArray("array");
            List<Vector3f> vertices = new ArrayList<>(indices.size());
            for (JsonElement index : indices) {
                int p = index.getAsInt() * 3;
                if (p + 2 < positions.length) {
                    vertices.add(new Vector3f(positions[p], positions[p + 1], positions[p + 2]));
                }
            }
            parts.add(new YsmBindArmature.BoneGeometry(boneIdx, vertices));
        }
        return new YsmBindArmature.GeometryInput(runtimeFile.getFileName().toString(), names, joints, mapped,
                Set.of(), parts);
    }

    /** The converted resource pack of this mod inside the instance's config directory. */
    private static Path convertedPackRoot() {
        String configured = System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY, "");
        if (configured.isEmpty()) {
            String fromEnvironment = System.getenv(YsmModelPackage.CONFIG_ROOT_ENV);
            configured = fromEnvironment == null ? "" : fromEnvironment;
        }
        if (configured.isEmpty()) {
            return null;
        }
        Path configDir = Paths.get(configured).toAbsolutePath().getParent();
        if (configDir == null) {
            return null;
        }
        Path pack = configDir.resolve("ysm_epicfight_compat").resolve("resourcepack").resolve("assets")
                .resolve("ysm_epicfight_compat");
        return Files.isDirectory(pack.resolve("ysm_runtime/entity")) ? pack : null;
    }

    private static Path findFixture(Path dir, String needle) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().contains(needle.toLowerCase()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static String fmt(Vector3f v) {
        return v == null ? "null" : String.format("(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }
}
