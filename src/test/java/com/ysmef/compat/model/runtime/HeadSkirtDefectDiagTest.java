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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measurement, not an assertion ("round 19" diagnostics): the reported "head separates from the body"
 * (STRESSTEST_SMT_Nahobino) and "the skirt is skewed" (兽耳酱x1 / NagaU_Kemomimi).
 *
 * <p>Everything here is read off the production code paths over the real {@code .ysm} packages:
 * {@link EFMeshJsonWriter#write} for the mesh/runtime the current rules would bake,
 * {@link YsmBindArmature#collectGeometry} + {@link YsmBindArmature#computePivots} for the pivots, and
 * {@link YsmJointMapper#resolveJointId(YSMGeoModel.Bone, YSMGeoModel)} for the joint of every bone.
 *
 * <p>What it prints, per model:
 *
 * <ol>
 *   <li>the chest/head pivot ladder: every bone whose geometry lands on the Chest or Head joint,
 *       bucketed by tier, with its own bounds, the mapped ancestor that supplied the joint, and how
 *       far the joint's settled pivot sits from the model's own author-chosen {@code AllHead} /
 *       {@code MHead} / {@code Head} pivots;</li>
 *   <li>the joint each piece of head-region geometry actually binds to;</li>
 *   <li>every physics-simulated bone: geometry, bounds, the joint it is bound to, whether it owns the
 *       geometry drawn around it, and what the runtime's physics section selected;</li>
 *   <li>the skirt bones identified structurally (the YSM physics-bone list from the user's log, plus
 *       every bone whose geometry crosses the hip plane inside the leg band).</li>
 * </ol>
 *
 * <p>Opt-in: skipped unless the YSM config root is given. Writes {@code build/reports/ysm-head-skirt-*.md}.
 */
class HeadSkirtDefectDiagTest {

    private static final List<String> MODELS = List.of(
            "STRESSTEST_SMT_Nahobino.ysm", "兽耳酱x1.ysm", "NagaU_Kemomimi.ysm");

    /** The physics-bone list the game logged for 兽耳酱x1 (its own converter's output). */
    private static final List<String> KEMOMIMI_PHYSICS_BONES = List.of(
            "X_T4_1", "X_yiqun1", "X_T3_1", "X_T6_1", "X_Hair1", "X_T5_1", "X_liuhai_F_1", "X_fawei1",
            "X_T7_1", "Left_ear_F_1", "X_T2_1", "X_qunzi1", "Right_ear_R_1", "X_liuhai_Z_1", "X_liuhai_R_1");

    @Test
    void dumpTheDiagnostic() throws Exception {
        String configured = configuredConfigRoot();
        assumeTrue(!configured.isEmpty(),
                "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + "=<config/yes_steve_model> (or the "
                        + YsmModelPackage.CONFIG_ROOT_ENV + " environment variable) to run the diagnostic");

        StringBuilder md = new StringBuilder();
        md.append("# Head / skirt defect diagnostic (round 19)\n\n");
        md.append("Coordinates: the runtime Minecraft frame (up = +Y), blocks, after the model's own ")
                .append("width/height scale. `joint` = `YSMJointMapper.resolveJointId(bone, model)`; ")
                .append("`bakeJoint` = the joint the written mesh JSON actually binds the bone's vertices to.\n");

        for (String model : MODELS) {
            md.append(modelReport(model));
        }

        Path out = Paths.get("build", "reports", "ysm-head-skirt-diag.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
        System.out.println(md);
    }

    private static String modelReport(String modelId) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("\n## ").append(modelId).append("\n\n");

        YsmModelPackage pkg = YsmModelPackage.load(modelId);
        if (pkg == null || pkg.geometry == null) {
            md.append("**could not load the package**\n");
            return md.toString();
        }
        YSMGeoModel geo = pkg.geometry;
        Path tmp = Files.createTempDirectory("ysm-head-skirt");
        Path meshFile = tmp.resolve("mesh.json");
        Path runtimeFile = tmp.resolve("runtime.json");
        EFMeshJsonWriter.write(pkg, meshFile, runtimeFile, "ysm_epicfight_compat:textures/audit.png");

        MeshData mesh = readMesh(meshFile);
        JsonObject runtime = JsonParser.parseString(
                Files.readString(runtimeFile, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Integer> runtimeJoint = new HashMap<>();
        Map<String, Boolean> runtimeMapped = new HashMap<>();
        Map<String, String> runtimeParent = new HashMap<>();
        for (JsonElement e : runtime.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            runtimeJoint.put(b.get("name").getAsString(), b.get("joint").getAsInt());
            runtimeMapped.put(b.get("name").getAsString(), b.get("mapped").getAsBoolean());
            runtimeParent.put(b.get("name").getAsString(),
                    b.has("parent") ? b.get("parent").getAsString() : "");
        }
        Set<String> hidden = YSMRuntimeModel.computeDefaultHiddenBoneNames(runtime);
        Set<String> baseForms = BoneAlternateForms.baseFormsPresent(
                geo.bonesByName.keySet().toArray(new String[0]));

        // ---- per-bone rows, geometry in model space ----
        List<Bone> rows = new ArrayList<>();
        for (YSMGeoModel.Bone bone : geo.bonesByName.values()) {
            float[] bounds = mesh.bounds(mesh.parts.get(EFMeshJsonWriter.BONE_PART_PREFIX + bone.name));
            int[] own = mesh.parts.get(EFMeshJsonWriter.BONE_PART_PREFIX + bone.name);
            Set<Integer> bakeJoints = mesh.jointsOf(own);
            Integer joint = runtimeJoint.get(bone.name);
            rows.add(new Bone(bone, bounds, own == null ? 0 : own.length,
                    joint == null ? -1 : joint,
                    runtimeMapped.getOrDefault(bone.name, false),
                    bakeJoints, hidden.contains(bone.name),
                    YsmBindArmature.tierOf(bone.name, baseForms, hidden),
                    pivotPosition(bone, pkg.widthScale, pkg.heightScale),
                    subtreeBounds(bone, pkg.widthScale, pkg.heightScale),
                    chainOf(bone)));
        }

        md.append(String.format(Locale.ROOT,
                "- bones=%d, bones with own geometry=%d, mesh parts=%d, scale=(%.4f, %.4f), "
                        + "hidden-in-default-form=%d%n",
                geo.bonesByName.size(), rows.stream().filter(r -> r.bounds != null).count(),
                mesh.parts.size(), pkg.widthScale, pkg.heightScale, hidden.size()));

        // ---- production pivots, through the production filter ----
        Map<String, Integer> boneIndex = new HashMap<>();
        String[] names = new String[runtimeJoint.size()];
        int[] joints = new int[names.length];
        boolean[] mapped = new boolean[names.length];
        int n = 0;
        for (JsonElement e : runtime.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            names[n] = b.get("name").getAsString();
            joints[n] = b.get("joint").getAsInt();
            mapped[n] = b.get("mapped").getAsBoolean();
            boneIndex.put(names[n], n);
            n++;
        }
        List<YsmBindArmature.BoneGeometry> parts = new ArrayList<>();
        for (Map.Entry<String, int[]> e : mesh.parts.entrySet()) {
            if (!e.getKey().startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            Integer idx = boneIndex.get(e.getKey().substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (idx == null) {
                continue;
            }
            List<Vector3f> verts = new ArrayList<>();
            for (int p : e.getValue()) {
                verts.add(new Vector3f(mesh.positionsMc[3 * p], mesh.positionsMc[3 * p + 1],
                        mesh.positionsMc[3 * p + 2]));
            }
            parts.add(new YsmBindArmature.BoneGeometry(idx, verts));
        }
        YsmBindArmature.GeometryInput input =
                new YsmBindArmature.GeometryInput(modelId, names, joints, mapped, hidden, parts);
        List<String> warnings = new ArrayList<>();
        YsmBindArmature.GeometryData geometryData = YsmBindArmature.collectGeometry(input, warnings::add);
        YsmBindArmature.BindPivots bind = YsmBindArmature.computePivots(input, geometryData, warnings::add);
        for (String w : warnings) {
            md.append("- [bind warn] ").append(w).append('\n');
        }
        md.append("\n### production bind pivots\n\n");
        md.append("| joint | pivot |\n|---|---|\n");
        for (int j = 0; j < JointTable.COUNT; j++) {
            if (bind.byJoint().containsKey(j)) {
                md.append("| ").append(JointTable.nameOf(j)).append(" | ")
                        .append(fmt(bind.byJoint().get(j))).append(" |\n");
            }
        }

        // ---- who sets the neck: the top of the Chest joint's settled geometry ----
        md.append("\n### which geometry sets the neck (top of the Chest joint's settled set)\n\n");
        List<Vector3f> chestVerts = geometryData.byJoint().getOrDefault(8, List.of());
        md.append(neckAnalysis(rows, chestVerts, bind, geometryData));

        // ---- who sets the hip: the top of the thigh geometry ----
        md.append("\n### which geometry sets the hip (top of each thigh joint's settled set)\n\n");
        for (int j : new int[]{JointTable.THIGH_R, JointTable.THIGH_L}) {
            List<Vector3f> v = geometryData.byJoint().getOrDefault(j, List.of());
            md.append("- ").append(JointTable.nameOf(j)).append(" settled geometry: ")
                    .append(v.size()).append(" vertex slots, top=")
                    .append(fmt(topOf(v))).append(", chosen=")
                    .append(fmt(bind.byJoint().get(j))).append('\n');
        }

        // ---- the Chest / Head joint geometry, bone by bone ----
        md.append("\n### bones whose geometry binds to Chest (8) or Head (9)\n\n");
        md.append("`chain` = the bone's nearest ancestors, `->`-separated, nearest last. ")
                .append("`src` = the mapped ancestor that supplied the joint.\n\n");
        md.append("| bone | chain | src | joint | tier | mapped | hidden | verts | minY | maxY | centerX | centerZ | minZ | maxZ |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Bone r : rows) {
            if (r.joint != 8 && r.joint != 9) {
                continue;
            }
            md.append(r.row()).append('\n');
        }

        // ---- head-region geometry: the highest geometry of the whole model, and what it binds to ----
        md.append("\n### the model's highest geometry (the head/skull tier)\n\n");
        md.append("| bone | chain | joint | bakeJoint | verts | minY | maxY | centerX | centerY | centerZ |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        List<Bone> highest = new ArrayList<>(rows);
        highest.removeIf(r -> r.bounds == null);
        highest.sort(Comparator.comparingDouble((Bone r) -> -r.bounds[4]));
        for (int i = 0; i < Math.min(30, highest.size()); i++) {
            md.append(highest.get(i).headRow()).append('\n');
        }

        // ---- head attachment: the neck seam ----
        md.append("\n### the neck seam, structurally\n\n");
        md.append(neckSeam(rows, bind));

        // ---- authoured head controls ----
        md.append("\n### the model's authored central head controls\n\n");
        md.append("| bone | chain | pivot (model space) | own geom minY..maxY | joint | tier |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (Bone r : rows) {
            String norm = YSMJointMapper.normalize(r.bone.name);
            if (norm.equals("allhead") || norm.equals("mhead") || norm.equals("head")
                    || norm.equals("neck") || norm.equals("upperbody") || norm.equals("upbody")
                    || norm.equals("allbody") || norm.equals("downbody") || norm.equals("waist")) {
                md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %d |%n",
                        r.bone.name, r.chain, fmt(r.pivot),
                        r.bounds == null ? "-" : String.format(Locale.ROOT, "%.3f..%.3f", r.bounds[1], r.bounds[4]),
                        JointTable.nameOf(r.joint), r.tier));
            }
        }

        // ---- skirt / physics bones ----
        md.append("\n").append(skirtSection(rows, runtime, geometryData, bind, modelId));

        // ---- neck: candidate rules, measured ----
        md.append("\n").append(neckCandidates(rows, geometryData, bind, pkg));

        // ---- the cache the game used before the generator bump ----
        md.append("\n").append(cachedComparison(modelId, pkg));

        // ---- where the physics actually pivots each cloth bone ----
        md.append("\n").append(physicsPivotSection(modelId, runtime, mesh, geo, pkg, hidden));
        return md.toString();
    }

    // ------------------------------------------------------------------
    // physics: the pivot, rest direction and lever of every cloth bone
    // ------------------------------------------------------------------

    /**
     * What {@code YsmPhysicsParts.buildSegment} measures for each bone the game simulates: the pivot it
     * swings about ({@code bindPivot}, i.e. the bone's own authored pivot carried through the same
     * {@code T(p)R T(-p)} chain the mesh writer baked the vertices with), the geometry centroid, the
     * rest direction ({@code centroid - pivot}) and the lever. Every number here is computed the way
     * the production code computes it; only the {@code YSMMesh} object is replaced by the mesh JSON.
     */
    private static String physicsPivotSection(String modelId, JsonObject runtime, MeshData mesh,
                                              YSMGeoModel geo, YsmModelPackage pkg, Set<String> hidden) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n### physics: the pivot every simulated cloth bone swings about\n\n");

        JsonArray bones = runtime.getAsJsonArray("bones");
        int n = bones.size();
        String[] name = new String[n];
        int[] parent = new int[n];
        int[] joint = new int[n];
        boolean[] mapped = new boolean[n];
        float[] px = new float[n];
        float[] py = new float[n];
        float[] pz = new float[n];
        float[] rx = new float[n];
        float[] ry = new float[n];
        float[] rz = new float[n];
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            JsonObject b = bones.get(i).getAsJsonObject();
            name[i] = b.get("name").getAsString();
            joint[i] = b.get("joint").getAsInt();
            mapped[i] = b.get("mapped").getAsBoolean();
            JsonArray pivot = b.getAsJsonArray("pivot");
            px[i] = pivot.get(0).getAsFloat();
            py[i] = pivot.get(1).getAsFloat();
            pz[i] = pivot.get(2).getAsFloat();
            JsonArray rot = b.getAsJsonArray("rot");
            rx[i] = rot.get(0).getAsFloat();
            ry[i] = rot.get(1).getAsFloat();
            rz[i] = rot.get(2).getAsFloat();
            index.put(name[i], i);
        }
        for (int i = 0; i < n; i++) {
            JsonObject b = bones.get(i).getAsJsonObject();
            String parentName = b.has("parent") ? b.get("parent").getAsString() : "";
            Integer p = parentName.isEmpty() ? null : index.get(parentName);
            parent[i] = p == null ? -1 : p;
        }
        Matrix4f[] world = new Matrix4f[n];
        for (int i = 0; i < n; i++) {
            world[i] = bindWorld(i, parent, px, py, pz, rx, ry, rz, world, 0);
        }

        // The joints the physics is written into, from the runtime's own selection.
        Set<String> physicsBones = new java.util.LinkedHashSet<>();
        if (runtime.has("physics")) {
            JsonObject physics = runtime.getAsJsonObject("physics");
            for (Map.Entry<String, JsonElement> e : physics.entrySet()) {
                if ("animation".equals(e.getKey())) {
                    continue;
                }
                JsonElement value = e.getValue();
                if (value.isJsonArray()) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        collectBoneNames(item, e.getKey(), physicsBones);
                    }
                } else {
                    collectBoneNames(value, e.getKey(), physicsBones);
                }
            }
        }

        float scaleX = pkg.widthScale;
        float scaleY = pkg.heightScale;
        Set<String> namedBones = new java.util.LinkedHashSet<>(physicsBones);
        namedBones.addAll(KEMOMIMI_PHYSICS_BONES);
        sb.append(String.format(Locale.ROOT,
                "- runtime `physics` section names %d bone(s); the game's own [physics] log named %d "
                        + "simulated bone(s) for the 兽耳酱x1 twin; scale=(%.4f, %.4f)%n",
                physicsBones.size(), KEMOMIMI_PHYSICS_BONES.size(), scaleX, scaleY));
        sb.append(String.format(Locale.ROOT,
                "- **a bone is simulated only when it owns its own drawn geometry and is not directly "
                        + "mapped** (`YsmPhysicsParts.buildSegment`); the scoreboard below is over the "
                        + "%d bone(s) named by either source%n%n", namedBones.size()));

        sb.append("| bone | joint | mapped | own geom slots | authored pivot (bind, scaled) | geometry centroid | rest = centroid-pivot | lever | rest points | physics says |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (String boneName : namedBones) {
            Integer i = index.get(boneName);
            if (i == null) {
                sb.append(String.format(Locale.ROOT, "| %s | - | - | - | - | - | - | - | - | not in the runtime table |%n",
                        boneName));
                continue;
            }
            int[] part = mesh.parts.get(EFMeshJsonWriter.BONE_PART_PREFIX + boneName);
            Vector3f pivot = new Vector3f(px[i], py[i], pz[i]);
            world[i].transformPosition(pivot);
            pivot.mul(scaleX, scaleY, scaleX);
            Vector3f centroid = centroidOf(mesh, part);
            Vector3f rest = centroid == null ? null : new Vector3f(centroid).sub(pivot);
            float lever = rest == null ? 0 : rest.length();
            sb.append(String.format(Locale.ROOT,
                    "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |%n",
                    boneName, JointTable.nameOf(joint[i]), mapped[i],
                    part == null ? "0" : String.valueOf(part.length),
                    fmtShort(pivot), fmtShort(centroid), fmtShort(rest),
                    String.format(Locale.ROOT, "%.3f", lever), direction(rest),
                    physicsVerdict(mapped[i], part == null ? 0 : part.length, lever)));
            if (part == null || part.length == 0) {
                continue;
            }
            float[] b = mesh.bounds(part);
            sb.append(String.format(Locale.ROOT,
                    "    - own geometry bounds x %.3f..%.3f, y %.3f..%.3f, z %.3f..%.3f; centre "
                            + "(%+.3f,%+.3f,%+.3f); pivot-centre horizontal offset %+.3f (x) %+.3f (z)%n",
                    b[0], b[3], b[1], b[4], b[2], b[5],
                    (b[0] + b[3]) * 0.5f - pivot.x, (b[1] + b[4]) * 0.5f - pivot.y, (b[2] + b[5]) * 0.5f - pivot.z,
                    (b[0] + b[3]) * 0.5f - pivot.x, (b[2] + b[5]) * 0.5f - pivot.z));
        }

        // Bones that own geometry but are NOT in the selection - the ones the log listed as declared
        // without geometry, or filtered out by the mapped test.
        sb.append("\n**bones that own drawn geometry in the skirt/hip region and are NOT simulated:**\n\n");
        sb.append("| bone | joint | mapped | own geom slots | ownTop | ownBot | nearest simulated ancestor |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        Vector3f hip = null;
        int shown = 0;
        for (Map.Entry<String, int[]> e : mesh.parts.entrySet()) {
            if (!e.getKey().startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            String boneName = e.getKey().substring(EFMeshJsonWriter.BONE_PART_PREFIX.length());
            if (physicsBones.contains(boneName)) {
                continue;
            }
            float[] b = mesh.bounds(e.getValue());
            if (b == null || b[1] > 1.05f || b[4] < 0.35f) {
                continue;
            }
            Integer bi = index.get(boneName);
            if (bi == null) {
                continue;
            }
            // only the hip band, i.e. cloth
            if (Math.abs(b[0]) > 0.45f && Math.abs(b[3]) > 0.45f) {
                continue;
            }
            String ancestor = "-";
            for (int p = parent[bi]; p >= 0; p = parent[p]) {
                if (physicsBones.contains(name[p])) {
                    ancestor = name[p];
                    break;
                }
            }
            if (shown++ >= 40) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %.3f | %.3f | %s |%n",
                    boneName, JointTable.nameOf(joint[bi]), mapped[bi], e.getValue().length, b[4], b[1], ancestor));
        }
        if (shown == 0) {
            sb.append("| (none) | | | | | | |\n");
        }
        return sb.toString();
    }

    private static String physicsVerdict(boolean mapped, int slots, float lever) {
        if (mapped) {
            return "**not simulated** (its pose belongs to Epic Fight)";
        }
        if (slots == 0) {
            return "**not simulated** (no geometry of its own)";
        }
        if (lever < 0.02f) {
            return "**not simulated** (lever below the minimum)";
        }
        return "simulated";
    }

    /** The bone names inside one `physics` entry, whatever shape the writer emitted. */
    private static void collectBoneNames(JsonElement element, String key, Set<String> out) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            String text = element.getAsString();
            if (!"animation".equals(key) && !text.isEmpty()) {
                out.add(text);
            }
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("name")) {
                collectBoneNames(object.get("name"), "name", out);
                return;
            }
            for (String candidate : new String[]{"bone", "bones", "part", "parts"}) {
                if (object.has(candidate)) {
                    collectBoneNames(object.get(candidate), candidate, out);
                }
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectBoneNames(item, key, out);
            }
            return;
        }
        // A name-keyed map: the keys are the bone names.
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            collectBoneNames(entry.getValue(), entry.getKey(), out);
        }
    }

    private static String direction(Vector3f v) {        if (v == null || v.lengthSquared() < 1e-9f) {
            return "-";
        }
        Vector3f d = new Vector3f(v).normalize();
        String label;
        if (d.y < -0.7f) {
            label = "down";
        } else if (d.y > 0.7f) {
            label = "up";
        } else if (d.y < -0.2f) {
            label = "down-and-out";
        } else {
            label = "sideways";
        }
        return String.format(Locale.ROOT, "(%+.2f,%+.2f,%+.2f) %s", d.x, d.y, d.z, label);
    }

    private static float get(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsFloat() : 0.0f;
    }

    private static Matrix4f bindWorld(int i, int[] parent, float[] px, float[] py, float[] pz,
                                      float[] rx, float[] ry, float[] rz, Matrix4f[] cache, int depth) {
        if (cache[i] != null) {
            return cache[i];
        }
        if (depth > 600) {
            return new Matrix4f();
        }
        Matrix4f local = new Matrix4f();
        local.translate(px[i], py[i], pz[i]);
        local.rotateZ(rz[i]);
        local.rotateY(ry[i]);
        local.rotateX(rx[i]);
        local.translate(-px[i], -py[i], -pz[i]);
        Matrix4f world = parent[i] >= 0
                ? new Matrix4f(bindWorld(parent[i], parent, px, py, pz, rx, ry, rz, cache, depth + 1)).mul(local)
                : local;
        cache[i] = world;
        return world;
    }

    private static Vector3f centroidOf(MeshData mesh, int[] indices) {
        if (indices == null || indices.length == 0) {
            return null;
        }
        Vector3f acc = new Vector3f();
        int n = 0;
        for (int idx : indices) {
            if (idx < 0 || idx * 3 + 2 >= mesh.positionsMc.length) {
                continue;
            }
            acc.add(mesh.positionsMc[idx * 3], mesh.positionsMc[idx * 3 + 1], mesh.positionsMc[idx * 3 + 2]);
            n++;
        }
        return n == 0 ? null : acc.div(n);
    }

    private static String fmtShort(Vector3f v) {
        return v == null ? "-" : String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }

    // ------------------------------------------------------------------
    // the previous build's cached conversion, pivoted by the same code
    // ------------------------------------------------------------------

    /**
     * The mesh/runtime pair the game loaded before the generator bump (the manifest cache entry of
     * the previous build), evaluated by the same production pivot code. Answers "regression or
     * newly-visible" directly: if the neck came out at the same place under the old bake, the symptom
     * is a pre-existing defect that only became visible once the stale cache was replaced.
     */
    private static String cachedComparison(String modelId, YsmModelPackage pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n### the game's own converted pack: previous build's cache vs now\n\n");
        Path configRoot = configuredConfigRoot().isEmpty() ? null : Paths.get(configuredConfigRoot());
        if (configRoot == null) {
            return sb.append("(no config root configured)\n").toString();
        }
        Path pack = configRoot.toAbsolutePath().getParent()
                .resolve("ysm_epicfight_compat").resolve("resourcepack").resolve("assets")
                .resolve("ysm_epicfight_compat");
        Path manifestFile = configRoot.toAbsolutePath().getParent()
                .resolve("ysm_epicfight_compat").resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            return sb.append("(no manifest at ").append(manifestFile).append(")\n").toString();
        }
        String manifestState;
        String meshName = null;
        try {
            JsonObject manifest = JsonParser.parseString(
                    Files.readString(manifestFile, StandardCharsets.UTF_8)).getAsJsonObject();
            manifestState = "generator=" + (manifest.has("generator") ? manifest.get("generator").getAsString() : "-");
            JsonObject models = manifest.has("models") ? manifest.getAsJsonObject("models") : null;
            if (models != null && models.has(modelId)) {
                meshName = models.getAsJsonObject(modelId).get("mesh").getAsString();
            }
        } catch (Exception e) {
            return sb.append("(manifest unreadable: ").append(e).append(")\n").toString();
        }
        sb.append("- manifest: ").append(manifestState)
                .append(", model ").append(modelId).append(" -> ")
                .append(meshName == null ? "**not converted by the running build**" : meshName).append('\n');
        if (meshName == null) {
            return sb.toString();
        }

        // Temporary fresh conversion of the current rules, for the side-by-side.
        try {
            Path tmp = Files.createTempDirectory("ysm-head-skirt-compare");
            Path freshMesh = tmp.resolve("mesh.json");
            Path freshRuntime = tmp.resolve("runtime.json");
            EFMeshJsonWriter.write(pkg, freshMesh, freshRuntime, "ysm_epicfight_compat:textures/audit.png");
            MeshData fresh = readMesh(freshMesh);
            JsonObject freshRuntimeJson = JsonParser.parseString(
                    Files.readString(freshRuntime, StandardCharsets.UTF_8)).getAsJsonObject();

            Path cachedMesh = pack.resolve("animmodels").resolve("entity").resolve(meshName + ".json");
            Path cachedRuntime = pack.resolve("ysm_runtime").resolve("entity").resolve(meshName + ".json");
            if (!Files.isRegularFile(cachedMesh) || !Files.isRegularFile(cachedRuntime)) {
                return sb.append("- cached pair missing under ").append(pack).append('\n').toString();
            }
            MeshData cached = readMesh(cachedMesh);
            JsonObject cachedRuntimeJson = JsonParser.parseString(
                    Files.readString(cachedRuntime, StandardCharsets.UTF_8)).getAsJsonObject();

            sb.append(String.format(Locale.ROOT,
                    "- the cached pack's mesh is %.6f MB, mtime %s; the mesh this build writes is %.6f MB%n",
                    Files.size(cachedMesh) / 1e6, Files.getLastModifiedTime(cachedMesh),
                    Files.size(freshMesh) / 1e6));
            Map<String, Integer> cachedJoints = runtimeJointMap(cachedRuntimeJson);
            if (pkg.geometry != null) {
                int mappedDiffer = 0;
                for (Map.Entry<String, Integer> e : cachedJoints.entrySet()) {
                    YSMGeoModel.Bone b = pkg.geometry.bonesByName.get(e.getKey());
                    if (b == null) {
                        continue;
                    }
                    if (YSMJointMapper.resolveJointId(b, pkg.geometry) != e.getValue()) {
                        mappedDiffer++;
                    }
                }
                sb.append(String.format(Locale.ROOT,
                        "- cached runtime table: %d bones; the CURRENT joint rules disagree with the cached "
                                + "`joint` field on %d of them%n", cachedJoints.size(), mappedDiffer));
            }

            YsmBindArmature.BindPivots cachedPivots = pivotsOf(cachedRuntimeJson, cached);
            YsmBindArmature.BindPivots freshPivots = pivotsOf(freshRuntimeJson, fresh);
            sb.append("\n| joint | cached (what the game drew before) | fresh (now) | delta |\n|---|---|---|---|\n");
            for (int j = 0; j < JointTable.COUNT; j++) {
                Vector3f a = cachedPivots.byJoint().get(j);
                Vector3f b = freshPivots.byJoint().get(j);
                sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s |%n", JointTable.nameOf(j), fmt(a), fmt(b),
                        a == null || b == null ? "-" : String.format(Locale.ROOT, "%.3f", a.distance(b))));
            }
        } catch (IOException e) {
            sb.append("- comparison failed: ").append(e).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, Integer> runtimeJointMap(JsonObject runtime) {
        Map<String, Integer> out = new HashMap<>();
        for (JsonElement e : runtime.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            out.put(b.get("name").getAsString(), b.get("joint").getAsInt());
        }
        return out;
    }

    private static YsmBindArmature.BindPivots pivotsOf(JsonObject runtime, MeshData mesh) {
        String[] names = new String[runtime.getAsJsonArray("bones").size()];
        int[] joints = new int[names.length];
        boolean[] mapped = new boolean[names.length];
        Map<String, Integer> index = new HashMap<>();
        int n = 0;
        for (JsonElement e : runtime.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            names[n] = b.get("name").getAsString();
            joints[n] = b.get("joint").getAsInt();
            mapped[n] = b.get("mapped").getAsBoolean();
            index.put(names[n], n);
            n++;
        }
        List<YsmBindArmature.BoneGeometry> parts = new ArrayList<>();
        for (Map.Entry<String, int[]> e : mesh.parts.entrySet()) {
            if (!e.getKey().startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            Integer idx = index.get(e.getKey().substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (idx == null) {
                continue;
            }
            List<Vector3f> verts = new ArrayList<>();
            for (int p : e.getValue()) {
                verts.add(new Vector3f(mesh.positionsMc[3 * p], mesh.positionsMc[3 * p + 1],
                        mesh.positionsMc[3 * p + 2]));
            }
            parts.add(new YsmBindArmature.BoneGeometry(idx, verts));
        }
        Set<String> hidden = YSMRuntimeModel.computeDefaultHiddenBoneNames(runtime);
        YsmBindArmature.GeometryInput input =
                new YsmBindArmature.GeometryInput("cache", names, joints, mapped, hidden, parts);
        List<String> warnings = new ArrayList<>();
        return YsmBindArmature.computePivots(input,
                YsmBindArmature.collectGeometry(input, warnings::add), warnings::add);
    }

    // ------------------------------------------------------------------
    // neck: candidate rules
    // ------------------------------------------------------------------

    private static String neckCandidates(List<Bone> rows, YsmBindArmature.GeometryData geometry,
                                         YsmBindArmature.BindPivots bind, YsmModelPackage pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n### neck candidates (measured, not asserted)\n\n");
        List<Vector3f> chest = geometry.byJoint().getOrDefault(8, List.of());
        List<Vector3f> head = geometry.byJoint().getOrDefault(9, List.of());
        Vector3f current = bind.byJoint().get(9);
        sb.append(String.format(Locale.ROOT,
                "- Chest-bound vertex slots=%d, Head-bound=%d, current neck=%s%n",
                chest.size(), head.size(), fmt(current)));
        if (chest.isEmpty() || head.isEmpty()) {
            return sb.toString();
        }

        // Authored central controls, for reference only.
        sb.append("\n**authored central head/neck controls** (reference; a name-driven rule would use these)\n\n");
        sb.append("| bone | pivot | own geom minY..maxY |\n|---|---|---|\n");
        for (Bone r : rows) {
            String norm = YSMJointMapper.normalize(r.bone.name);
            if (norm.equals("allhead") || norm.equals("mhead")
                    || (norm.equals("head") && r.bone.parent != null
                        && YSMJointMapper.normalize(r.bone.parent.name).equals("allhead"))) {
                sb.append(String.format(Locale.ROOT, "| %s | %s | %s |%n", r.bone.name, fmt(r.pivot),
                        r.bounds == null ? "-" : String.format(Locale.ROOT, "%.3f..%.3f", r.bounds[1], r.bounds[4])));
            }
        }

        Vector3f chestTop = topOf(chest);
        Vector3f chestCentre = centroid(chest);
        float modelDiag = extent(chest);
        sb.append(String.format(Locale.ROOT,
                "%n- chest geometry: top ring=%s, centroid=%s, chest span (max-min of x/y/z)=%.3f%n",
                fmt(chestTop), fmt(chestCentre), modelDiag));

        sb.append("\n| candidate rule | neck point | distance to current |\n|---|---|---|\n");
        appendCandidate(sb, "CURRENT: top ring of Chest geometry", current, current);
        appendCandidate(sb, "lowest ring of Head geometry", lowestRing(head), current);
        appendCandidate(sb, "highest ring of Head geometry", topOf(head), current);
        appendCandidate(sb, "mean of the two", midpoint(lowestRing(head), topOf(head)), current);

        // Radial candidate: the lowest ring of Head geometry that has Chest geometry below it.
        for (float r : new float[]{0.05f, 0.10f, 0.15f, 0.20f, 0.30f}) {
            appendCandidate(sb, String.format(Locale.ROOT,
                            "lowest Head ring that has Chest geometry below it within r=%.2f", r),
                    lowestHeadRingWithChestBelow(head, chest, r), current);
        }

        // The Head-bound geometry's own cross-section: for the ring scan, print radius vs height.
        sb.append("\n**Head-bound geometry: horizontal radius from the vertical axis through the chest's ")
                .append("top ring, per 0.05-block Y slice** (the neck should be where the head's own ")
                .append("cross-section is narrow)\n\n");
        sb.append("| y slice | slots | max radius from chestTop axis | min radius |\n|---|---|---|---|\n");
        int slices = 0;
        for (float y = topOf(head) != null ? topOf(head).y : 0; y > 0 && slices < 40; y -= 0.05f) {
            float lo = y - 0.05f;
            float maxR = -1;
            float minR = Float.MAX_VALUE;
            int n = 0;
            for (Vector3f v : head) {
                if (v.y < lo || v.y > y) {
                    continue;
                }
                n++;
                float dx = v.x - chestTop.x;
                float dz = v.z - chestTop.z;
                float r = (float) Math.sqrt(dx * dx + dz * dz);
                maxR = Math.max(maxR, r);
                minR = Math.min(minR, r);
            }
            if (n == 0) {
                continue;
            }
            slices++;
            sb.append(String.format(Locale.ROOT, "| %.2f..%.2f | %d | %.3f | %.3f |%n",
                    lo, y, n, maxR, minR == Float.MAX_VALUE ? -1 : minR));
        }

        // How much the head-bound geometry would move under a rotation about the current neck.
        float swing = maxDistance(head, current);
        sb.append(String.format(Locale.ROOT,
                "%n**swing radius of the Head-bound geometry about the current neck**: the farthest "
                        + "Head-bound vertex is %.3f blocks from %s, so a 30-deg head rotation moves it "
                        + "%.3f blocks%n",
                swing, fmt(current), 2 * Math.sin(Math.toRadians(15)) * swing));

        sb.append("\n**where the neck should be, measured on the skull**: the skull is the mass of "
                + "Head-bound geometry near the body's vertical axis, so each vertex is weighted by "
                + "exp(-((r-rMin)/0.10)^2), where r is its horizontal distance from the axis through the "
                + "chest geometry's top ring and rMin the smallest such radius anywhere in the head. "
                + "`lowest 30%` is the bottom third of that weight, by Y.\n\n");
        sb.append("| candidate | point | weighted r.m.s. horizontal radius of the skull | vs lowest-30% centroid |\n");
        sb.append("|---|---|---|---|\n");
        Vector3f lowCentroid = ringCentroid(head, 0.0f, 0.30f);
        appendSkull(sb, "CURRENT: top ring of Chest geometry", current, head, chestTop, lowCentroid);
        appendSkull(sb, "authored AllHead/MHead control", authoredNeck(rows), head, chestTop, lowCentroid);
        appendSkull(sb, "lowest ring of Head geometry", lowestRing(head), head, chestTop, lowCentroid);
        appendSkull(sb, "centroid of the lowest 30% of the skull", lowCentroid, head, chestTop, lowCentroid);
        Vector3f onAxis = onAxisFromChest(current, chest);
        appendSkull(sb, "current Y on the chest geometry's own vertical axis", onAxis, head, chestTop, lowCentroid);

        sb.append("\n**what pulls the chest's top off the body's axis**: the Chest-bound geometry's top "
                + "slices, exactly as `topOf` sees them (a vertex is counted once per corner it appears "
                + "in, so a heavily tessellated piece weighs more)\n\n");
        sb.append("| slice centre y | slots | mean x | mean z | min x | max x | min z | max z |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        float maxChestY = -Float.MAX_VALUE;
        for (Vector3f v : chest) {
            maxChestY = Math.max(maxChestY, v.y);
        }
        float topY = maxChestY;
        sb.append(String.format(Locale.ROOT,
                "| (the chest geometry's maximum y; the production `topOf` averages the ring within 0.05 "
                        + "of this) | | | | | | | |%n"));
        for (float drop : new float[]{0.0f, 0.02f, 0.05f, 0.10f, 0.20f, 0.30f, 0.50f}) {
            float lo = topY - drop - 0.001f;
            Vector3f mean = new Vector3f();
            int count = 0;
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            for (Vector3f v : chest) {
                if (v.y < lo || v.y > topY + 0.001f) {
                    continue;
                }
                mean.add(v);
                count++;
                minX = Math.min(minX, v.x);
                maxX = Math.max(maxX, v.x);
                minZ = Math.min(minZ, v.z);
                maxZ = Math.max(maxZ, v.z);
            }
            if (count == 0) {
                continue;
            }
            mean.div(count);
            sb.append(String.format(Locale.ROOT, "| %.3f | %d | %+.3f | %+.3f | %.3f | %.3f | %.3f | %.3f |%n",
                    topY - drop, count, mean.x, mean.z, minX, maxX, minZ, maxZ));
        }

        sb.append("\n**the body's vertical axis, measured several ways** (the neck's horizontal position "
                + "should come from one of these; x is the left/right axis, z the front/back one, and a "
                + "left-right symmetric model has median x = 0)\n\n");
        sb.append("| source | x | z |\n|---|---|---|\n");
        appendAxis(sb, "median of the whole Chest-bound geometry", chest);
        appendAxis(sb, "median of the Chest-bound geometry below " + fmtShort(chestTop) + " (all of it)",
                below(chest, chestTop));
        appendAxis(sb, "median of the Head-bound geometry's lowest 0.10", baseRing(head));
        appendAxis(sb, "median of the whole Head-bound geometry", head);
        appendAxis(sb, "lowest Head ring with Chest geometry within 0.20 of it", supported(head, chest, 0.20f));
        appendAxis(sb, "lowest Head ring with Chest geometry within 0.30 of it", supported(head, chest, 0.30f));
        return sb.toString();
    }

    private static void appendAxis(StringBuilder sb, String label, List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "| %s | - | - |%n", label));
            return;
        }
        List<Float> xs = new ArrayList<>();
        List<Float> zs = new ArrayList<>();
        for (Vector3f v : vertices) {
            xs.add(v.x);
            zs.add(v.z);
        }
        sb.append(String.format(Locale.ROOT, "| %s | %+.3f | %+.3f |%n", label, median(xs), median(zs)));
    }

    private static List<Vector3f> below(List<Vector3f> vertices, Vector3f top) {
        if (top == null) {
            return vertices == null ? List.of() : vertices;
        }
        List<Vector3f> out = new ArrayList<>();
        for (Vector3f v : vertices) {
            if (v.y <= top.y + 1e-4f) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<Vector3f> baseRing(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return List.of();
        }
        float minY = Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            minY = Math.min(minY, v.y);
        }
        List<Vector3f> out = new ArrayList<>();
        for (Vector3f v : vertices) {
            if (v.y <= minY + 0.10f) {
                out.add(v);
            }
        }
        return out;
    }

    /** The lowest 0.05-thick ring of `vertices` that has `support` geometry within `radius` of it. */
    private static List<Vector3f> supported(List<Vector3f> vertices, List<Vector3f> support, float radius) {
        if (vertices == null || vertices.isEmpty() || support == null || support.isEmpty()) {
            return List.of();
        }
        float bestY = Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            if (v.y >= bestY) {
                continue;
            }
            for (Vector3f s : support) {
                float dx = s.x - v.x;
                float dz = s.z - v.z;
                if (dx * dx + dz * dz <= radius * radius) {
                    bestY = v.y;
                    break;
                }
            }
        }
        if (bestY == Float.MAX_VALUE) {
            return List.of();
        }
        List<Vector3f> out = new ArrayList<>();
        for (Vector3f v : vertices) {
            if (v.y <= bestY + 0.05f) {
                out.add(v);
            }
        }
        return out;
    }

    private static float median(List<Float> values) {
        List<Float> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int size = sorted.size();
        return size % 2 == 1
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) * 0.5f;
    }

    /** The worst horizontal displacement of `vertices` under a 30-degree rotation about `pivot`. */
    private static void appendSwing(StringBuilder sb, String label, Vector3f pivot, List<Vector3f> vertices,
                                    List<Vector3f> chest) {
        if (pivot == null || vertices == null || vertices.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "| %s | - | - | - | - |%n", label));
            return;
        }
        Vector3f centre = centroid(chest);
        double pitch = 0;
        double yaw = 0;
        double roll = 0;
        for (Vector3f v : vertices) {
            float dy = v.y - pivot.y;
            float dz = v.z - pivot.z;
            float dx = v.x - pivot.x;
            pitch = Math.max(pitch, Math.hypot(dy, dz) * 2 * Math.sin(Math.toRadians(15)));
            yaw = Math.max(yaw, Math.hypot(dx, dz) * 2 * Math.sin(Math.toRadians(15)));
            roll = Math.max(roll, Math.hypot(dx, dy) * 2 * Math.sin(Math.toRadians(15)));
        }
        sb.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.3f | %.3f |%n", label,
                pitch, yaw, roll,
                centre == null ? -1 : Math.hypot(pivot.x - centre.x, pivot.z - centre.z)));
    }

    /** The lowest `fraction` of a vertex list by Y. */
    private static List<Vector3f> lowestFraction(List<Vector3f> vertices, float fraction) {
        if (vertices == null || vertices.isEmpty()) {
            return List.of();
        }
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            minY = Math.min(minY, v.y);
            maxY = Math.max(maxY, v.y);
        }
        float hi = minY + (maxY - minY) * fraction;
        List<Vector3f> out = new ArrayList<>();
        for (Vector3f v : vertices) {
            if (v.y <= hi) {
                out.add(v);
            }
        }
        return out;
    }

    private static void appendSkull(StringBuilder sb, String label, Vector3f candidate, List<Vector3f> head,
                                    Vector3f axis, Vector3f lowCentroid) {
        if (candidate == null) {
            sb.append(String.format(Locale.ROOT, "| %s | - | - | - |%n", label));
            return;
        }
        float rMin = Float.MAX_VALUE;
        for (Vector3f v : head) {
            rMin = Math.min(rMin, horizontal(v, axis));
        }
        double weighted = 0;
        double weight = 0;
        for (Vector3f v : head) {
            float r = horizontal(v, axis);
            double w = Math.exp(-Math.pow((r - rMin) / 0.10, 2));
            weighted += w * r * r;
            weight += w;
        }
        sb.append(String.format(Locale.ROOT, "| %s | %s | %.4f | %s |%n", label, fmt(candidate),
                weight == 0 ? -1 : Math.sqrt(weighted / weight),
                lowCentroid == null ? "-" : String.format(Locale.ROOT, "%.3f", candidate.distance(lowCentroid))));
    }

    private static float horizontal(Vector3f v, Vector3f axis) {
        float dx = v.x - axis.x;
        float dz = v.z - axis.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /** The centroid of the lowest `fraction` of the Head-bound geometry by Y. */
    private static Vector3f ringCentroid(List<Vector3f> vertices, float fromFraction, float toFraction) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            minY = Math.min(minY, v.y);
            maxY = Math.max(maxY, v.y);
        }
        float lo = minY + (maxY - minY) * fromFraction;
        float hi = minY + (maxY - minY) * toFraction;
        Vector3f acc = new Vector3f();
        int n = 0;
        for (Vector3f v : vertices) {
            if (v.y >= lo && v.y <= hi) {
                acc.add(v);
                n++;
            }
        }
        return n == 0 ? null : acc.div(n);
    }

    /** The current neck's Y on the chest geometry's own vertical axis (its centroid's x/z). */
    private static Vector3f onAxisFromChest(Vector3f neck, List<Vector3f> chest) {
        Vector3f centre = centroid(chest);
        return neck == null || centre == null ? null : new Vector3f(centre.x, neck.y, centre.z);
    }

    /** The model's authored neck control, when it has one: AllHead, else MHead, else Head. */
    private static Vector3f authoredNeck(List<Bone> rows) {
        Vector3f allHead = null;
        Vector3f mHead = null;
        Vector3f head = null;
        for (Bone r : rows) {
            String norm = YSMJointMapper.normalize(r.bone.name);
            if (norm.equals("allhead") && allHead == null) {
                allHead = r.pivot;
            } else if (norm.equals("mhead") && mHead == null) {
                mHead = r.pivot;
            } else if (norm.equals("head") && head == null && r.bone.parent != null
                    && YSMJointMapper.normalize(r.bone.parent.name).equals("allhead")) {
                head = r.pivot;
            }
        }
        return mHead != null ? mHead : (head != null ? head : allHead);
    }

    private static void appendCandidate(StringBuilder sb, String label, Vector3f value, Vector3f current) {
        sb.append(String.format(Locale.ROOT, "| %s | %s | %s |%n", label, fmt(value),
                value == null || current == null ? "-" : String.format(Locale.ROOT, "%.3f", value.distance(current))));
    }

    private static Vector3f lowestRing(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        float minY = Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            minY = Math.min(minY, v.y);
        }
        Vector3f acc = new Vector3f();
        int n = 0;
        for (Vector3f v : vertices) {
            if (v.y <= minY + 0.05f) {
                acc.add(v);
                n++;
            }
        }
        return n == 0 ? new Vector3f(vertices.get(0)) : acc.div(n);
    }

    private static Vector3f lowestHeadRingWithChestBelow(List<Vector3f> head, List<Vector3f> chest, float radius) {
        float bestY = Float.MAX_VALUE;
        Vector3f best = new Vector3f();
        int count = 0;
        for (Vector3f v : head) {
            boolean supported = false;
            for (Vector3f c : chest) {
                float dx = c.x - v.x;
                float dz = c.z - v.z;
                if (dx * dx + dz * dz <= radius * radius && c.y <= v.y) {
                    supported = true;
                    break;
                }
            }
            if (!supported) {
                continue;
            }
            if (v.y < bestY - 1e-4f) {
                bestY = v.y;
                best.set(0);
                count = 0;
            }
            if (Math.abs(v.y - bestY) <= 1e-4f) {
                best.add(v);
                count++;
            }
        }
        return count == 0 ? null : best.div(count);
    }

    private static float maxDistance(List<Vector3f> vertices, Vector3f p) {
        if (vertices == null || p == null) {
            return 0;
        }
        float best = 0;
        for (Vector3f v : vertices) {
            best = Math.max(best, v.distance(p));
        }
        return best;
    }

    private static Vector3f centroid(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        Vector3f acc = new Vector3f();
        for (Vector3f v : vertices) {
            acc.add(v);
        }
        return acc.div(vertices.size());
    }

    private static float extent(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return 0;
        }
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Vector3f v : vertices) {
            b[0] = Math.min(b[0], v.x);
            b[1] = Math.min(b[1], v.y);
            b[2] = Math.min(b[2], v.z);
            b[3] = Math.max(b[3], v.x);
            b[4] = Math.max(b[4], v.y);
            b[5] = Math.max(b[5], v.z);
        }
        return Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
    }

    private static Vector3f midpoint(Vector3f a, Vector3f b) {
        return a == null || b == null ? null : new Vector3f(a).add(b).mul(0.5f);
    }

    // ------------------------------------------------------------------
    // neck
    // ------------------------------------------------------------------

    private static String neckAnalysis(List<Bone> rows, List<Vector3f> chestVerts,
                                       YsmBindArmature.BindPivots bind,
                                       YsmBindArmature.GeometryData geometry) {
        StringBuilder sb = new StringBuilder();
        Vector3f neck = bind.byJoint().get(9);
        Vector3f chestPivot = bind.byJoint().get(8);
        Vector3f hip = bind.byJoint().get(0);
        sb.append("- `Head` pivot (the neck) = ").append(fmt(neck)).append('\n');
        sb.append("- `Chest` pivot = ").append(fmt(chestPivot)).append('\n');
        sb.append("- `Root`/`Torso` pivot (the hip) = ").append(fmt(hip)).append('\n');
        if (neck == null || chestVerts.isEmpty()) {
            return sb.toString();
        }
        float maxY = -Float.MAX_VALUE;
        for (Vector3f v : chestVerts) {
            maxY = Math.max(maxY, v.y);
        }
        sb.append(String.format(Locale.ROOT,
                "- the Chest joint's settled geometry: %d vertex slots, top ring y=%.3f%n", chestVerts.size(), maxY));
        sb.append("\n**Bones whose own geometry reaches into the top ring of the Chest joint's geometry "
                + "(within 0.05 of y=").append(String.format(Locale.ROOT, "%.3f", maxY)).append("):**\n\n");
        sb.append("| bone | chain | joint | tier | mapped | hidden | verts | minY | maxY | centerX | centerZ | verts in top ring |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Bone r : rows) {
            if (r.bounds == null || r.joint != 8) {
                continue;
            }
            long inRing = r.verticesWithinTopRing();
            if (r.bounds[4] < maxY - 0.05f) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %s | %s | %d | %.3f | %.3f | %.3f | %.3f | %d |%n",
                    r.bone.name, r.chain, JointTable.nameOf(r.joint), r.tier, r.mapped, r.hidden,
                    r.verts, r.bounds[1], r.bounds[4], (r.bounds[0] + r.bounds[3]) * 0.5f,
                    (r.bounds[2] + r.bounds[5]) * 0.5f, inRing));
        }
        return sb.toString();
    }

    /** The neck seam: the lowest geometry bound to Head, against the highest bound to Chest. */
    private static String neckSeam(List<Bone> rows, YsmBindArmature.BindPivots bind) {
        StringBuilder sb = new StringBuilder();
        float headMinY = Float.MAX_VALUE;
        float chestMaxY = -Float.MAX_VALUE;
        for (Bone r : rows) {
            if (r.bounds == null) {
                continue;
            }
            if (r.joint == 9) {
                headMinY = Math.min(headMinY, r.bounds[1]);
            }
            if (r.joint == 8) {
                chestMaxY = Math.max(chestMaxY, r.bounds[4]);
            }
        }
        Vector3f neck = bind.byJoint().get(9);
        sb.append(String.format(Locale.ROOT,
                "- lowest geometry bound to `Head`: y=%.3f%n- highest geometry bound to `Chest`: y=%.3f%n",
                headMinY, chestMaxY));
        if (neck != null) {
            sb.append(String.format(Locale.ROOT,
                    "- `Head` pivot y=%.3f; gap to the lowest Head geometry = %.3f; "
                            + "it sits %.3f above the highest Chest geometry%n",
                    neck.y, neck.y - headMinY, neck.y - chestMaxY));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // skirt / physics
    // ------------------------------------------------------------------

    private static String skirtSection(List<Bone> rows, JsonObject runtime,
                                       YsmBindArmature.GeometryData geometry,
                                       YsmBindArmature.BindPivots bind, String modelId) {
        StringBuilder sb = new StringBuilder();
        sb.append("### physics\n\n");
        if (runtime.has("physics")) {
            JsonObject physics = runtime.getAsJsonObject("physics");
            sb.append("- runtime `physics` section: ");
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : physics.entrySet()) {
                if ("animation".equals(e.getKey())) {
                    sb.append("animation=").append(e.getValue().getAsString()).append(", ");
                } else {
                    keys.add(e.getKey());
                }
            }
            sb.append(keys.size()).append(" part(s): ").append(keys).append('\n');
        } else {
            sb.append("- runtime has **no** `physics` section (the converter declares nothing; the runtime "
                    + "falls back to bone-name classification)\n");
        }

        Vector3f hip = bind.byJoint().get(0);
        sb.append(String.format(Locale.ROOT, "- hip (`Root` pivot) = %s%n", fmt(hip)));

        // The structural skirt: geometry crossing the hip plane within the leg band.
        sb.append("\n### geometry around the hip, band = 0.35 blocks below to 0.15 above the hip\n\n");
        sb.append("`ownTop/ownBot` = the bone's own geometry; `subTop/subBot` = its whole subtree. ")
                .append("`src` = the mapped ancestor that supplied the joint.\n\n");
        sb.append("| bone | chain | src | joint | tier | mapped | hidden | ownVerts | ownTop | ownBot | subTop | subBot | ownCenterX | ownCenterZ |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<Bone> band = new ArrayList<>();
        for (Bone r : rows) {
            if (r.bounds == null || hip == null) {
                continue;
            }
            if (r.bounds[1] <= hip.y + 0.15f && r.bounds[4] >= hip.y - 0.35f
                    && Math.abs(r.bounds[0]) < 0.5f && Math.abs(r.bounds[3]) < 0.5f) {
                band.add(r);
            }
        }
        band.sort(Comparator.comparingDouble((Bone r) -> r.subtreeBounds == null ? 0 : -r.subtreeBounds[4]));
        for (Bone r : band) {
            sb.append(String.format(Locale.ROOT,
                    "| %s | %s | %s | %s | %d | %s | %s | %d | %.3f | %.3f | %s | %s | %.3f | %.3f |%n",
                    r.bone.name, r.chain, r.sourceName(), JointTable.nameOf(r.joint), r.tier, r.mapped, r.hidden,
                    r.verts, r.bounds[1], r.bounds[4],
                    r.subtreeBounds == null ? "-" : String.format(Locale.ROOT, "%.3f", r.subtreeBounds[4]),
                    r.subtreeBounds == null ? "-" : String.format(Locale.ROOT, "%.3f", r.subtreeBounds[1]),
                    (r.bounds[0] + r.bounds[3]) * 0.5f, (r.bounds[2] + r.bounds[5]) * 0.5f));
        }

        // The named skirt bones from the user's own log (for the kemomimi twin).
        sb.append("\n### the bones the game logged as simulated for 兽耳酱x1 (name list), resolved here\n\n");
        sb.append("| bone | present | chain | src | joint | tier | mapped | hidden | ownVerts | ownTop | ownBot | subTop | subBot |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        Map<String, Bone> byName = new LinkedHashMap<>();
        for (Bone r : rows) {
            byName.put(r.bone.name, r);
        }
        for (String name : KEMOMIMI_PHYSICS_BONES) {
            Bone r = byName.get(name);
            if (r == null) {
                sb.append(String.format(Locale.ROOT, "| %s | no | - | - | - | - | - | - | - | - | - | - | - |%n", name));
                continue;
            }
            sb.append(String.format(Locale.ROOT,
                    "| %s | yes | %s | %s | %s | %d | %s | %s | %d | %s | %s | %s | %s |%n",
                    name, r.chain, r.sourceName(), JointTable.nameOf(r.joint), r.tier, r.mapped, r.hidden, r.verts,
                    r.bounds == null ? "-" : String.format(Locale.ROOT, "%.3f", r.bounds[1]),
                    r.bounds == null ? "-" : String.format(Locale.ROOT, "%.3f", r.bounds[4]),
                    r.subtreeBounds == null ? "-" : String.format(Locale.ROOT, "%.3f", r.subtreeBounds[4]),
                    r.subtreeBounds == null ? "-" : String.format(Locale.ROOT, "%.3f", r.subtreeBounds[1])));
        }

        // Which bones own the geometry in the skirt band, and which bone is the nearest simulated ancestor.
        sb.append("\n### the skirt band, as geometry ownership\n\n");
        sb.append("For every bone with own geometry in the band: the bone itself, and its nearest ancestor that ")
                .append("appears in the game's simulated-bone list.\n\n");
        sb.append("| bone | ownVerts | ownTop | ownBot | joint | nearest simulated ancestor | that ancestor owns geometry? |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        Set<String> simulated = new java.util.HashSet<>(KEMOMIMI_PHYSICS_BONES);
        for (Bone r : band) {
            String ancestor = "-";
            boolean ancestorOwns = false;
            for (YSMGeoModel.Bone b = r.bone.parent; b != null; b = b.parent) {
                if (simulated.contains(b.name)) {
                    ancestor = b.name;
                    Bone ab = byName.get(b.name);
                    ancestorOwns = ab != null && ab.bounds != null;
                    break;
                }
            }
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.3f | %.3f | %s | %s | %s |%n",
                    r.bone.name, r.verts, r.bounds[1], r.bounds[4], JointTable.nameOf(r.joint), ancestor,
                    ancestorOwns));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // per-bone data
    // ------------------------------------------------------------------

    private static final class Bone {
        final YSMGeoModel.Bone bone;
        final float[] bounds;
        final float[] subtreeBounds;
        final int verts;
        final int joint;
        final boolean mapped;
        final Set<Integer> bakeJoints;
        final boolean hidden;
        final int tier;
        final Vector3f pivot;
        final String chain;

        Bone(YSMGeoModel.Bone bone, float[] bounds, int verts, int joint, boolean mapped,
             Set<Integer> bakeJoints, boolean hidden, int tier, Vector3f pivot, float[] subtreeBounds,
             String chain) {
            this.bone = bone;
            this.bounds = bounds;
            this.verts = verts;
            this.joint = joint;
            this.mapped = mapped;
            this.bakeJoints = bakeJoints;
            this.hidden = hidden;
            this.tier = tier;
            this.pivot = pivot;
            this.subtreeBounds = subtreeBounds;
            this.chain = chain;
        }

        String sourceName() {
            YSMGeoModel.Bone source = mappedAncestor(bone);
            return source == null ? "-" : source.name;
        }

        long verticesWithinTopRing() {
            return bounds == null ? 0 : verts;
        }

        String row() {
            if (bounds == null) {
                return String.format(Locale.ROOT,
                        "| %s | %s | %s | %s | %d | %s | %s | %d | - | - | - | - | - | - |",
                        bone.name, chain, sourceName(), JointTable.nameOf(joint), tier, mapped, hidden, verts);
            }
            return String.format(Locale.ROOT,
                    "| %s | %s | %s | %s | %d | %s | %s | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |",
                    bone.name, chain, sourceName(), JointTable.nameOf(joint), tier, mapped, hidden, verts,
                    bounds[1], bounds[4], (bounds[0] + bounds[3]) * 0.5f, (bounds[2] + bounds[5]) * 0.5f,
                    bounds[2], bounds[5]);
        }

        String headRow() {
            return String.format(Locale.ROOT, "| %s | %s | %s | %s | %d | %.3f | %.3f | %.3f | %.3f | %.3f |",
                    bone.name, chain, JointTable.nameOf(joint), bakeJointNames(), verts,
                    bounds[1], bounds[4], (bounds[0] + bounds[3]) * 0.5f, (bounds[1] + bounds[4]) * 0.5f,
                    (bounds[2] + bounds[5]) * 0.5f);
        }

        String bakeJointNames() {
            if (bakeJoints == null || bakeJoints.isEmpty()) {
                return "-";
            }
            StringBuilder sb = new StringBuilder();
            for (int j : bakeJoints) {
                if (sb.length() > 0) {
                    sb.append('+');
                }
                sb.append(JointTable.nameOf(j));
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static YSMGeoModel.Bone mappedAncestor(YSMGeoModel.Bone bone) {
        for (YSMGeoModel.Bone b = bone; b != null; b = b.parent) {
            if (YSMJointMapper.isDirectlyMapped(b)) {
                return b;
            }
        }
        return null;
    }

    private static Vector3f topOf(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        float maxY = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            maxY = Math.max(maxY, v.y);
        }
        Vector3f acc = new Vector3f();
        int count = 0;
        for (Vector3f v : vertices) {
            if (v.y >= maxY - 0.05f) {
                acc.add(v);
                count++;
            }
        }
        return count == 0 ? new Vector3f(vertices.get(0)) : acc.div(count);
    }

    private static float[] subtreeBounds(YSMGeoModel.Bone bone, float scaleW, float scaleH) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        List<YSMGeoModel.Bone> stack = new ArrayList<>();
        stack.add(bone);
        while (!stack.isEmpty()) {
            YSMGeoModel.Bone cur = stack.remove(stack.size() - 1);
            if (!cur.quads.isEmpty()) {
                float[] own = ownBounds(cur, scaleW, scaleH);
                if (own != null) {
                    any = true;
                    for (int c = 0; c < 3; c++) {
                        b[c] = Math.min(b[c], own[c]);
                        b[3 + c] = Math.max(b[3 + c], own[3 + c]);
                    }
                }
            }
            stack.addAll(cur.children);
        }
        return any ? b : null;
    }

    private static float[] ownBounds(YSMGeoModel.Bone bone, float scaleW, float scaleH) {
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
        return b[0] == Float.MAX_VALUE ? null : b;
    }

    private static Vector3f pivotPosition(YSMGeoModel.Bone bone, float scaleW, float scaleH) {
        Vector3f p = new Vector3f(bone.pivotX, bone.pivotY, bone.pivotZ)
                .mulPosition(bone.parent == null ? new Matrix4f() : worldFromParentChain(bone.parent));
        return new Vector3f(p.x * scaleW, p.y * scaleH, p.z * scaleW);
    }

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

    // ------------------------------------------------------------------
    // mesh JSON
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
            Set<Integer> out = new java.util.LinkedHashSet<>();
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

    private static String fmt(Vector3f v) {
        return v == null ? "-" : String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }

    private static String configuredConfigRoot() {
        String property = System.getProperty(YsmModelPackage.CONFIG_ROOT_PROPERTY, "");
        if (!property.isEmpty()) {
            return property;
        }
        String fromEnvironment = System.getenv(YsmModelPackage.CONFIG_ROOT_ENV);
        return fromEnvironment == null ? "" : fromEnvironment;
    }
}
