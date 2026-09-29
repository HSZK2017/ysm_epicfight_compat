package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.model.BoneAlternateForms;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.JointTable;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measurement, not an assertion: the per-bone binding table of {@code EKU(1.0.ysm}, the model the
 * in-game report says shows its "legs separating from the body".
 *
 * <p>The game log for this model carries the one lead it has: {@code [bind] model='EKU(1.0.ysm' had
 * no geometry for 1 joint(s) under the standard bind filters; relaxed so their pivots stay
 * geometry-derived ... Root gave up every filter, including the hidden-bone filter (beijingEKU,
 * FUCHIC, egg-4, egg-3, egg-2, egg-1)}. Only the hip joint relaxed, and it relaxed to the deepest
 * tier, so the question is what geometry those six hidden bones carry and what pivot the hip was
 * therefore measured from.
 *
 * <p>Everything is read off the production paths: {@link YsmModelPackage#load} for the package,
 * {@link EFMeshJsonWriter#write} for the mesh and runtime the current rules bake,
 * {@link YsmBindArmature#collectGeometry} / {@link YsmBindArmature#computePivots} for the pivots (the
 * production filter and pivot arithmetic, called directly), and {@link YsmBindArmature#tierOf} for
 * each bone's tier. The one thing reproduced rather than called is the bind chain, because
 * {@code YSMRuntimeModel.compile} is private and {@code YSMMesh} needs a live game: {@link #bindWorldOf}
 * and {@link #bindLocalOf} are {@code YSMRuntimeModel#computeBindWorld} / {@code #computeBindLocal}
 * written out, and {@link #meshSpacePivot} is {@code YsmPhysicsParts.pivotInMeshSpace} written out.
 * Both are cross-checked against the mesh's own vertex bounds, which is what makes the reproduction
 * falsifiable: if the reproduction disagreed with the writer, the geometry would not sit on its
 * bone's pivot column.
 *
 * <p>Writes {@code build/reports/ysm-eku-binding.txt}: the joint table (pivot, owned geometry bounds,
 * bones that placed it), the per-joint geometry sides, and the per-bone table (name, parent chain,
 * joint, mapped flag, tier, authored pivot, own geometry bounds and centroid, pivot in mesh space,
 * settled joint pivot, both distances).
 *
 * <p>Opt-in: skipped unless {@code -Dysmef.golden.ysm_config_root} / {@code YSMEF_YSM_CONFIG_ROOT}
 * names the YSM config directory.
 */
class EkuLegSeparationProbeTest {

    private static final String MODEL = "EKU(1.0.ysm";

    /**
     * How far this probe's pivots may sit from the game's own {@code [bind]} line for this model,
     * blocks. The log rounds every component to three decimals, so the finest agreement reachable
     * is about 0.0009 blocks; five millimetres leaves room for that and for summation order, and is
     * still two orders of magnitude below the ~1.5 blocks a frame error produces.
     */
    private static final float CALIBRATION_TOLERANCE = 0.005F;

    /**
     * Each leg, as the three joints that draw it. The thigh joint is the hip end, the leg joint is
     * the knee, and the knee joint is the ankle: the bind armature's ids, which
     * {@link com.ysmef.compat.model.JointTable} names.
     */
    private static final int[][] LEG_SIDES = {
            {JointTable.THIGH_R, JointTable.LEG_R, JointTable.KNEE_R},
            {JointTable.THIGH_L, JointTable.LEG_L, JointTable.KNEE_L}};

    /** Bones whose name reads as part of a leg, for the "what draws the leg" table. */
    private static final java.util.regex.Pattern LEG_NAME = java.util.regex.Pattern.compile(
            "leg|thigh|knee|shin|calf|foot|shoe|xie|tui", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The highest y in a set of vertices, or NaN when there are none. */
    private static float top(List<Vector3f> vertices) {
        float max = Float.NaN;
        for (Vector3f v : vertices) {
            max = Float.isNaN(max) ? v.y : Math.max(max, v.y);
        }
        return max;
    }

    @Test
    void dumpTheBindingTable() throws Exception {
        String configured = configuredConfigRoot();
        assumeTrue(!configured.isEmpty(),
                "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + "=<config/yes_steve_model> (or the "
                        + YsmModelPackage.CONFIG_ROOT_ENV + " environment variable) to run the probe");

        YsmModelPackage pkg = YsmModelPackage.load(MODEL);
        if (pkg == null || pkg.geometry == null) {
            throw new IllegalStateException("could not load the package for '" + MODEL + "' from " + configured);
        }

        Path tmp = Files.createTempDirectory("ysm-eku-binding");
        Path freshMesh = tmp.resolve("mesh.json");
        Path freshRuntime = tmp.resolve("runtime.json");
        EFMeshJsonWriter.write(pkg, freshMesh, freshRuntime, "ysm_epicfight_compat:textures/eku.png");

        // The files the game really loaded, when the install's converted pack is there: the freshly
        // written runtime carries no animations, so `computeDefaultHiddenBoneNames` answers "nothing
        // is hidden" for it - and the log's relaxation line says this model hides six bones. The
        // converted pack on disk is the same bake plus its animations, which is what the game reads.
        Path configDir = Paths.get(configured).toAbsolutePath().getParent();
        Path pack = configDir.resolve("ysm_epicfight_compat").resolve("resourcepack").resolve("assets")
                .resolve("ysm_epicfight_compat");
        Path installedRuntime = findConverted(pack.resolve("ysm_runtime").resolve("entity"), "eku");
        Path installedMesh = findConverted(pack.resolve("animmodels").resolve("entity"), "eku");
        Path runtimeFile = installedRuntime != null ? installedRuntime : freshRuntime;
        Path meshFile = installedMesh != null ? installedMesh : freshMesh;

        Probe probe = Probe.read(meshFile, runtimeFile, pkg.widthScale, pkg.heightScale, pkg.modelId);
        System.out.println("probe read the " + (installedRuntime != null ? "installed" : "freshly written")
                + " converted pair: runtime=" + runtimeFile + " mesh=" + meshFile);

        List<String> warnings = new ArrayList<>();
        YsmBindArmature.GeometryData geometry = YsmBindArmature.collectGeometry(probe.input, warnings::add);
        YsmBindArmature.BindPivots pivots = YsmBindArmature.computePivots(probe.input, geometry, warnings::add);

        // ---- calibration: the game's own pivots for this model --------------------------------
        // latest.log line 1682, printed by YsmBindArmature#build:
        //   [bind] model='EKU(1.0.ysm' pivots root=(0.000,0.935,-0.002),torso=(0.000,0.935,-0.002),
        //   chest=(0.000,1.134,0.012),head=(0.000,1.333,0.027),shoulderR=(0.121,1.321,0.025),
        //   shoulderL=(-0.121,1.321,0.027),elbowR=(0.219,1.095,0.028),elbowL=(-0.221,1.096,0.024),
        //   wristR=(0.244,0.881,0.026),wristL=(-0.244,0.881,0.026)
        // That line comes from the same two calls this probe makes - collectGeometry and
        // computePivots - on mesh.positions() of the same converted files, so reproducing it is
        // the check that this probe's frame mapping is the production frame. It is a sharp check:
        // a frame error moves a pivot by about a body height (see #drawnVertex), and the pivots
        // below are accepted only when they agree with the game's own numbers to 5 millimetres.
        // "torso" is the hip a second time because YsmBindArmature#computePivots puts the hip on
        // both joint 0 and joint 7, and the log prints the same variable in both slots.
        Map<String, Vector3f> logged = new LinkedHashMap<>();
        logged.put("root", new Vector3f(0.000F, 0.935F, -0.002F));
        logged.put("torso", new Vector3f(0.000F, 0.935F, -0.002F));
        logged.put("chest", new Vector3f(0.000F, 1.134F, 0.012F));
        logged.put("head", new Vector3f(0.000F, 1.333F, 0.027F));
        logged.put("shoulderR", new Vector3f(0.121F, 1.321F, 0.025F));
        logged.put("elbowR", new Vector3f(0.219F, 1.095F, 0.028F));
        logged.put("wristR", new Vector3f(0.244F, 0.881F, 0.026F));
        Map<String, Vector3f> reproduced = new LinkedHashMap<>();
        reproduced.put("root", pivots.byJoint().get(JointTable.ROOT));
        reproduced.put("torso", pivots.byJoint().get(JointTable.TORSO));
        reproduced.put("chest", pivots.byJoint().get(JointTable.CHEST));
        reproduced.put("head", pivots.byJoint().get(JointTable.HEAD));
        reproduced.put("shoulderR", pivots.byJoint().get(JointTable.ARM_R));
        reproduced.put("elbowR", pivots.byJoint().get(JointTable.HAND_R));
        reproduced.put("wristR", pivots.wristR());
        List<String> calibration = new ArrayList<>();
        String worstName = "-";
        float worstDelta = 0.0F;
        for (Map.Entry<String, Vector3f> entry : logged.entrySet()) {
            Vector3f got = reproduced.get(entry.getKey());
            float delta = got == null ? Float.NaN : got.distance(entry.getValue());
            if (!(delta <= worstDelta)) {
                worstDelta = delta;
                worstName = entry.getKey();
            }
            calibration.add(String.format(Locale.ROOT, "%s | %s | %s | %s | %s",
                    entry.getKey(), fmt(entry.getValue()), fmt(got),
                    Float.isNaN(delta) ? "-" : String.format(Locale.ROOT, "%.4f", delta),
                    Float.isNaN(delta) ? "MISSING" : (delta <= CALIBRATION_TOLERANCE ? "ok" : "OFF")));
        }
        System.out.println("calibration against the game log: worst " + worstName + " = "
                + worstDelta + " blocks");
        for (String line : calibration) {
            System.out.println("   " + line);
        }
        assertTrue(worstDelta <= CALIBRATION_TOLERANCE,
                "the drawn-frame pivots must reproduce the game's own [bind] line for this model;"
                        + " worst was " + worstName + " off by " + worstDelta + " blocks: " + calibration);

        StringBuilder md = new StringBuilder(1 << 18);
        md.append("# ").append(MODEL).append(" per-bone binding table\n\n");
        md.append("bones=").append(probe.names.length)
                .append("  bones with geometry=").append(probe.verticesByBone.size())
                .append("  mesh parts=").append(probe.partCount)
                .append("  hidden(default form)=").append(probe.hidden.size()).append(' ').append(probe.hidden)
                .append("  scale=").append(pkg.widthScale).append('/').append(pkg.heightScale)
                .append("\n\n");

        md.append("## warnings\n\n");
        if (warnings.isEmpty()) {
            md.append("- (none)\n");
        }
        for (String warning : warnings) {
            md.append("- ").append(warning).append('\n');
        }
        md.append('\n');

        md.append("## joint table\n\n");
        md.append("joint | name | settled pivot | tier relaxed | bones that placed it | owned geometry bounds | verts\n");
        md.append("---|---|---|---|---|---|---\n");
        Map<Integer, List<String>> placersByJoint = new TreeMap<>();
        for (int i = 0; i < probe.names.length; i++) {
            int joint = probe.joints[i];
            Integer settledTier = geometry.relaxation() == null ? null
                    : geometry.relaxation().tierByJoint().get(joint);
            if (geometry.byBone().containsKey(i)
                    && (settledTier == null || probe.tiers[i] <= settledTier)) {
                placersByJoint.computeIfAbsent(joint, k -> new ArrayList<>()).add(probe.names[i]);
            }
        }
        for (Map.Entry<Integer, List<Vector3f>> entry : new TreeMap<>(geometry.byJoint()).entrySet()) {
            int joint = entry.getKey();
            md.append(joint).append(" | ").append(JointTable.nameOf(joint))
                    .append(" | ").append(fmt(pivots.byJoint().get(joint)))
                    .append(" | ").append(geometry.relaxation() == null ? "-"
                            : String.valueOf(geometry.relaxation().tierByJoint().get(joint)))
                    .append(" | ").append(placersByJoint.getOrDefault(joint, List.of()))
                    .append(" | ").append(bounds(entry.getValue()))
                    .append(" | ").append(entry.getValue().size())
                    .append('\n');
        }
        md.append('\n');

        md.append("## calibration: this probe against the game's own [bind] line\n\n");
        md.append("pivot | game (latest.log line 1682) | this probe | delta (blocks) | verdict\n");
        md.append("---|---|---|---|---\n");
        for (String line : calibration) {
            md.append(line.replace(" | ", " | ")).append('\n');
        }
        md.append('\n');
        md.append("worst delta ").append(String.format(Locale.ROOT, "%.4f", worstDelta))
                .append(" blocks (").append(worstName).append("); tolerance ")
                .append(CALIBRATION_TOLERANCE).append('\n');
        md.append("The [bind] line is printed from the same two calls this probe makes, on the same"
                + " converted files, so agreement here is what makes every other number in this"
                + " report admissible: a frame error would move a pivot about a body height.\n\n");
        md.append("### which build the EKU log came from\n\n");
        md.append("The physics-segment line of that same log is NOT from the shipped code, and its"
                + " `L=` column says so: it prints L=2.045 for LeftBackHair1_1 and L=2.011 for mao2,"
                + " which are the levers a pivot carrying the writer's corner turn would have"
                + " (2.045 and 2.011 in this probe's turned column), where the shipped pivot gives"
                + " 0.117 and 0.102. A lever is pose-independent (it is |centroid - bindPivot| of the"
                + " bind geometry), so this dates that log to the reverted turn revision. The [bind]"
                + " pivots above are unaffected by it - they never read a physics pivot - which is"
                + " why they are still the calibration.\n\n");

        md.append("## leg joints against their own geometry\n\n");
        md.append("joint | name | settled pivot | geometry bounds | pivot - bounds centre | pivot inside bounds? | pivot.y - top.y\n");
        md.append("---|---|---|---|---|---|---\n");
        for (int joint : new int[]{JointTable.ROOT, JointTable.THIGH_R, JointTable.THIGH_L,
                JointTable.LEG_R, JointTable.LEG_L, JointTable.KNEE_R, JointTable.KNEE_L,
                JointTable.TORSO, JointTable.CHEST, JointTable.HEAD}) {
            List<Vector3f> owned = geometry.byJoint().get(joint);
            Vector3f pivot = pivots.byJoint().get(joint);
            md.append(joint).append(" | ").append(JointTable.nameOf(joint))
                    .append(" | ").append(fmt(pivot))
                    .append(" | ").append(owned == null ? "-" : bounds(owned))
                    .append(" | ").append(owned == null || pivot == null ? "-" : fmt(centroid(owned).sub(pivot, new Vector3f())))
                    .append(" | ").append(owned == null || pivot == null ? "-" : String.valueOf(inside(pivot, owned)))
                    .append(" | ").append(owned == null || pivot == null ? "-" : String.format(Locale.ROOT, "%.4f", pivot.y - top(owned)))
                    .append('\n');
        }
        md.append('\n');

        md.append("## the leg as a chain: does the pivot tile the leg's own geometry?\n\n");
        md.append("side | leg geometry bounds (all three joints) | thigh joint | leg joint | knee joint | each pivot inside the leg box? | height of each pivot within the leg\n");
        md.append("---|---|---|---|---|---|---\n");
        for (int[] side : LEG_SIDES) {
            List<Vector3f> legGeometry = new ArrayList<>();
            for (int joint : side) {
                List<Vector3f> owned = geometry.byJoint().get(joint);
                if (owned != null) {
                    legGeometry.addAll(owned);
                }
            }
            List<Vector3f> pivotsOfSide = new ArrayList<>();
            boolean allInside = true;
            StringBuilder heights = new StringBuilder();
            for (int joint : side) {
                Vector3f pivot = pivots.byJoint().get(joint);
                pivotsOfSide.add(pivot);
                boolean in = pivot != null && !legGeometry.isEmpty() && inside(pivot, legGeometry);
                allInside &= in;
                if (heights.length() > 0) {
                    heights.append(", ");
                }
                heights.append(String.format(Locale.ROOT, "%.3f", pivot == null ? Float.NaN : pivot.y));
            }
            md.append(JointTable.nameOf(side[0])).append(" | ").append(bounds(legGeometry))
                    .append(" | ").append(fmt(pivotsOfSide.get(0)))
                    .append(" | ").append(fmt(pivotsOfSide.get(1)))
                    .append(" | ").append(fmt(pivotsOfSide.get(2)))
                    .append(" | ").append(allInside)
                    .append(" | ").append(heights)
                    .append('\n');
        }
        md.append('\n');

        md.append("## leg-region bones: which joint draws them, and does that joint's pivot sit on them?\n\n");
        md.append("bone | joint | mapped | own geometry bounds | own centroid | joint pivot | |centroid - joint pivot| | joint pivot inside own geometry?\n");
        md.append("---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < probe.names.length; i++) {
            if (!LEG_NAME.matcher(probe.names[i]).find()) {
                continue;
            }
            List<Vector3f> verts = probe.verticesByBone.get(i);
            Vector3f jointPivot = pivots.byJoint().get(probe.joints[i]);
            md.append(probe.names[i])
                    .append(" | ").append(probe.joints[i]).append(' ').append(JointTable.nameOf(probe.joints[i]))
                    .append(" | ").append(probe.mapped[i])
                    .append(" | ").append(verts == null || verts.isEmpty() ? "-" : bounds(verts))
                    .append(" | ").append(verts == null || verts.isEmpty() ? "-" : fmt(centroid(verts)))
                    .append(" | ").append(fmt(jointPivot))
                    .append(" | ").append(verts == null || verts.isEmpty() || jointPivot == null ? "-"
                            : String.format(Locale.ROOT, "%.4f", centroid(verts).distance(jointPivot)))
                    .append(" | ").append(verts == null || verts.isEmpty() || jointPivot == null ? "-"
                            : String.valueOf(inside(jointPivot, verts)))
                    .append('\n');
        }
        md.append('\n');

        md.append("## per-bone table\n\n");
        md.append("bone | parent | chain(parent<-) | joint | mapped | tier | authored pivot | geometry bounds | centroid | pivot in mesh space | settled joint pivot | |pivot - joint pivot| | |centroid - pivot| | mirrored counterpart\n");
        md.append("---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < probe.names.length; i++) {
            String name = probe.names[i];
            int joint = probe.joints[i];
            Vector3f meshPivot = probe.meshPivot[i];
            Vector3f jointPivot = pivots.byJoint().get(joint);
            List<Vector3f> verts = probe.verticesByBone.get(i);
            md.append(name)
                    .append(" | ").append(probe.parents[i])
                    .append(" | ").append(probe.chain[i])
                    .append(" | ").append(joint).append(' ').append(JointTable.nameOf(joint))
                    .append(" | ").append(probe.mapped[i])
                    .append(" | ").append(probe.tiers[i] > 0 ? String.valueOf(probe.tiers[i]) : "-")
                    .append(" | ").append(fmt(probe.authoredPivot[i]))
                    .append(" | ").append(verts == null || verts.isEmpty() ? "-" : bounds(verts))
                    .append(" | ").append(verts == null || verts.isEmpty() ? "-" : fmt(centroid(verts)))
                    .append(" | ").append(fmt(meshPivot))
                    .append(" | ").append(fmt(jointPivot))
                    .append(" | ").append(meshPivot == null || jointPivot == null ? "-"
                            : String.format(Locale.ROOT, "%.4f", meshPivot.distance(jointPivot)))
                    .append(" | ").append(verts == null || verts.isEmpty() || meshPivot == null ? "-"
                            : String.format(Locale.ROOT, "%.4f", centroid(verts).distance(meshPivot)))
                    .append(" | ").append(probe.mirror.getOrDefault(name, "-"))
                    .append('\n');
        }

        Path out = Paths.get("build", "reports", "ysm-eku-binding.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
        System.out.println("EKU binding table written to " + out.toAbsolutePath());

        for (int joint : new int[]{JointTable.ROOT, JointTable.THIGH_R, JointTable.LEG_R,
                JointTable.KNEE_R, JointTable.THIGH_L, JointTable.LEG_L, JointTable.KNEE_L,
                JointTable.CHEST, JointTable.HEAD}) {
            List<Vector3f> owned = geometry.byJoint().get(joint);
            Vector3f pivot = pivots.byJoint().get(joint);
            System.out.println(String.format(Locale.ROOT,
                    "joint %2d %-10s pivot=%s  geometry=%s  pivotInside=%s",
                    joint, JointTable.nameOf(joint), fmt(pivot),
                    owned == null ? "-" : bounds(owned),
                    owned == null || pivot == null ? "-" : String.valueOf(inside(pivot, owned))));
        }
    }

    /**
     * The frame cross-check: {@code YsmPhysicsParts#pivotInMeshSpace} names a segment's pivot, the
     * collision volumes are built from the converted mesh's vertices, and a part delta is applied to
     * those same vertices - so all three have to be in one frame, the one
     * {@link #drawnVertex} maps the stored numbers into. This method measures the production pivot,
     * the corner-turned pivot the reverted revision named, and the previous round's mixed-frame
     * comparison, each against this bone's own geometry as drawn, and asserts the production one is
     * the one that lands on it.
     */
    @Test
    void frameCrossCheckOnTheRealConvertedFiles() throws Exception {
        String configured = configuredConfigRoot();
        assumeTrue(!configured.isEmpty(),
                "set -D" + YsmModelPackage.CONFIG_ROOT_PROPERTY + "=<config/yes_steve_model> (or the "
                        + YsmModelPackage.CONFIG_ROOT_ENV + " environment variable) to run the cross-check");
        Path configDir = Paths.get(configured).toAbsolutePath().getParent();
        Path pack = configDir.resolve("ysm_epicfight_compat").resolve("resourcepack").resolve("assets")
                .resolve("ysm_epicfight_compat");
        Path runtimeFile = findConverted(pack.resolve("ysm_runtime").resolve("entity"), "eku");
        Path meshFile = runtimeFile == null ? null
                : findConverted(pack.resolve("animmodels").resolve("entity"), "eku");
        assumeTrue(runtimeFile != null && meshFile != null,
                "no converted EKU files under " + pack);

        JsonObject runtimeJson = JsonParser.parseString(
                Files.readString(runtimeFile, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject meshJson = JsonParser.parseString(
                Files.readString(meshFile, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray bonesJson = runtimeJson.getAsJsonArray("bones");
        float scale = 0.7F;
        if (runtimeJson.has("scale") && runtimeJson.get("scale").isJsonArray()) {
            scale = runtimeJson.getAsJsonArray("scale").get(0).getAsFloat();
        }

        int count = bonesJson.size();
        String[] names = new String[count];
        Vector3f[] authored = new Vector3f[count];
        int[] parentIndex = new int[count];
        Map<String, Integer> indexOfName = new HashMap<>();
        for (int i = 0; i < count; i++) {
            JsonObject bone = bonesJson.get(i).getAsJsonObject();
            names[i] = bone.get("name").getAsString();
            JsonArray pivot = bone.getAsJsonArray("pivot");
            authored[i] = new Vector3f(pivot.get(0).getAsFloat(), pivot.get(1).getAsFloat(),
                    pivot.get(2).getAsFloat());
            indexOfName.put(names[i], i);
        }
        for (int i = 0; i < count; i++) {
            String parent = bonesJson.get(i).getAsJsonObject().has("parent")
                    ? bonesJson.get(i).getAsJsonObject().get("parent").getAsString() : "";
            parentIndex[i] = parent.isEmpty() ? -1 : indexOfName.getOrDefault(parent, -1);
        }
        Matrix4f[] bindWorld = new Matrix4f[count];
        for (int i = 0; i < count; i++) {
            bindWorldOf(bonesJson, parentIndex, bindWorld, i, 0);
        }

        JsonObject verticesJson = meshJson.getAsJsonObject("vertices");
        JsonArray positionsJson = verticesJson.getAsJsonObject("positions").getAsJsonArray("array");
        float[] positions = new float[positionsJson.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = positionsJson.get(i).getAsFloat();
        }
        Map<Integer, List<Vector3f>> verts = new LinkedHashMap<>();
        Map<Integer, List<Vector3f>> rawVerts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : verticesJson.getAsJsonObject("parts").entrySet()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            Integer boneIndex = indexOfName.get(partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (boneIndex == null) {
                continue;
            }
            JsonArray indices = entry.getValue().getAsJsonObject().getAsJsonArray("array");
            List<Vector3f> list = verts.computeIfAbsent(boneIndex, k -> new ArrayList<>());
            List<Vector3f> fileFrameList = rawVerts.computeIfAbsent(boneIndex, k -> new ArrayList<>());
            for (JsonElement index : indices) {
                int p = index.getAsInt() * 3;
                if (p + 2 < positions.length) {
                    fileFrameList.add(new Vector3f(positions[p], positions[p + 1], positions[p + 2]));
                    list.add(drawnVertex(positions, p));
                }
            }
        }

        // Three readings, and the comparison is only admissible because all three are measured
        // against the SAME thing: this bone's own geometry as the game draws it (drawnVertex). A
        // pivot-to-centroid distance is meaningless when the two sides are in different frames -
        // that is what the previous round's number was, and it is the third row below.
        //
        //   0. production: YsmPhysicsParts#pivotInMeshSpace, what the physics really names.
        //   1. the writer's corner turn applied to that pivot - the reverted revision, which this
        //      row is here to keep visibly far away.
        //   2. the previous round's mixed metric: the production pivot measured against the
        //      centroid of the geometry in the FILE frame, which is the comparison that reported
        //      1.672 -> 0.074 and was read as a 22x improvement.
        String[] labels = {
                "production pivotInMeshSpace() vs own geometry as drawn",
                "reverted revision (pivot corner-turned) vs own geometry as drawn",
                "previous round's metric: production pivot vs own geometry in the FILE frame"};
        double[] sums = new double[3];
        double[] medians = new double[3];
        int[] insideCount = new int[3];
        int compared = 0;
        List<Double>[] distances = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
        for (int i = 0; i < count; i++) {
            List<Vector3f> own = verts.get(i);
            if (own == null || own.isEmpty()) {
                continue;
            }
            Vector3f p = new Vector3f(authored[i]);
            bindWorld[i].transformPosition(p);
            Vector3f centre = centroid(own);
            Vector3f fileFrameCentre = centroid(rawVerts.get(i));
            Vector3f asProduction = YsmPhysicsParts.pivotInMeshSpace(bindWorld[i],
                    authored[i].x, authored[i].y, authored[i].z, scale, scale);
            Vector3f asTurned = new Vector3f(p.x, -p.z, p.y).mul(scale);
            if (asProduction == null) {
                continue;
            }
            distances[0].add((double) asProduction.distance(centre));
            distances[1].add((double) asTurned.distance(centre));
            distances[2].add((double) asProduction.distance(fileFrameCentre));
            sums[0] += asProduction.distance(centre);
            sums[1] += asTurned.distance(centre);
            sums[2] += asProduction.distance(fileFrameCentre);
            if (inside(asProduction, own)) {
                insideCount[0]++;
            }
            if (inside(asTurned, own)) {
                insideCount[1]++;
            }
            if (inside(asProduction, rawVerts.get(i))) {
                insideCount[2]++;
            }
            compared++;
        }
        for (int f = 0; f < 3; f++) {
            java.util.Collections.sort(distances[f]);
            medians[f] = distances[f].get(distances[f].size() / 2);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("frame cross-check over ").append(compared).append(" bones with geometry, scale=")
                .append(scale).append('\n');
        sb.append("pivot - own geometry centroid, in blocks, all three measured against the drawn"
                + " geometry unless stated\n");
        for (int f = 0; f < 3; f++) {
            sb.append(labels[f]).append("  mean=")
                    .append(String.format(Locale.ROOT, "%.4f", sums[f] / Math.max(1, compared)))
                    .append("  median=").append(String.format(Locale.ROOT, "%.4f", medians[f]))
                    .append("  pivot inside its own drawn bbox=").append(insideCount[f])
                    .append('/').append(compared)
                    .append('\n');
        }
        System.out.println(sb);

        // The direction of the answer, asserted rather than only printed: the production pivot sits
        // on the geometry it drives and the other two do not. The margins are wide (the measured
        // means are ~0.03 against ~1.7 blocks) because this is a statement about which frame is
        // right, not about a tuning constant.
        double production = sums[0] / Math.max(1, compared);
        double turned = sums[1] / Math.max(1, compared);
        double mixed = sums[2] / Math.max(1, compared);
        assertTrue(production * 4.0 < turned,
                "the corner-turned pivot must be far from the geometry it is applied to; got"
                        + " production=" + production + " turned=" + turned);
        assertTrue(production * 4.0 < mixed,
                "and so must the mixed-frame metric the reverted revision was justified by; got"
                        + " production=" + production + " mixed=" + mixed);
        assertTrue(insideCount[0] * 4 > compared,
                "the production pivot must sit inside its own drawn geometry for most bones; got "
                        + insideCount[0] + '/' + compared);

        // Per-joint means for the frame production uses, so the report can say which joints are off.
        Path out = Paths.get("build", "reports", "ysm-eku-frame-check.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    /** The converted runtime + mesh, read back into plain tables. */
    private static final class Probe {
        final YsmBindArmature.GeometryInput input;
        final Set<String> hidden;
        final String[] names;
        final String[] parents;
        final String[] chain;
        final int[] joints;
        final boolean[] mapped;
        final int[] tiers;
        final Vector3f[] authoredPivot;
        final Vector3f[] meshPivot;
        final Map<Integer, List<Vector3f>> verticesByBone;
        final Map<String, String> mirror;
        final int partCount;

        private Probe(YsmBindArmature.GeometryInput input, Set<String> hidden, String[] names,
                      String[] parents, String[] chain, int[] joints, boolean[] mapped, int[] tiers,
                      Vector3f[] authoredPivot, Vector3f[] meshPivot,
                      Map<Integer, List<Vector3f>> verticesByBone, Map<String, String> mirror,
                      int partCount) {
            this.input = input;
            this.hidden = hidden;
            this.names = names;
            this.parents = parents;
            this.chain = chain;
            this.joints = joints;
            this.mapped = mapped;
            this.tiers = tiers;
            this.authoredPivot = authoredPivot;
            this.meshPivot = meshPivot;
            this.verticesByBone = verticesByBone;
            this.mirror = mirror;
            this.partCount = partCount;
        }

        static Probe read(Path meshFile, Path runtimeFile, float scaleW, float scaleH, String modelId)
                throws IOException {
            JsonObject runtimeJson = JsonParser.parseString(
                    Files.readString(runtimeFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject meshJson = JsonParser.parseString(
                    Files.readString(meshFile, StandardCharsets.UTF_8)).getAsJsonObject();

            JsonArray bonesJson = runtimeJson.getAsJsonArray("bones");
            int count = bonesJson.size();
            String[] names = new String[count];
            String[] parents = new String[count];
            String[] chain = new String[count];
            int[] joints = new int[count];
            boolean[] mapped = new boolean[count];
            Vector3f[] authoredPivot = new Vector3f[count];
            Vector3f[] meshPivot = new Vector3f[count];
            int[] parentIndex = new int[count];
            Map<String, Integer> indexOfName = new HashMap<>();
            for (int i = 0; i < count; i++) {
                JsonObject bone = bonesJson.get(i).getAsJsonObject();
                names[i] = bone.get("name").getAsString();
                parents[i] = bone.has("parent") ? bone.get("parent").getAsString() : "";
                joints[i] = bone.get("joint").getAsInt();
                mapped[i] = bone.get("mapped").getAsBoolean();
                JsonArray pivot = bone.getAsJsonArray("pivot");
                authoredPivot[i] = new Vector3f(pivot.get(0).getAsFloat(), pivot.get(1).getAsFloat(),
                        pivot.get(2).getAsFloat());
                indexOfName.put(names[i], i);
            }
            for (int i = 0; i < count; i++) {
                parentIndex[i] = parents[i].isEmpty() ? -1
                        : indexOfName.getOrDefault(parents[i], -1);
                List<String> links = new ArrayList<>();
                int cursor = parentIndex[i];
                int guard = 0;
                while (cursor >= 0 && guard++ < 64) {
                    links.add(names[cursor]);
                    cursor = parentIndex[cursor];
                }
                chain[i] = String.join("<-", links);
            }

            // The bind chain, exactly as YSMRuntimeModel computes it.
            Matrix4f[] bindWorld = new Matrix4f[count];
            for (int i = 0; i < count; i++) {
                bindWorldOf(bonesJson, parentIndex, bindWorld, i, 0);
            }
            for (int i = 0; i < count; i++) {
                meshPivot[i] = meshSpacePivot(bindWorld[i], authoredPivot[i], scaleW, scaleH);
            }

            Set<String> hidden = YSMRuntimeModel.computeDefaultHiddenBoneNames(runtimeJson);
            Set<String> baseForms = BoneAlternateForms.baseFormsPresent(names);
            int[] tiers = new int[count];
            for (int i = 0; i < count; i++) {
                tiers[i] = YsmBindArmature.tierOf(names[i], baseForms, hidden);
            }

            JsonObject verticesJson = meshJson.getAsJsonObject("vertices");
            JsonArray positionsJson = verticesJson.getAsJsonObject("positions").getAsJsonArray("array");
            float[] positions = new float[positionsJson.size()];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = positionsJson.get(i).getAsFloat();
            }
            Map<Integer, List<Vector3f>> verticesByBone = new LinkedHashMap<>();
            List<YsmBindArmature.BoneGeometry> parts = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : verticesJson.getAsJsonObject("parts").entrySet()) {
                String partName = entry.getKey();
                if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                    continue;
                }
                Integer boneIndex = indexOfName.get(partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
                if (boneIndex == null) {
                    continue;
                }
                JsonArray indices = entry.getValue().getAsJsonObject().getAsJsonArray("array");
                List<Vector3f> vertices = new ArrayList<>(indices.size());
                for (JsonElement index : indices) {
                    int p = index.getAsInt() * 3;
                    if (p + 2 < positions.length) {
                        Vector3f v = drawnVertex(positions, p);
                        vertices.add(v);
                        verticesByBone.computeIfAbsent(boneIndex, k -> new ArrayList<>()).add(v);
                    }
                }
                parts.add(new YsmBindArmature.BoneGeometry(boneIndex, vertices));
            }

            YsmBindArmature.GeometryInput input = new YsmBindArmature.GeometryInput(
                    modelId, names, joints, mapped, hidden, parts);
            return new Probe(input, hidden, names, parents, chain, joints, mapped, tiers,
                    authoredPivot, meshPivot, verticesByBone, mirrorPairs(names, meshPivot), parts.size());
        }
    }

    /** {@code YSMRuntimeModel#computeBindWorld}. */
    private static Matrix4f bindWorldOf(JsonArray bones, int[] parentIndex, Matrix4f[] cache, int index,
                                        int depth) {
        if (depth > 64) {
            throw new IllegalStateException("cyclic bone hierarchy in the runtime table");
        }
        if (cache[index] != null) {
            return cache[index];
        }
        Matrix4f local = bindLocalOf(bones.get(index).getAsJsonObject());
        int parent = parentIndex[index];
        Matrix4f world = parent >= 0
                ? new Matrix4f(bindWorldOf(bones, parentIndex, cache, parent, depth + 1)).mul(local)
                : local;
        cache[index] = world;
        return world;
    }

    /** {@code YSMRuntimeModel#computeBindLocal}. */
    private static Matrix4f bindLocalOf(JsonObject bone) {
        JsonArray pivot = bone.getAsJsonArray("pivot");
        JsonArray rot = bone.getAsJsonArray("rot");
        float px = pivot.get(0).getAsFloat();
        float py = pivot.get(1).getAsFloat();
        float pz = pivot.get(2).getAsFloat();
        return new Matrix4f().translation(px, py, pz)
                .rotateZ(rot.get(2).getAsFloat())
                .rotateY(rot.get(1).getAsFloat())
                .rotateX(rot.get(0).getAsFloat())
                .translate(-px, -py, -pz);
    }

    /** {@code YsmPhysicsParts#pivotInMeshSpace}. */
    private static Vector3f meshSpacePivot(Matrix4f bindWorld, Vector3f pivot, float scaleW, float scaleH) {
        Vector3f out = new Vector3f(pivot);
        bindWorld.transformPosition(out);
        return out.mul(scaleW, scaleH, scaleW);
    }

    /**
     * A vertex out of the converted mesh JSON, in the frame the game actually draws it in.
     *
     * <p>The JSON is in the <b>file</b> frame: {@link EFMeshJsonWriter#walkBone} stores every
     * corner as {@code (x.scaleW, -z.scaleW, y.scaleH)} - the author's Z-up frame turned into the
     * Z-up frame Epic Fight's own meshes are authored in. Epic Fight's loader turns it back on load
     * ({@code JsonAssetLoader} applies {@code rotate(-90 deg, X)}), so {@code mesh.positions()} -
     * the array {@link YsmBindArmature#inputOf} feeds to the pivot rules, and the array a part delta
     * multiplies - is the authored chain scaled once with no turn. Its relation to the stored
     * numbers is exactly {@code (X, Y, Z) -> (X, Z, -Y)}.
     *
     * <p>This mapping is the whole correction this probe needed, and it is worth stating what it
     * cost to get wrong: reading these numbers raw puts y and z the wrong way round, and every
     * "top of the geometry" ({@link YsmBindArmature#topOf} takes the maximum <b>y</b>) then lands on
     * the front of the model instead of on top of it. The earlier version of this probe did exactly
     * that and produced a per-joint table in which the leg pivots are meaningless.
     */
    private static Vector3f drawnVertex(float[] positions, int p) {
        return new Vector3f(positions[p], positions[p + 2], -positions[p + 1]);
    }

    /** For each bone whose name reads as one of a mirrored pair, the name of the other side. */
    private static Map<String, String> mirrorPairs(String[] names, Vector3f[] meshPivot) {
        Map<String, String> byName = new HashMap<>();
        for (String name : names) {
            byName.put(name.toLowerCase(Locale.ROOT), name);
        }
        Map<String, String> mirror = new LinkedHashMap<>();
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            String other = null;
            if (lower.startsWith("left")) {
                other = byName.get("right" + lower.substring(4));
            } else if (lower.startsWith("right")) {
                other = byName.get("left" + lower.substring(5));
            } else if (lower.contains("left")) {
                other = byName.get(lower.replace("left", "right"));
            } else if (lower.contains("right")) {
                other = byName.get(lower.replace("right", "left"));
            }
            if (other != null) {
                mirror.put(name, other);
            }
        }
        return mirror;
    }

    private static boolean inside(Vector3f point, List<Vector3f> vertices) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }
        return point.x >= minX && point.x <= maxX && point.y >= minY && point.y <= maxY
                && point.z >= minZ && point.z <= maxZ;
    }

    private static String bounds(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return "-";
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }
        return String.format(Locale.ROOT, "[%.3f..%.3f %.3f..%.3f %.3f..%.3f]",
                minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static Vector3f centroid(List<Vector3f> vertices) {
        Vector3f sum = new Vector3f();
        for (Vector3f v : vertices) {
            sum.add(v);
        }
        return sum.div(Math.max(1, vertices.size()));
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

    /** The converted file of a model under a pack directory, found by a case-insensitive needle. */
    private static Path findConverted(Path dir, String needle) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .contains(needle.toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElse(null);
        }
    }
}
