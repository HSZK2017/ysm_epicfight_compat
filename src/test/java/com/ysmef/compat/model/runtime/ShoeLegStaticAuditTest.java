package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.BoneAlternateForms;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.JointTable;
import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.model.YSMJointMapper;
import com.ysmef.compat.ysm.YsmModelPackage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measurement, not an assertion: the static-displacement audit for the reported "shoes not on the feet"
 * and "leg separated from the body" models. Opt-in (it needs a YSM install), like the other
 * install-dependent tests; it writes its tables under {@code build/reports/}.
 *
 * <p>Produces, per model, the tables a diagnosis can be read off without the game:
 *
 * <ol>
 *   <li><b>The fresh production conversion</b> - {@link EFMeshJsonWriter#write} over the real .ysm
 *       package, i.e. the mesh the CURRENT rules would bake, with every per-vertex joint id.</li>
 *   <li><b>The stale on-disk conversion</b> - the mesh JSON the game actually loaded (the mod reuses a
 *       verified manifest cache entry, so this is whatever build first converted it), read from the
 *       generated resource pack.</li>
 *   <li><b>An independent bind re-evaluation</b> - every bone's world matrix rebuilt from its own
 *       parent chain (walking {@code bone.parent} upwards and composing), then the bone's own quad
 *       corners transformed with it. This shares no traversal with the writer's top-down recursion, so
 *       a difference between it and either JSON localises a wrong bake instead of a wrong number.</li>
 * </ol>
 *
 * <p>Plus: the geometry-derived bind pivot of every joint (through the production
 * {@link YsmBindArmature#computePivots} over both the fresh and the stale tables), the bones hidden in
 * the model's default form, and the conversion-time physics part selection (the only writer of
 * per-part transforms in battle mode).
 *
 * <p>Skipped unless the YSM config root is given, like the other install-dependent golden tests.
 */
class ShoeLegStaticAuditTest {

    private static final List<String> MODELS = List.of(
            "兽耳酱x1.ysm", "NagaU_Kemomimi.ysm", "STRESSTEST_SMT_Nahobino.ysm");

    private static final int[] LEG_JOINTS = {
            JointTable.THIGH_R, JointTable.LEG_R, JointTable.KNEE_R,
            JointTable.THIGH_L, JointTable.LEG_L, JointTable.KNEE_L};

    @Test
    void dumpTheStaticAudit() throws Exception {
        String configured = configuredConfigRoot();
        assumeTrue(!configured.isEmpty(),
                "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + "=<config/yes_steve_model> (or the "
                        + YsmModelPackage.CONFIG_ROOT_ENV + " environment variable) to run the static audit");
        Path pack = convertedPackRoot(Paths.get(configured));
        assumeTrue(pack != null, "no converted resource pack under " + configured);

        StringBuilder md = new StringBuilder();
        md.append("# YSM-EF static-displacement audit (ShoeLegStaticAuditTest)\n\n");
        md.append("Fresh = `EFMeshJsonWriter.write` over the real package with the current rules. ")
                .append("Stale = the mesh JSON the game loaded from the generated pack. ")
                .append("Independent = every bone's bind transform rebuilt from its own parent chain.\n\n");
        md.append("All coordinates are the runtime Minecraft frame (up = +Y), in blocks, after the ")
                .append("model's width/height scale.\n");

        for (String model : MODELS) {
            md.append(modelReport(model, pack));
        }

        Path out = Paths.get("build", "reports", "ysm-shoe-leg-audit.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
        System.out.println(md);
    }

    private static String modelReport(String modelId, Path pack) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("\n## ").append(modelId).append("\n\n");

        Path manifestFile = pack.getParent().getParent().getParent().resolve("manifest.json");
        JsonObject manifest = JsonParser.parseString(
                Files.readString(manifestFile, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject entry = manifest.getAsJsonObject("models").getAsJsonObject(modelId);
        if (entry == null || !entry.has("mesh")) {
            // The manifest only lists the models this install has actually converted, so an audit
            // fixture is legitimately absent whenever the session that wrote the manifest never used
            // it. This test is a dump of whatever the install happens to hold, not an assertion about
            // the install, so report the gap instead of failing with a NullPointerException.
            md.append("**not converted in this install (no manifest entry)**\n");
            return md.toString();
        }
        String meshName = entry.get("mesh").getAsString();

        YsmModelPackage pkg = YsmModelPackage.load(modelId);
        if (pkg == null || pkg.geometry == null) {
            md.append("**could not load the package**\n");
            return md.toString();
        }
        Path tmp = Files.createTempDirectory("ysm-static-audit");
        Path freshMeshFile = tmp.resolve("mesh.json");
        Path freshRuntimeFile = tmp.resolve("runtime.json");
        EFMeshJsonWriter.write(pkg, freshMeshFile, freshRuntimeFile, "ysm_epicfight_compat:textures/audit.png");

        MeshData fresh = readMesh(freshMeshFile);
        MeshData stale = readMesh(pack.resolve("animmodels").resolve("entity").resolve(meshName + ".json"));
        JsonObject freshRuntime = JsonParser.parseString(
                Files.readString(freshRuntimeFile, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject staleRuntime = JsonParser.parseString(
                Files.readString(pack.resolve("ysm_runtime").resolve("entity").resolve(meshName + ".json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();

        YSMGeoModel geo = pkg.geometry;
        Set<String> alternateForms = BoneAlternateForms.baseFormsPresent(
                geo.bonesByName.keySet().toArray(new String[0]));
        Set<String> hiddenFresh = YSMRuntimeModel.computeDefaultHiddenBoneNames(freshRuntime);
        Set<String> hiddenStale = YSMRuntimeModel.computeDefaultHiddenBoneNames(staleRuntime);
        Map<String, Integer> rtFresh = runtimeJoints(freshRuntime);
        Map<String, Integer> rtStale = runtimeJoints(staleRuntime);

        // ---- independent bind re-evaluation (own code path: parent chain, not the writer's recursion) ----
        Map<String, float[]> independent = new TreeMap<>();
        Map<String, Vector3f> bonePivotMc = new HashMap<>();
        for (YSMGeoModel.Bone bone : geo.bonesByName.values()) {
            independent.put(bone.name, independentBounds(bone, pkg.widthScale, pkg.heightScale));
            bonePivotMc.put(bone.name, pivotPosition(bone, pkg.widthScale, pkg.heightScale));
        }

        int partsCompared = 0;
        int partsJointDiffer = 0;
        long vertsJointDiffer = 0;
        long vertsJointTotal = 0;
        double worstBakeDelta = 0.0;
        String worstBakeBone = "-";
        List<String> jointDiffs = new ArrayList<>();
        List<Row> rows = new ArrayList<>();

        for (Map.Entry<String, int[]> partEntry : fresh.parts.entrySet()) {
            String part = partEntry.getKey();
            if (!part.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            String bone = part.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length());
            YSMGeoModel.Bone b = geo.bonesByName.get(bone);
            if (b == null) {
                continue;
            }
            partsCompared++;
            int[] freshIdx = partEntry.getValue();
            int[] staleIdx = stale.parts.get(part);
            Set<Integer> jf = fresh.jointsOf(freshIdx);
            Set<Integer> js = stale.jointsOf(staleIdx);
            long total = freshIdx == null ? 0 : freshIdx.length;
            vertsJointTotal += total;
            if (!jf.equals(js)) {
                partsJointDiffer++;
                vertsJointDiffer += total;
                if (jointDiffs.size() < 400) {
                    jointDiffs.add(String.format(Locale.ROOT, "| %s | %s | %s | %d | %s |",
                            bone, jointNames(jf), jointNames(js), total, chainOf(b)));
                }
            }
            float[] ind = independent.get(bone);
            float[] fr = fresh.bounds(freshIdx);
            float[] st = stale.bounds(staleIdx);
            double d = fr == null || ind == null ? 0.0 : maxDelta(fr, ind);
            if (d > worstBakeDelta) {
                worstBakeDelta = d;
                worstBakeBone = bone;
            }
            rows.add(new Row(bone, chainOf(b), YSMJointMapper.isDirectlyMapped(b),
                    BoneAlternateForms.isAlternateForm(bone, alternateForms),
                    hiddenFresh.contains(bone), hiddenStale.contains(bone),
                    rtFresh.getOrDefault(bone, -1), rtStale.getOrDefault(bone, -1),
                    jf, js, firstOf(jf), firstOf(js), total, b.quads.size(),
                    fr, st, ind, bonePivotMc.get(bone)));
        }

        md.append(String.format(Locale.ROOT,
                "- bones=%d, bones with geometry=%d, fresh parts=%d, scale=(%.4f, %.4f)%n",
                geo.bonesByName.size(), geo.bonesByName.values().stream().filter(x -> !x.quads.isEmpty()).count(),
                fresh.parts.size(), pkg.widthScale, pkg.heightScale));
        md.append(String.format(Locale.ROOT,
                "- **fresh vs stale bake: %d of %d parts differ in joint id (%d of %d vertices, %.2f%%)**%n",
                partsJointDiffer, partsCompared, vertsJointDiffer, vertsJointTotal,
                100.0 * vertsJointDiffer / Math.max(1, vertsJointTotal)));
        md.append(String.format(Locale.ROOT,
                "- fresh JSON bounds vs the independent bind re-evaluation: worst corner delta %.6f (bone `%s`)%n",
                worstBakeDelta, worstBakeBone));
        md.append(String.format(Locale.ROOT,
                "- fresh vs stale geometry bounds: %s%n", geometryBoundsAgree(fresh, stale, rows)
                        ? "identical for every part (same bake; only the joint ids can differ)"
                        : "DIFFER for at least one part"));
        int hiddenFreshOnly = 0;
        int hiddenStaleOnly = 0;
        for (String h : hiddenFresh) {
            if (!hiddenStale.contains(h)) {
                hiddenFreshOnly++;
            }
        }
        for (String h : hiddenStale) {
            if (!hiddenFresh.contains(h)) {
                hiddenStaleOnly++;
            }
        }
        md.append(String.format(Locale.ROOT,
                "- hidden-in-default-form: fresh=%d, stale=%d (fresh-only=%d, stale-only=%d)%n",
                hiddenFresh.size(), hiddenStale.size(), hiddenFreshOnly, hiddenStaleOnly));

        // ---- pivots, fresh and stale, through the production computation ----
        YsmBindArmature.BindPivots pivotsFresh = pivots("fresh", freshRuntime, fresh);
        YsmBindArmature.BindPivots pivotsStale = pivots("stale", staleRuntime, stale);
        md.append("\n### bind pivots (production `computePivots`)\n\n");
        md.append("| joint | fresh pivot | stale pivot |\n|---|---|---|\n");
        for (int j = 0; j < JointTable.COUNT; j++) {
            md.append("| ").append(JointTable.nameOf(j))
                    .append(" | ").append(fmt(pivotsFresh.byJoint().get(j)))
                    .append(" | ").append(fmt(pivotsStale.byJoint().get(j)))
                    .append(" |\n");
        }
        md.append("\nwristR fresh=").append(fmt(pivotsFresh.wristR()))
                .append(", fistR fresh=").append(fmt(pivotsFresh.fistR())).append('\n');

        // ---- which bucket each leg joint's pivot could have come from ----
        md.append("\n### leg-joint pivot ladder (fresh inputs)\n\n");
        md.append("Every bone whose FRESH joint is that joint, bucketed by the tier the filter gives it and by ")
                .append("whether its name maps directly, with the group's highest geometry Y. The production ")
                .append("pivot is the top of the bucket the filter settled on; `topOf` averages the vertices ")
                .append("within ").append("0.05").append(" of that maximum, so the printed pivot can sit slightly below the maxY.\n\n");
        Set<String> baseForms = BoneAlternateForms.baseFormsPresent(geo.bonesByName.keySet().toArray(new String[0]));
        for (int joint : LEG_JOINTS) {
            // tier -> {mapped: topY, mappedBones, unmapped: topY, unmappedBones}
            Map<Integer, float[]> topByTier = new TreeMap<>();
            Map<Integer, List<String>> bonesByTier = new TreeMap<>();
            for (Row r : rows) {
                if (r.jointFreshOne != joint) {
                    continue;
                }
                int tier = YsmBindArmature.tierOf(r.bone, baseForms, hiddenFresh);
                float[] slot = topByTier.computeIfAbsent(tier, k -> new float[]{Float.NaN, Float.NaN});
                int bucket = r.mapped ? 0 : 1;
                float top = r.maxYFresh() == null ? Float.NaN : r.maxYFresh();
                if (Float.isFinite(top)) {
                    slot[bucket] = Float.isFinite(slot[bucket]) ? Math.max(slot[bucket], top) : top;
                }
                bonesByTier.computeIfAbsent(tier, k -> new ArrayList<>())
                        .add(r.bone + (r.mapped ? "[mapped]" : ""));
            }
            md.append("- `").append(JointTable.nameOf(joint)).append("` production pivot ")
                    .append(fmt(pivotsFresh.byJoint().get(joint))).append("; mapped-top=")
                    .append(topByTier.values().stream()
                            .mapToDouble(s -> Float.isFinite(s[0]) ? s[0] : -1.0).max().orElse(-1.0) > -1
                            ? String.format(Locale.ROOT, "%.3f", topByTier.values().stream()
                                    .mapToDouble(s -> Float.isFinite(s[0]) ? s[0] : -1.0).max().orElse(-1.0))
                            : "-")
                    .append(", any-top=")
                    .append(String.format(Locale.ROOT, "%.3f", topByTier.values().stream()
                            .mapToDouble(s -> Math.max(Float.isFinite(s[0]) ? s[0] : -1.0,
                                    Float.isFinite(s[1]) ? s[1] : -1.0)).max().orElse(-1.0)))
                    .append('\n');
            for (Map.Entry<Integer, float[]> e : topByTier.entrySet()) {
                List<String> bones = bonesByTier.get(e.getKey());
                md.append("    - tier ").append(e.getKey())
                        .append(": mapped top=").append(Float.isFinite(e.getValue()[0])
                                ? String.format(Locale.ROOT, "%.3f", e.getValue()[0]) : "-")
                        .append(", unmapped top=").append(Float.isFinite(e.getValue()[1])
                                ? String.format(Locale.ROOT, "%.3f", e.getValue()[1]) : "-")
                        .append(" (").append(String.join(", ", bones.size() > 12
                                ? bones.subList(0, 12) : bones))
                        .append(bones.size() > 12 ? ", +" + (bones.size() - 12) + " more" : "")
                        .append(")\n");
            }
        }

        // ---- structurally identified shoe: the lowest geometry of the whole model ----
        float modelMinY = Float.MAX_VALUE;
        for (Row r : rows) {
            if (r.minYFresh() != null) {
                modelMinY = Math.min(modelMinY, r.minYFresh());
            }
        }
        md.append(String.format(Locale.ROOT,
                "%n### the model's lowest geometry (structurally: the foot/shoe tier), model minY=%.3f%n%n", modelMinY));
        md.append("| bone | chain | jointF | jointS | verts | minY | maxY | centerX | pivotY | mapped | variant | hiddenF | hiddenS |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<Row> lowest = new ArrayList<>(rows);
        lowest.sort(Comparator.comparingDouble(r -> r.minYFresh() == null ? Float.MAX_VALUE : r.minYFresh()));
        int shownLow = 0;
        for (Row r : lowest) {
            if (r.minYFresh() == null || r.minYFresh() > modelMinY + 0.15f || shownLow >= 40) {
                continue;
            }
            shownLow++;
            md.append(r.shortRow()).append('\n');
        }

        // ---- legs: every part whose joint is a leg joint, lowest first ----
        md.append("\n### parts bound to a leg joint (any leg joint, fresh or stale)\n\n");
        md.append("`jointF/jointS` = the joint of the part's vertices in the fresh / stale bake; ")
                .append("`rtF/rtS` = the same bone's `joint` field in the fresh / stale runtime table.\n\n");
        md.append("| bone | chain | jointF | jointS | rtF | rtS | verts | quads | minY | maxY | centerX | pivotY | mapped | variant | hiddenF | hiddenS |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<Row> legRows = new ArrayList<>();
        for (Row r : rows) {
            if (r.onLegJoint()) {
                legRows.add(r);
            }
        }
        legRows.sort(Comparator.comparingDouble(r -> r.minYFresh() == null ? Float.MAX_VALUE : r.minYFresh()));
        for (Row r : legRows) {
            md.append(r.legRow()).append('\n');
        }

        md.append("\n**lowest geometry per leg side** (position, not name):\n\n");
        for (int[] sidePair : new int[][]{{JointTable.THIGH_R, JointTable.LEG_R, JointTable.KNEE_R},
                {JointTable.THIGH_L, JointTable.LEG_L, JointTable.KNEE_L}}) {
            List<Row> side = new ArrayList<>();
            for (Row r : legRows) {
                if (r.onSide(sidePair)) {
                    side.add(r);
                }
            }
            side.sort(Comparator.comparingDouble(r -> r.minYFresh() == null ? Float.MAX_VALUE : r.minYFresh()));
            md.append("- `").append(JointTable.nameOf(sidePair[0])).append("` side: ");
            int shown = 0;
            for (Row r : side) {
                if (shown++ >= 8) {
                    break;
                }
                md.append('`').append(r.bone).append("`[F=").append(r.jointNamesFresh())
                        .append(",S=").append(r.jointNamesStale())
                        .append(",y=").append(r.minYFresh() == null ? "-"
                                : String.format(Locale.ROOT, "%.3f..%.3f", r.minYFresh(), r.maxYFresh()))
                        .append(",v=").append(r.verts).append("] ");
            }
            md.append('\n');
        }

        md.append("\n### physics parts written at conversion time (the battle-mode per-part transforms)\n\n");
        md.append("- fresh: ").append(physicsSummary(freshRuntime)).append('\n');
        md.append("- stale: ").append(physicsSummary(staleRuntime)).append('\n');

        md.append("\n### the parts whose joint the fresh rules change\n\n");
        md.append("| bone | fresh joint | stale joint | verts | chain |\n|---|---|---|---|---|\n");
        for (String line : jointDiffs) {
            md.append(line).append('\n');
        }

        Path tsv = Paths.get("build", "reports", "ysm-shoe-leg-" + safe(modelId) + ".tsv");
        StringBuilder t = new StringBuilder();
        t.append("bone\tchain\tmapped\tvariant\thiddenFresh\thiddenStale\tjointFresh\tjointStale\trtFresh\trtStale")
                .append("\tverts\tquads\tminYF\tmaxYF\tminYI\tmaxYI\tminYS\tmaxYS")
                .append("\tminXF\tmaxXF\tminZF\tmaxZF\tpivotX\tpivotY\tpivotZ\tbakeDelta\n");
        for (Row r : rows) {
            t.append(r.tsv()).append('\n');
        }
        Files.createDirectories(tsv.getParent());
        Files.writeString(tsv, t.toString(), StandardCharsets.UTF_8);
        md.append("\n[tsv] ").append(tsv.toAbsolutePath()).append('\n');

        return md.toString();
    }

    private static YsmBindArmature.BindPivots pivots(String label, JsonObject runtime, MeshData mesh) {
        JsonArray bones = runtime.getAsJsonArray("bones");
        int n = bones.size();
        String[] names = new String[n];
        int[] joints = new int[n];
        boolean[] mapped = new boolean[n];
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            JsonObject b = bones.get(i).getAsJsonObject();
            names[i] = b.get("name").getAsString();
            joints[i] = b.get("joint").getAsInt();
            mapped[i] = b.get("mapped").getAsBoolean();
            index.put(names[i], i);
        }
        List<YsmBindArmature.BoneGeometry> parts = new ArrayList<>();
        for (Map.Entry<String, int[]> e : mesh.parts.entrySet()) {
            if (!e.getKey().startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            String boneName = e.getKey().substring(EFMeshJsonWriter.BONE_PART_PREFIX.length());
            Integer boneIdx = index.get(boneName);
            if (boneIdx == null) {
                continue;
            }
            List<Vector3f> vertices = new ArrayList<>();
            for (int p : e.getValue()) {
                vertices.add(new Vector3f(mesh.positionsMc[3 * p], mesh.positionsMc[3 * p + 1],
                        mesh.positionsMc[3 * p + 2]));
            }
            parts.add(new YsmBindArmature.BoneGeometry(boneIdx, vertices));
        }
        Set<String> hidden = YSMRuntimeModel.computeDefaultHiddenBoneNames(runtime);
        YsmBindArmature.GeometryInput input =
                new YsmBindArmature.GeometryInput(label + ":audit", names, joints, mapped, hidden, parts);
        List<String> warnings = new ArrayList<>();
        YsmBindArmature.GeometryData geometry = YsmBindArmature.collectGeometry(input, warnings::add);
        return YsmBindArmature.computePivots(input, geometry, warnings::add);
    }

    private static String physicsSummary(JsonObject runtime) {
        if (!runtime.has("physics")) {
            return "no physics section (name-based fallback)";
        }
        JsonObject physics = runtime.getAsJsonObject("physics");
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : physics.entrySet()) {
            if (!"animation".equals(e.getKey())) {
                names.add(e.getKey());
            }
        }
        String animation = physics.has("animation") ? physics.get("animation").getAsString() : "-";
        List<String> leg = new ArrayList<>();
        for (String name : names) {
            String normalized = YSMJointMapper.normalize(name);
            if (normalized.contains("leg") || normalized.contains("foot") || normalized.contains("shoe")
                    || normalized.contains("thigh") || normalized.contains("xie") || normalized.contains("jiao")) {
                leg.add(name);
            }
        }
        return names.size() + " parts, animation=" + animation + ", leg/foot/shoe-named among them: " + leg;
    }

    private static Map<String, Integer> runtimeJoints(JsonObject runtime) {
        Map<String, Integer> out = new HashMap<>();
        for (JsonElement e : runtime.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            out.put(b.get("name").getAsString(), b.get("joint").getAsInt());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // mesh JSON reading
    // ------------------------------------------------------------------

    private static final class MeshData {
        final float[] positionsMc;
        final int[] vertexJoint;
        final Map<String, int[]> parts;

        MeshData(float[] positionsMc, int[] vertexJoint, Map<String, int[]> parts) {
            this.positionsMc = positionsMc;
            this.vertexJoint = vertexJoint;
            this.parts = parts;
        }

        Set<Integer> jointsOf(int[] indices) {
            Set<Integer> out = new LinkedHashSet<>();
            if (indices == null) {
                return out;
            }
            for (int i : indices) {
                if (i >= 0 && i < vertexJoint.length) {
                    out.add(vertexJoint[i]);
                }
            }
            return out;
        }

        float[] bounds(int[] indices) {
            if (indices == null || indices.length == 0) {
                return null;
            }
            float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                    -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int idx : indices) {
                if (idx < 0 || idx * 3 + 2 >= positionsMc.length) {
                    continue;
                }
                for (int c = 0; c < 3; c++) {
                    float v = positionsMc[idx * 3 + c];
                    b[c] = Math.min(b[c], v);
                    b[3 + c] = Math.max(b[3 + c], v);
                }
            }
            return b[0] == Float.MAX_VALUE ? null : b;
        }
    }

    private static MeshData readMesh(Path file) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject vertices = root.getAsJsonObject("vertices");
        JsonArray positions = vertices.getAsJsonObject("positions").getAsJsonArray("array");
        int count = positions.size() / 3;
        float[] mc = new float[count * 3];
        for (int i = 0; i < count; i++) {
            float x = positions.get(i * 3).getAsFloat();
            float y = positions.get(i * 3 + 1).getAsFloat();
            float z = positions.get(i * 3 + 2).getAsFloat();
            // The loader maps the JSON's Blender frame back with (x, y, z)_json -> (x, z, -y)_mc.
            mc[i * 3] = x;
            mc[i * 3 + 1] = z;
            mc[i * 3 + 2] = -y;
        }
        JsonArray vindices = vertices.getAsJsonObject("vindices").getAsJsonArray("array");
        int[] joint = new int[count];
        for (int i = 0; i < count; i++) {
            joint[i] = vindices.get(i * 2).getAsInt();
        }
        Map<String, int[]> parts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : vertices.getAsJsonObject("parts").entrySet()) {
            JsonArray array = e.getValue().getAsJsonObject().getAsJsonArray("array");
            int[] indices = new int[array.size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = array.get(i).getAsInt();
            }
            parts.put(e.getKey(), indices);
        }
        return new MeshData(mc, joint, parts);
    }

    // ------------------------------------------------------------------
    // the independent bind re-evaluation: parent chain, composer, corners
    // ------------------------------------------------------------------

    private static float[] independentBounds(YSMGeoModel.Bone bone, float scaleW, float scaleH) {
        if (bone.quads.isEmpty()) {
            return null;
        }
        Matrix4f world = worldFromParentChain(bone);
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (YSMGeoModel.Quad quad : bone.quads) {
            for (Vector3f corner : quad.positions) {
                if (corner == null) {
                    continue;
                }
                Vector3f p = new Vector3f(corner).mulPosition(world);
                float[] mc = {p.x * scaleW, p.y * scaleH, p.z * scaleW};
                for (int c = 0; c < 3; c++) {
                    b[c] = Math.min(b[c], mc[c]);
                    b[3 + c] = Math.max(b[3 + c], mc[c]);
                }
            }
        }
        return b;
    }

    /** Where the bone's own pivot lands in model space: the parent chain's composition, not the bone's. */
    private static Vector3f pivotPosition(YSMGeoModel.Bone bone, float scaleW, float scaleH) {
        Vector3f p = new Vector3f(bone.pivotX, bone.pivotY, bone.pivotZ)
                .mulPosition(bone.parent == null ? new Matrix4f() : worldFromParentChain(bone.parent));
        return new Vector3f(p.x * scaleW, p.y * scaleH, p.z * scaleW);
    }

    /**
     * One bone's bind world transform, rebuilt from the bone's own {@code parent} links: the ancestor
     * chain is collected upwards and composed root-first. Deliberately a different traversal from the
     * writer's top-down recursion, and it never consults the writer.
     */
    private static Matrix4f worldFromParentChain(YSMGeoModel.Bone bone) {
        List<YSMGeoModel.Bone> chain = new ArrayList<>();
        for (YSMGeoModel.Bone b = bone; b != null; b = b.parent) {
            chain.add(b);
        }
        java.util.Collections.reverse(chain);
        Matrix4f world = new Matrix4f();
        for (YSMGeoModel.Bone b : chain) {
            Matrix4f local = new Matrix4f();
            local.translate(b.pivotX, b.pivotY, b.pivotZ);
            local.rotateZ(b.rotZ);
            local.rotateY(b.rotY);
            local.rotateX(b.rotX);
            local.translate(-b.pivotX, -b.pivotY, -b.pivotZ);
            world.mul(local);
        }
        return world;
    }

    private static double maxDelta(float[] a, float[] b) {
        double worst = 0.0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            worst = Math.max(worst, Math.abs(a[i] - b[i]));
        }
        return worst;
    }

    private static boolean geometryBoundsAgree(MeshData fresh, MeshData stale, List<Row> rows) {
        for (Row r : rows) {
            if (r.minFresh == null || r.minStale == null || maxDelta(r.minFresh, r.minStale) > 1e-3) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // rows
    // ------------------------------------------------------------------

    private static final class Row {
        final String bone;
        final String chain;
        final boolean mapped;
        final boolean variant;
        final boolean hiddenFresh;
        final boolean hiddenStale;
        final int rtFresh;
        final int rtStale;
        final Set<Integer> jointsFresh;
        final Set<Integer> jointsStale;
        final int jointFreshOne;
        final int jointStaleOne;
        final long verts;
        final int quads;
        final float[] minFresh;
        final float[] minStale;
        final float[] minIndependent;
        final Vector3f pivot;

        Row(String bone, String chain, boolean mapped, boolean variant, boolean hiddenFresh, boolean hiddenStale,
            int rtFresh, int rtStale, Set<Integer> jointsFresh, Set<Integer> jointsStale,
            int jointFreshOne, int jointStaleOne, long verts, int quads,
            float[] minFresh, float[] minStale, float[] minIndependent, Vector3f pivot) {
            this.bone = bone;
            this.chain = chain;
            this.mapped = mapped;
            this.variant = variant;
            this.hiddenFresh = hiddenFresh;
            this.hiddenStale = hiddenStale;
            this.rtFresh = rtFresh;
            this.rtStale = rtStale;
            this.jointsFresh = jointsFresh;
            this.jointsStale = jointsStale;
            this.jointFreshOne = jointFreshOne;
            this.jointStaleOne = jointStaleOne;
            this.verts = verts;
            this.quads = quads;
            this.minFresh = minFresh;
            this.minStale = minStale;
            this.minIndependent = minIndependent;
            this.pivot = pivot;
        }

        Float minYFresh() {
            return minFresh == null ? null : minFresh[1];
        }

        Float maxYFresh() {
            return minFresh == null ? null : minFresh[4];
        }

        String jointNamesFresh() {
            return jointNames(jointsFresh);
        }

        String jointNamesStale() {
            return jointNames(jointsStale);
        }

        boolean onLegJoint() {
            return isLegJoint(jointFreshOne) || isLegJoint(jointStaleOne);
        }

        boolean onSide(int[] side) {
            for (int j : side) {
                if (jointFreshOne == j || jointStaleOne == j) {
                    return true;
                }
            }
            return false;
        }

        String shortRow() {
            return String.format(Locale.ROOT, "| %s | %s | %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %s |",
                    bone, chain, jointNamesFresh(), jointNamesStale(), verts,
                    minYFresh() == null ? "-" : f(minYFresh()),
                    minYFresh() == null ? "-" : f(maxYFresh()),
                    minFresh == null ? "-" : f((minFresh[0] + minFresh[3]) * 0.5f),
                    pivot == null ? "-" : f(pivot.y), mapped, variant, hiddenFresh, hiddenStale);
        }

        String legRow() {
            return String.format(Locale.ROOT,
                    "| %s | %s | %s | %s | %s | %s | %d | %d | %s | %s | %s | %s | %s | %s | %s | %s |",
                    bone, chain, jointNamesFresh(), jointNamesStale(),
                    JointTable.nameOf(rtFresh), JointTable.nameOf(rtStale), verts, quads,
                    minYFresh() == null ? "-" : f(minYFresh()),
                    minYFresh() == null ? "-" : f(maxYFresh()),
                    minFresh == null ? "-" : f((minFresh[0] + minFresh[3]) * 0.5f),
                    pivot == null ? "-" : f(pivot.y), mapped, variant, hiddenFresh, hiddenStale);
        }

        String tsv() {
            StringBuilder sb = new StringBuilder();
            sb.append(bone).append('\t').append(chain).append('\t').append(mapped).append('\t').append(variant)
                    .append('\t').append(hiddenFresh).append('\t').append(hiddenStale)
                    .append('\t').append(jointNamesFresh()).append('\t').append(jointNamesStale())
                    .append('\t').append(JointTable.nameOf(rtFresh)).append('\t').append(JointTable.nameOf(rtStale))
                    .append('\t').append(verts).append('\t').append(quads)
                    .append('\t').append(minFresh == null ? "-" : f(minFresh[1]))
                    .append('\t').append(minFresh == null ? "-" : f(minFresh[4]))
                    .append('\t').append(minIndependent == null ? "-" : f(minIndependent[1]))
                    .append('\t').append(minIndependent == null ? "-" : f(minIndependent[4]))
                    .append('\t').append(minStale == null ? "-" : f(minStale[1]))
                    .append('\t').append(minStale == null ? "-" : f(minStale[4]))
                    .append('\t').append(minFresh == null ? "-" : f(minFresh[0]))
                    .append('\t').append(minFresh == null ? "-" : f(minFresh[3]))
                    .append('\t').append(minFresh == null ? "-" : f(minFresh[2]))
                    .append('\t').append(minFresh == null ? "-" : f(minFresh[5]));
            if (pivot != null) {
                sb.append('\t').append(f(pivot.x)).append('\t').append(f(pivot.y)).append('\t').append(f(pivot.z));
            } else {
                sb.append("\t-\t-\t-");
            }
            double d = minFresh == null || minIndependent == null ? 0 : maxDelta(minFresh, minIndependent);
            sb.append('\t').append(String.format(Locale.ROOT, "%.6f", d));
            return sb.toString();
        }
    }

    private static int firstOf(Set<Integer> joints) {
        for (int j : joints) {
            return j;
        }
        return -1;
    }

    private static String chainOf(YSMGeoModel.Bone bone) {
        List<String> names = new ArrayList<>();
        int depth = 0;
        for (YSMGeoModel.Bone b = bone; b != null && depth < 6; b = b.parent, depth++) {
            names.add(b.name);
        }
        java.util.Collections.reverse(names);
        boolean truncated = false;
        if (bone != null && bone.parent != null) {
            int total = 0;
            for (YSMGeoModel.Bone b = bone.parent; b != null; b = b.parent) {
                total++;
            }
            truncated = total > 5;
        }
        return (truncated ? "... -> " : "") + String.join(" -> ", names);
    }

    private static boolean isLegJoint(int joint) {
        for (int j : LEG_JOINTS) {
            if (j == joint) {
                return true;
            }
        }
        return false;
    }

    private static String jointNames(Set<Integer> joints) {
        if (joints == null || joints.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int j : joints) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(JointTable.nameOf(j));
        }
        return sb.toString();
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String fmt(Vector3f v) {
        return v == null ? "null" : String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }

    private static String safe(String modelId) {
        return modelId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String configuredConfigRoot() {
        String property = System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY, "");
        if (!property.isEmpty()) {
            return property;
        }
        String fromEnvironment = System.getenv(YsmModelPackage.CONFIG_ROOT_ENV);
        return fromEnvironment == null ? "" : fromEnvironment;
    }

    private static Path convertedPackRoot(Path configRoot) {
        Path configDir = configRoot.toAbsolutePath().getParent();
        if (configDir == null) {
            return null;
        }
        Path pack = configDir.resolve("ysm_epicfight_compat").resolve("resourcepack").resolve("assets")
                .resolve("ysm_epicfight_compat");
        return Files.isDirectory(pack.resolve("animmodels/entity")) ? pack : null;
    }
}
