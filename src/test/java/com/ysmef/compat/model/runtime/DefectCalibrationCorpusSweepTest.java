package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.BoneAlternateForms;
import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.model.YSMJointMapper;
import com.ysmef.compat.ysm.YsmBinaryReader;
import com.ysmef.compat.ysm.YsmFileCrypto;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measurement, not an assertion: the two "threshold" rules of round 19 calibrated over the whole
 * model corpus, so both can be judged by their blast radius before they ship. Run with
 * {@code YSMEF_YSM_CORPUS_ROOT} (e.g. {@code E:\program\JAVA\ysm-model-repo}, 906 packages).
 *
 * <p>One walk of the corpus answers both questions, because parsing and baking 906 models is the
 * expensive part and both rules are read from the same baked geometry:
 *
 * <ol>
 *   <li><b>The wrap rule of {@link YsmPhysicsParts#wrapsPivot}</b> - the direction spread, from each
 *       simulated bone's pivot to its own vertices, that separates a strand (concentrated on one
 *       axis) from a band, scalp or hat that closes around the pivot. The report prints the
 *       distribution and, for every candidate threshold, how many bones and models it would drop,
 *       plus the spread of the bones the game's own log named on the skirt model - the ones that
 *       must <i>keep</i> being simulated.</li>
 *   <li><b>The ornament gate of {@link YsmBindArmature#neckPivot}</b> - whether the top ring of the
 *       Chest joint's settled geometry is the body's collar or a one-sided decoration, and what the
 *       Head pivot's horizontal position becomes when it is. Every candidate (ring fraction x axis
 *       offset x replacement axis) is evaluated against the model's own authored
 *       {@code AllHead}/{@code MHead}/{@code Head} control, which is the closest thing to an oracle
 *       the data has.</li>
 * </ol>
 *
 * <p><b>Stated approximation.</b> The bones hidden in a model's default form are evaluated from the
 * model's parallel animations at conversion time, which this offline walk does not do, so the hidden
 * set passed to {@link YsmBindArmature#tierOf} is empty. Hidden bones therefore count as tier 0 here
 * and can join the Chest joint's settled geometry. The two models the rules were written against are
 * also checked through the exact production path, hidden set included, by
 * {@code HeadSkirtDefectDiagTest} and {@code DefectRuleFocusedTest}; this sweep is the breadth
 * measurement, those are the point measurements.
 */
class DefectCalibrationCorpusSweepTest {

    /** The physics bones the game's own log named for the 兽耳酱x1 twin (see HeadSkirtDefectDiagTest). */
    private static final List<String> LOGGED_SKIRT_BONES = List.of(
            "X_T4_1", "X_yiqun1", "X_T3_1", "X_T6_1", "X_Hair1", "X_T5_1", "X_liuhai_F_1", "X_fawei1",
            "X_T7_1", "Left_ear_F_1", "X_T2_1", "X_qunzi1", "Right_ear_R_1", "X_liuhai_Z_1", "X_liuhai_R_1");

    /** Candidate thresholds for the wrap rule's largest-over-smallest direction spread. */
    private static final float[] SPREAD_THRESHOLDS = {1.05F, 1.1F, 1.15F, 1.2F, 1.3F, 1.5F, 2.0F, 3.0F};

    /** Candidate gates for the ornament rule: ring slots as a share of the joint's geometry. */
    private static final float[] RING_FRACTIONS = {0.02F, 0.05F, 0.10F, 0.25F, 1.0F};

    /** Candidate gates for the ornament rule: how far the ring sits from the body's axis, blocks. */
    private static final float[] RING_OFFSETS = {0.04F, 0.05F, 0.06F, 0.08F, 0.10F};

    /** The histogram edges of the direction spread, for the distribution table. */
    private static final float[] SPREAD_BUCKETS = {1.02F, 1.05F, 1.1F, 1.2F, 1.5F, 2.0F, 3.0F, 5.0F, 10.0F};

    /**
     * The candidate discriminators for "the geometry closes around its pivot", each read off the same
     * baked geometry. The first is the direction spread the task proposed; the others are the shapes
     * of the same idea that survive being measured on the model the rule exists for (see the report's
     * section for the NagaU_Kemomimi bones).
     */
    private static final List<String> WRAP_METRICS = List.of(
            "spreadBelow", "octants", "interior", "behindReach", "behindFraction", "aboveLever",
            "localCentre", "localBackReach", "globalOffset", "eigenRatioBelow", "bandOffset",
            "bandOffsetStrict", "bandOffsetStrictRing", "bandSpread", "bandSpreadStrict", "shipped");

    /** Every candidate threshold per metric, as "the value at which the rule would fire". */
    private static final Map<String, List<Float>> WRAP_THRESHOLDS = wrapThresholds();

    private static Map<String, List<Float>> wrapThresholds() {
        Map<String, List<Float>> thresholds = new LinkedHashMap<>();
        thresholds.put("spreadBelow", List.of(1.1F, 1.2F, 1.5F, 2.0F, 2.5F, 2.6F, 2.8F));
        thresholds.put("octants", List.of(5.0F, 6.0F, 7.0F, 8.0F));
        thresholds.put("interior", List.of(0.05F, 0.10F, 0.15F, 0.20F));
        thresholds.put("behindReach", List.of(0.3F, 0.5F, 0.7F, 1.0F));
        thresholds.put("behindFraction", List.of(0.2F, 0.3F, 0.4F));
        thresholds.put("aboveLever", List.of(0.3F, 0.5F, 1.0F));
        thresholds.put("localCentre", List.of(0.3F, 0.4F, 0.5F, 0.6F, 0.7F, 0.8F));
        thresholds.put("localBackReach", List.of(0.3F, 0.5F, 0.7F, 1.0F));
        thresholds.put("globalOffset", List.of(0.3F, 0.4F, 0.5F, 0.6F, 0.7F));
        thresholds.put("eigenRatioBelow", List.of(1.2F, 1.5F, 2.0F, 3.0F));
        thresholds.put("bandOffset", List.of(0.3F, 0.4F, 0.5F, 0.6F, 0.7F));
        thresholds.put("bandOffsetStrict", List.of(0.3F, 0.4F, 0.5F, 0.6F, 0.7F));
        thresholds.put("bandOffsetStrictRing", List.of(0.3F, 0.4F, 0.5F, 0.6F, 0.7F));
        thresholds.put("bandSpread", List.of(0.3F, 0.4F, 0.5F, 0.6F, 0.7F, 0.8F));
        thresholds.put("bandSpreadStrict", List.of(0.3F, 0.4F, 0.5F, 0.6F, 0.7F, 0.8F));
        thresholds.put("shipped", List.of(1.0F));
        return thresholds;
    }

    @Test
    void theWrapRuleAndTheNeckRuleOverTheCorpus() throws Exception {
        String root = System.getenv("YSMEF_YSM_CORPUS_ROOT");
        assumeTrue(root != null && !root.isEmpty(), "set YSMEF_YSM_CORPUS_ROOT to sweep the model corpus");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));

        StringBuilder wrap = new StringBuilder();
        StringBuilder neck = new StringBuilder();
        StringBuilder shapeDumps = new StringBuilder();
        wrap.append("# The wrap rule over the corpus (round 19)\n\n")
                .append("Candidate discriminators for \"this bone's own geometry closes around its pivot rather than ")
                .append("hanging from it\" (`YsmPhysicsParts.wrapsPivot`), measured over every bone that could ever ")
                .append("be a segment: own geometry, a joint, tier 0, not directly mapped. The first candidate is ")
                .append("the direction spread the round proposed; the rest are the same idea measured other ways. ")
                .append("The calibration that matters is the NagaU_Kemomimi table at the end: the rule must drop ")
                .append("`X_yiqun1` (the skewed outer skirt band) and must NOT drop `X_qunzi1` (the panel below it), ")
                .append("`X_Hair1` or the hair/ear chains, whose spreads are 2.16 - 85.\n\n");

        long[] bucketCounts = new long[SPREAD_BUCKETS.length + 1];
        Map<Float, int[]> firedByThreshold = new LinkedHashMap<>();
        for (float threshold : SPREAD_THRESHOLDS) {
            firedByThreshold.put(threshold, new int[2]);
        }
        Map<Float, Integer> modelsFiredByThreshold = new LinkedHashMap<>();
        for (float threshold : SPREAD_THRESHOLDS) {
            modelsFiredByThreshold.put(threshold, 0);
        }
        List<String> firedBones = new ArrayList<>();
        List<String> loggedBoneSpreads = new ArrayList<>();
        long evaluatedBones = 0;
        long evaluatedModels = 0;
        long spreadFinite = 0;
        double sumSpread = 0;
        double worstSpread = 0;

        // ---- the candidate wrap discriminators, each with its own fire counters ----
        Map<String, long[]> metricFires = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> metricModels = new LinkedHashMap<>();
        Map<String, List<String>> metricRows = new LinkedHashMap<>();
        Map<String, long[]> metricSelectedFires = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> metricSelectedModels = new LinkedHashMap<>();
        for (String metric : WRAP_METRICS) {
            for (float threshold : WRAP_THRESHOLDS.get(metric)) {
                String key = metric + " @ " + threshold;
                metricFires.put(key, new long[1]);
                metricModels.put(key, new java.util.LinkedHashSet<>());
                metricRows.put(key, new ArrayList<>());
                metricSelectedFires.put(key, new long[1]);
                metricSelectedModels.put(key, new java.util.LinkedHashSet<>());
            }
        }
        long selectedBones = 0;
        Map<String, List<double[]>> metricDistributions = new LinkedHashMap<>();
        for (String metric : WRAP_METRICS) {
            metricDistributions.put(metric, new ArrayList<>());
        }

        // ---- neck accumulators ----
        Map<String, int[]> neckFired = new LinkedHashMap<>();
        Map<String, double[]> neckMove = new LinkedHashMap<>();
        Map<String, double[]> neckOracle = new LinkedHashMap<>();
        Map<String, String> neckWorst = new LinkedHashMap<>();
        Map<String, List<String>> neckRows = new LinkedHashMap<>();
        for (float fraction : RING_FRACTIONS) {
            for (float offset : RING_OFFSETS) {
                for (String replacement : new String[]{"hip", "chestCentroid", "restCentroid", "hipCapped", "hipRim"}) {
                    String key = String.format(Locale.ROOT, "%.2f|%.2f|%s", fraction, offset, replacement);
                    neckFired.put(key, new int[1]);
                    neckMove.put(key, new double[]{0, 0});
                    neckOracle.put(key, new double[]{0, 0, 0, 0});
                    neckRows.put(key, new ArrayList<>());
                }
            }
        }
        long neckModels = 0;
        long neckWithAuthor = 0;
        double sumCurrentOracle = 0;

        int parsed = 0;
        int skipped = 0;
        for (Path file : files) {
            String modelName = file.getFileName().toString();
            YSMGeoModel model;
            float scaleW;
            float scaleH;
            try {
                YsmBinaryReader.BinaryModel binary = YsmBinaryReader.read(
                        YsmFileCrypto.decryptYsmFile(Files.readAllBytes(file)));
                model = YSMGeoModel.fromBinary(binary);
                scaleW = binary.widthScale;
                scaleH = binary.heightScale;
            } catch (Throwable t) {
                skipped++;
                continue;
            }
            parsed++;
            if (parsed % 50 == 0) {
                System.out.println("[corpus sweep] parsed=" + parsed + " skipped=" + skipped
                        + " bones=" + evaluatedBones + " neckModels=" + neckModels);
            }

            List<YSMGeoModel.Bone> bones = new ArrayList<>(model.bonesByName.values());
            Map<String, Matrix4f> worlds = new HashMap<>();
            Map<String, List<Vector3f>> baked = new LinkedHashMap<>();
            for (YSMGeoModel.Bone bone : bones) {
                if (bone.quads.isEmpty()) {
                    continue;
                }
                List<Vector3f> vertices = new ArrayList<>(bone.quads.size() * 4);
                Matrix4f world = worldOf(bone, worlds, 0);
                for (YSMGeoModel.Quad quad : bone.quads) {
                    for (Vector3f corner : quad.positions) {
                        if (corner == null) {
                            continue;
                        }
                        Vector3f p = new Vector3f(corner).mulPosition(world);
                        vertices.add(new Vector3f(p.x * scaleW, p.y * scaleH, p.z * scaleW));
                    }
                }
                baked.put(bone.name, vertices);
            }

            // ---------------------------------------------------------------
            // Task A: the wrap rule, over every bone that could ever be a segment
            // ---------------------------------------------------------------
            Set<String> baseForms = BoneAlternateForms.baseFormsPresent(
                    model.bonesByName.keySet().toArray(new String[0]));
            boolean evaluableModel = false;
            List<Float> modelSpreads = new ArrayList<>();

            // The bones the production classifier would simulate on this model, so the wrap rule's
            // blast radius can be reported over the segments that actually exist rather than over
            // every bone that carries geometry. The author's own physics animation is not available
            // offline (it needs the converted runtime JSON), so this is the fallback path; it is the
            // lower bound of the real selection and the fairest available proxy.
            Map<String, Integer> boneIndexByName = new HashMap<>();
            for (int i = 0; i < bones.size(); i++) {
                boneIndexByName.put(bones.get(i).name, i);
            }
            YSMRuntimeModel.BoneRt[] bonesRt = new YSMRuntimeModel.BoneRt[bones.size()];
            Map<Integer, float[]> geometryByBone = new HashMap<>();
            Map<Integer, int[]> partsByBone = new HashMap<>();
            for (int i = 0; i < bones.size(); i++) {
                YSMGeoModel.Bone bone = bones.get(i);
                YSMRuntimeModel.BoneRt rt = new YSMRuntimeModel.BoneRt();
                rt.name = bone.name;
                rt.parent = bone.parent == null ? -1 : boneIndexByName.getOrDefault(bone.parent.name, -1);
                rt.joint = YSMJointMapper.resolveJointId(bone, model);
                rt.mapped = YSMJointMapper.isDirectlyMapped(bone);
                bonesRt[i] = rt;
                List<Vector3f> vertices = baked.get(bone.name);
                if (vertices != null && !vertices.isEmpty()) {
                    Vector3f centre = centroidOf(vertices);
                    geometryByBone.put(i, new float[]{centre.x, centre.y, centre.z, vertices.size()});
                    partsByBone.put(i, new int[]{0});
                }
            }
            java.util.function.IntPredicate ownsGeometry =
                    index -> YsmPhysicsParts.ownsItsGeometry(index, geometryByBone, partsByBone);
            java.util.Set<Integer> classified = new java.util.HashSet<>(YsmPhysicsParts.selectBones(
                    bonesRt, ownsGeometry, YsmPhysicsTuning.maxChains(), new int[1]));
            selectedBones += classified.size();

            for (int boneIdx = 0; boneIdx < bones.size(); boneIdx++) {
                YSMGeoModel.Bone bone = bones.get(boneIdx);
                List<Vector3f> vertices = baked.get(bone.name);
                if (vertices == null || vertices.size() < 4) {
                    continue;
                }
                if (YSMJointMapper.resolveJointId(bone, model) < 0 || YSMJointMapper.isDirectlyMapped(bone)
                        || YsmBindArmature.tierOf(bone.name, baseForms, Set.of()) != 0) {
                    continue;
                }
                Vector3f pivot = pivotOf(bone, worlds, scaleW, scaleH);
                if (pivot == null) {
                    continue;
                }
                float spread = YsmPhysicsParts.directionSpread(vertices, pivot);
                Vector3f centroid = centroidOf(vertices);
                float lever = centroid == null ? 0.0F : centroid.distance(pivot);
                if (!Float.isFinite(spread) || spread > 1.0E5F || lever < 0.01F) {
                    continue;
                }
                WrapStats stats = wrapStats(vertices, pivot, centroid, lever, spread);
                evaluatedBones++;
                evaluableModel = true;
                spreadFinite++;
                sumSpread += spread;
                worstSpread = Math.max(worstSpread, spread);
                modelSpreads.add(spread);
                for (Map.Entry<String, List<double[]>> distribution : metricDistributions.entrySet()) {
                    distribution.getValue().add(new double[]{stats.value(distribution.getKey())});
                }
                for (String metric : WRAP_METRICS) {
                    float value = stats.value(metric);
                    for (float threshold : WRAP_THRESHOLDS.get(metric)) {
                        if (!WrapStats.fires(metric, value, threshold)) {
                            continue;
                        }
                        String key = metric + " @ " + threshold;
                        metricFires.get(key)[0]++;
                        metricModels.get(key).add(modelName);
                        List<String> rows = metricRows.get(key);
                        if (rows.size() < 300) {
                            rows.add(String.format(Locale.ROOT,
                                    "%s  %s  value %.3f  category %s  joint %d  slots %d  lever %.3f  rest %s%s",
                                    modelName, bone.name, value, YsmPhysicsParts.categoryOf(bone.name),
                                    YSMJointMapper.resolveJointId(bone, model), vertices.size(), lever, fmt(stats.rest),
                                    classified.contains(boneIdx) ? "  [classifier segment]" : ""));
                        }
                        if (classified.contains(boneIdx)) {
                            metricSelectedFires.get(key)[0]++;
                            metricSelectedModels.get(key).add(modelName);
                        }
                    }
                }
                int spreadBucket = SPREAD_BUCKETS.length;
                for (int i = 0; i < SPREAD_BUCKETS.length; i++) {
                    if (spread < SPREAD_BUCKETS[i]) {
                        spreadBucket = i;
                        break;
                    }
                }
                bucketCounts[spreadBucket]++;
                for (float threshold : SPREAD_THRESHOLDS) {
                    if (spread < threshold) {
                        firedByThreshold.get(threshold)[0]++;
                        if (firedBones.size() < 2000) {
                            firedBones.add(String.format(Locale.ROOT,
                                    "%s  %s  spread %.3f  category %s  joint %d  slots %d  lever %.3f",
                                    modelName, bone.name, spread,
                                    YsmPhysicsParts.categoryOf(bone.name), YSMJointMapper.resolveJointId(bone, model),
                                    vertices.size(), lever));
                        }
                    }
                }
                if (modelName.startsWith("NagaU_Kemomimi") && LOGGED_SKIRT_BONES.contains(bone.name)) {
                    loggedBoneSpreads.add(String.format(Locale.ROOT,
                            "%-18s %s", bone.name, stats.describe()));
                    shapeDumps.append("\n### ").append(bone.name).append('\n')
                            .append(shapeDump(vertices, pivot, centroid)).append('\n');
                }
            }
            if (evaluableModel) {
                evaluatedModels++;
            }
            for (float threshold : SPREAD_THRESHOLDS) {
                for (float spread : modelSpreads) {
                    if (spread < threshold) {
                        modelsFiredByThreshold.merge(threshold, 1, Integer::sum);
                        break;
                    }
                }
            }

            // ---------------------------------------------------------------
            // Task B: the ornament gate, through the production geometry filter
            // ---------------------------------------------------------------
            int[] joint = new int[bones.size()];
            boolean[] mapped = new boolean[bones.size()];
            String[] names = new String[bones.size()];
            List<YsmBindArmature.BoneGeometry> parts = new ArrayList<>();
            for (int i = 0; i < bones.size(); i++) {
                YSMGeoModel.Bone bone = bones.get(i);
                names[i] = bone.name;
                joint[i] = YSMJointMapper.resolveJointId(bone, model);
                mapped[i] = YSMJointMapper.isDirectlyMapped(bone);
                List<Vector3f> vertices = baked.get(bone.name);
                if (vertices != null && !vertices.isEmpty()) {
                    parts.add(new YsmBindArmature.BoneGeometry(i, vertices));
                }
            }
            YsmBindArmature.GeometryInput input =
                    new YsmBindArmature.GeometryInput(modelName, names, joint, mapped, Set.of(), parts);
            YsmBindArmature.GeometryData geometry =
                    YsmBindArmature.collectGeometry(input, message -> { });
            List<Vector3f> chest = geometry.byJoint().get(8);
            if (chest == null || chest.isEmpty()) {
                continue;
            }
            neckModels++;
            Vector3f ring = topOf(chest);
            Vector3f hip = midpoint(topOf(geometry.byJoint().get(1)), topOf(geometry.byJoint().get(4)));
            Vector3f chestCentroid = centroidOf(chest);
            List<Vector3f> below = new ArrayList<>();
            for (Vector3f v : chest) {
                if (v.y < ring.y - 1.0E-4F) {
                    below.add(v);
                }
            }
            Vector3f restCentroid = centroidOf(below);
            Vector3f authored = authoredHeadPivot(bones, scaleW, scaleH);
            if (authored != null) {
                neckWithAuthor++;
                sumCurrentOracle += horizontal(ring, authored);
            }
            for (Map.Entry<String, int[]> candidate : neckFired.entrySet()) {
                String[] key = candidate.getKey().split("\\|");
                float fraction = Float.parseFloat(key[0]);
                float offsetGate = Float.parseFloat(key[1]);
                float hipOffset = hip == null ? Float.MAX_VALUE : (float) horizontal(ring, hip);
                // The same reading as the wrap rule's: a top ring is an ornament when it sits at the
                // rim of the chest geometry's own left-right footprint rather than on its centre -
                // the shape a one-sided decoration has, whatever share of the vertices it holds.
                float chestMinX = Float.MAX_VALUE;
                float chestMaxX = -Float.MAX_VALUE;
                for (Vector3f v : chest) {
                    chestMinX = Math.min(chestMinX, v.x);
                    chestMaxX = Math.max(chestMaxX, v.x);
                }
                float chestHalfWidth = (chestMaxX - chestMinX) * 0.5F;
                float ringAtRim = chestHalfWidth > 1.0E-5F
                        ? Math.abs(ring.x - (chestMinX + chestMaxX) * 0.5F) / chestHalfWidth : 0.0F;
                Vector3f replacement = "hip".equals(key[2]) ? hip
                        : "chestCentroid".equals(key[2]) ? chestCentroid
                        : "restCentroid".equals(key[2]) ? restCentroid
                        : "hipCapped".equals(key[2]) ? (hipOffset <= 0.25F ? hip : null)
                        : (hipOffset <= 0.25F && ringAtRim >= 0.5F ? hip : null);
                if (replacement == null) {
                    continue;
                }
                int ringSlots = ringSlots(chest, ring.y);
                float offset = (float) horizontal(ring, replacement);
                if (ringSlots > (int) (chest.size() * fraction) || offset < offsetGate) {
                    continue;
                }
                candidate.getValue()[0]++;
                Vector3f moved = new Vector3f(replacement.x, ring.y, replacement.z);
                double move = new Vector3f(moved).sub(ring).length();
                double[] moveStats = neckMove.get(candidate.getKey());
                moveStats[0] += move;
                if (move > moveStats[1]) {
                    moveStats[1] = move;
                    neckWorst.put(candidate.getKey(), modelName);
                }
                if (authored != null) {
                    double[] oracle = neckOracle.get(candidate.getKey());
                    double before = horizontal(ring, authored);
                    double after = horizontal(moved, authored);
                    oracle[0] += after;
                    oracle[1] = Math.max(oracle[1], after);
                    if (after < before - 1.0E-6) {
                        oracle[2]++;
                    } else if (after > before + 1.0E-6) {
                        oracle[3]++;
                    }
                }
                List<String> rows = neckRows.get(candidate.getKey());
                if (rows.size() < 40) {
                    rows.add(String.format(Locale.ROOT,
                            "%s  ring %d/%d (%.2f%%) rim %.2f at (%+.3f,%+.3f) y=%.3f  ->  (%+.3f,%+.3f)  move %.3f%s",
                            modelName, ringSlots, chest.size(), 100.0 * ringSlots / chest.size(), ringAtRim,
                            ring.x, ring.z, ring.y, moved.x, moved.z, move,
                            authored == null ? "" : String.format(Locale.ROOT,
                                    "  authoredHead (%+.3f,%+.3f): off-axis %.3f -> %.3f",
                                    authored.x, authored.z, horizontal(ring, authored), horizontal(moved, authored))));
                }
            }
        }

        wrap.append(String.format(Locale.ROOT,
                "- packages parsed=%d, undecryptable/unparseable=%d%n- bones with own geometry, a joint, "
                        + "tier 0 and not directly mapped: %d, over %d model(s)%n- spread: mean %.3f, worst %.3f%n%n",
                parsed, skipped, evaluatedBones, evaluatedModels,
                spreadFinite == 0 ? 0 : sumSpread / spreadFinite, worstSpread));
        wrap.append("## distribution\n\n| spread below | bones |\n|---|---|\n");
        for (int i = 0; i < SPREAD_BUCKETS.length; i++) {
            wrap.append(String.format(Locale.ROOT, "| %.2f | %d |%n", SPREAD_BUCKETS[i], bucketCounts[i]));
        }
        wrap.append(String.format(Locale.ROOT, "| (above %.2f) | %d |%n%n", SPREAD_BUCKETS[SPREAD_BUCKETS.length - 1],
                bucketCounts[SPREAD_BUCKETS.length]));

        wrap.append("## how many bones the wrap rule would drop, per candidate threshold\n\n")
                .append("| threshold | bones dropped | of ").append(evaluatedBones).append(" |\n|---|---|---|\n");
        for (Map.Entry<Float, int[]> entry : firedByThreshold.entrySet()) {
            wrap.append(String.format(Locale.ROOT, "| %.2f | %d (%.3f%%) |%n",
                    entry.getKey(), entry.getValue()[0], 100.0 * entry.getValue()[0] / Math.max(1, evaluatedBones)));
        }

        wrap.append("\n## every bone below spread 3.0\n\n```\n");
        for (String line : firedBones) {
            wrap.append(line).append('\n');
        }
        wrap.append("```\n");

        wrap.append("\n## the skirt model's own physics bones (the ones that must keep being simulated)\n\n```\n");
        if (loggedBoneSpreads.isEmpty()) {
            wrap.append("(NagaU_Kemomimi.ysm is not in this corpus root)\n");
        }
        for (String line : loggedBoneSpreads) {
            wrap.append(line).append('\n');
        }
        wrap.append("```\n");

        wrap.append("\n## the shape of the skirt model's physics bones, slice by slice (pivot-relative)\n\n")
                .append("Each table slices the bone's own geometry in 0.05-block steps of dy = y - pivot.y, ")
                .append("from the top down, and prints the slots in the slice with their horizontal offsets ")
                .append("from the pivot. A piece that hangs from its pivot has its geometry on one side of ")
                .append("a plane through the pivot; a band worn around the body has it on all sides.\n")
                .append(shapeDumps);

        wrap.append("## the candidate discriminators, measured\n\n")
                .append("Each row is one metric at one threshold: how many of the ").append(evaluatedBones)
                .append(" bone(s) it would drop, in how many models, and - the number that matters - how many ")
                .append("of the ").append(selectedBones)
                .append(" bone(s) the production classifier would actually simulate on this corpus it would ")
                .append("remove, and in how many models that changes the segment list.\n\n")
                .append("`spreadBelow` and `eigenRatioBelow` fire below the threshold; every other metric ")
                .append("fires at or above it.\n\n");
        for (String metric : WRAP_METRICS) {
            List<Double> values = new ArrayList<>();
            for (double[] value : metricDistributions.get(metric)) {
                values.add(value[0]);
            }
            java.util.Collections.sort(values);
            wrap.append(String.format(Locale.ROOT,
                    "**%s**: min %.3f, median %.3f, max %.3f%n%n", metric,
                    values.isEmpty() ? 0 : values.get(0),
                    values.isEmpty() ? 0 : values.get(values.size() / 2),
                    values.isEmpty() ? 0 : values.get(values.size() - 1)));
            wrap.append("| threshold | bones dropped | % | models affected | classifier segments dropped | of ")
                    .append(selectedBones).append(" | models whose segments change |\n|---|---|---|---|---|---|---|\n");
            for (float threshold : WRAP_THRESHOLDS.get(metric)) {
                String key = metric + " @ " + threshold;
                wrap.append(String.format(Locale.ROOT, "| %.2f | %d | %.3f%% | %d | %d | %.3f%% | %d |%n",
                        threshold, metricFires.get(key)[0],
                        100.0 * metricFires.get(key)[0] / Math.max(1, evaluatedBones),
                        metricModels.get(key).size(),
                        metricSelectedFires.get(key)[0],
                        100.0 * metricSelectedFires.get(key)[0] / Math.max(1, selectedBones),
                        metricSelectedModels.get(key).size()));
            }
            wrap.append('\n');
        }
        wrap.append("## who each candidate would drop (capped at 300 rows)\n\n");
        for (String metric : WRAP_METRICS) {
            for (float threshold : WRAP_THRESHOLDS.get(metric)) {
                String key = metric + " @ " + threshold;
                List<String> rows = metricRows.get(key);
                if (rows.isEmpty()) {
                    continue;
                }
                wrap.append("\n### ").append(key).append(" (").append(rows.size()).append(" row(s))\n\n```\n");
                for (String line : rows) {
                    wrap.append(line).append('\n');
                }
                wrap.append("```\n");
            }
        }
        write("ysm-physics-wrap-corpus.md", wrap.toString());

        neck.append("# The chest-top ornament gate over the corpus (round 19)\n\n")
                .append("Candidate rules for `YsmBindArmature.neckPivot`: the Head pivot keeps the height of the ")
                .append("Chest joint's settled geometry's top ring, and takes its horizontal position from the ")
                .append("ring mean (the shipped rule) unless the ring holds no more than `fraction` of the joint's ")
                .append("vertices and sits at least `offset` blocks from the replacement axis. `oracle` is the ")
                .append("model's own authored AllHead/MHead/Head pivot (horizontal offsets only, and only when ")
                .append("the model has one).\n\n");
        neck.append(String.format(Locale.ROOT,
                "- models with Chest geometry=%d, of which %d have an authored head control; the shipped rule's "
                        + "mean horizontal distance to that control is %.3f blocks%n%n",
                neckModels, neckWithAuthor, neckWithAuthor == 0 ? 0 : sumCurrentOracle / neckWithAuthor));
        neck.append("| fraction | offset | replacement | models fired | mean move | worst move | worst mover | oracle improved | worsened | mean oracle dist after |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, int[]> entry : neckFired.entrySet()) {
            String[] key = entry.getKey().split("\\|");
            double[] moveStats = neckMove.get(entry.getKey());
            double[] oracle = neckOracle.get(entry.getKey());
            int fired = entry.getValue()[0];
            neck.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %.3f | %.3f | %s | %d | %d | %.3f |%n",
                    key[0], key[1], key[2], fired,
                    fired == 0 ? 0 : moveStats[0] / fired, moveStats[1],
                    neckWorst.getOrDefault(entry.getKey(), "-"),
                    (int) oracle[2], (int) oracle[3],
                    fired == 0 ? 0 : oracle[0] / fired));
        }
        neck.append("\n## who fires, and where the pivot lands\n\n");
        for (Map.Entry<String, List<String>> entry : neckRows.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            String[] key = entry.getKey().split("\\|");
            neck.append("\n### fraction ").append(key[0]).append(", offset ").append(key[1])
                    .append(", replacement ").append(key[2]).append(" (")
                    .append(entry.getValue().size()).append(" row(s) shown)\n\n```\n");
            for (String line : entry.getValue()) {
                neck.append(line).append('\n');
            }
            neck.append("```\n");
        }
        write("ysm-neck-ornament-corpus.md", neck.toString());
        System.out.println(wrap.substring(0, Math.min(4000, wrap.length())));
        System.out.println(neck.substring(0, Math.min(4000, neck.length())));
    }

    /**
     * The vertical twin of the wrap rule, over the corpus: {@link YsmPhysicsParts#risesFromPivot}.
     *
     * <p>The rule under test is "a piece whose own geometry sits above its pivot cannot be hanging
     * from that pivot, so it is not simulated". What has to be established here is the
     * <b>threshold</b>, and the only honest way to pick one is to look at the distribution: if the
     * up-component of the unit rest direction is near -1 for hanging pieces and near +1 for pieces
     * whose geometry is a whole limb above their pivot, then a threshold anywhere in the empty band
     * between the two clusters changes nothing about which bones are dropped, while a threshold on
     * the edge of a cluster is a fitted number. This writes the histogram, the per-threshold fire
     * counts (over every candidate bone and over the bones the production classifier would actually
     * simulate), the names of the bones the shipped threshold drops, and the overlap with the round-19
     * wrap rule, which already drops pieces of the same class.
     *
     * <p>Guards, because a metric that cannot fail is worse than none: pieces pointing <i>down</i>
     * must never fire at a positive threshold, the count of pieces that fire when the geometry wraps
     * its pivot as well must be reported separately from the net-new drops, and the pieces that must
     * keep swinging on the known-good model (its skirt panels, hair and tail) are named in the
     * instance probe's report rather than here - the corpus holds the packages, the instance holds the
     * converted artefacts.
     */
    @Test
    void thePieceAboveItsPivotRuleOverTheCorpus() throws Exception {
        String root = System.getenv("YSMEF_YSM_CORPUS_ROOT");
        assumeTrue(root != null && !root.isEmpty(), "set YSMEF_YSM_CORPUS_ROOT to sweep the model corpus");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));

        float[] thresholds = {0.0F, 0.02F, 0.05F, 0.10F, 0.20F, 0.35F, 0.50F, 0.75F};
        long[] fired = new long[thresholds.length];
        long[] firedSelected = new long[thresholds.length];
        long[] firedNetNew = new long[thresholds.length];
        // Of the fires, how many are pieces whose pivot is still ON the piece (inside its own bounding
        // box). A piece hinged at a point on itself can swing however it is oriented; a piece whose
        // pivot is off it is the defect class this rule is for. The split is what tells a narrow fix
        // from a broad one, so it is counted per threshold rather than argued about.
        long[] firedPivotOnPiece = new long[thresholds.length];
        long[] firedSelectedPivotOffPiece = new long[thresholds.length];
        // The shipped rule is the pair (direction AND pivot not on the piece). Its own counters, and
        // the model sets, are what the blast-radius claim is about - measured rather than inferred
        // from the two halves.
        long[] shippedFired = new long[thresholds.length];
        long[] shippedSelected = new long[thresholds.length];
        Map<Integer, java.util.Set<String>> shippedModels = new LinkedHashMap<>();
        Map<Integer, java.util.Set<String>> shippedSelectedModels = new LinkedHashMap<>();
        // How far outside its own geometry the shipped rule's pivots are, in bands, so a reader can see
        // whether the containment test is doing real work or firing on pivots a hair off the surface.
        long[] shippedGapBands = new long[5];
        long[] shippedSelectedGapBands = new long[5];
        long[] shippedNetNew = new long[1];
        long[] shippedAlreadyWrapped = new long[1];
        List<String> shippedRows = new ArrayList<>();
        for (int i = 0; i < thresholds.length; i++) {
            shippedModels.put(i, new java.util.LinkedHashSet<>());
            shippedSelectedModels.put(i, new java.util.LinkedHashSet<>());
        }
        Map<Integer, java.util.Set<String>> firedModels = new LinkedHashMap<>();
        Map<Integer, java.util.Set<String>> firedSelectedModels = new LinkedHashMap<>();
        for (int i = 0; i < thresholds.length; i++) {
            firedModels.put(i, new java.util.LinkedHashSet<>());
            firedSelectedModels.put(i, new java.util.LinkedHashSet<>());
        }
        // Histogram of the up-component of the unit rest direction, in bands of ten per cent.
        long[] downBands = new long[11];
        long[] upBands = new long[11];
        long candidates = 0;
        long belowHorizontal = 0;
        long selectedBones = 0;
        long wrapsAndRises = 0;
        long wrapsTotal = 0;
        Map<String, long[]> firesByCategory = new LinkedHashMap<>();
        List<String> firedRows = new ArrayList<>();
        List<String> gapRows = new ArrayList<>();
        int parsed = 0;
        int skipped = 0;

        for (Path file : files) {
            String modelName = file.getFileName().toString();
            YSMGeoModel model;
            float scaleW;
            float scaleH;
            try {
                YsmBinaryReader.BinaryModel binary = YsmBinaryReader.read(
                        YsmFileCrypto.decryptYsmFile(Files.readAllBytes(file)));
                model = YSMGeoModel.fromBinary(binary);
                scaleW = binary.widthScale;
                scaleH = binary.heightScale;
            } catch (Throwable t) {
                skipped++;
                continue;
            }
            parsed++;
            if (parsed % 100 == 0) {
                System.out.println("[hang rule sweep] parsed=" + parsed + " skipped=" + skipped
                        + " candidates=" + candidates + " fired=" + fired[3]);
            }

            List<YSMGeoModel.Bone> bones = new ArrayList<>(model.bonesByName.values());
            Map<String, Matrix4f> worlds = new HashMap<>();
            Map<String, List<Vector3f>> baked = new LinkedHashMap<>();
            for (YSMGeoModel.Bone bone : bones) {
                if (bone.quads.isEmpty()) {
                    continue;
                }
                List<Vector3f> vertices = new ArrayList<>(bone.quads.size() * 4);
                Matrix4f world = worldOf(bone, worlds, 0);
                for (YSMGeoModel.Quad quad : bone.quads) {
                    for (Vector3f corner : quad.positions) {
                        if (corner == null) {
                            continue;
                        }
                        Vector3f p = new Vector3f(corner).mulPosition(world);
                        vertices.add(new Vector3f(p.x * scaleW, p.y * scaleH, p.z * scaleW));
                    }
                }
                baked.put(bone.name, vertices);
            }

            Map<String, Integer> boneIndexByName = new HashMap<>();
            for (int i = 0; i < bones.size(); i++) {
                boneIndexByName.put(bones.get(i).name, i);
            }
            YSMRuntimeModel.BoneRt[] bonesRt = new YSMRuntimeModel.BoneRt[bones.size()];
            Map<Integer, float[]> geometryByBone = new HashMap<>();
            Map<Integer, int[]> partsByBone = new HashMap<>();
            for (int i = 0; i < bones.size(); i++) {
                YSMGeoModel.Bone bone = bones.get(i);
                YSMRuntimeModel.BoneRt rt = new YSMRuntimeModel.BoneRt();
                rt.name = bone.name;
                rt.parent = bone.parent == null ? -1 : boneIndexByName.getOrDefault(bone.parent.name, -1);
                rt.joint = YSMJointMapper.resolveJointId(bone, model);
                rt.mapped = YSMJointMapper.isDirectlyMapped(bone);
                bonesRt[i] = rt;
                List<Vector3f> vertices = baked.get(bone.name);
                if (vertices != null && !vertices.isEmpty()) {
                    Vector3f centre = centroidOf(vertices);
                    geometryByBone.put(i, new float[]{centre.x, centre.y, centre.z, vertices.size()});
                    partsByBone.put(i, new int[]{0});
                }
            }
            java.util.function.IntPredicate ownsGeometry =
                    index -> YsmPhysicsParts.ownsItsGeometry(index, geometryByBone, partsByBone);
            java.util.Set<Integer> classified = new java.util.HashSet<>(YsmPhysicsParts.selectBones(
                    bonesRt, ownsGeometry, YsmPhysicsTuning.maxChains(), new int[1]));
            selectedBones += classified.size();

            Set<String> baseForms = BoneAlternateForms.baseFormsPresent(
                    model.bonesByName.keySet().toArray(new String[0]));
            for (int boneIdx = 0; boneIdx < bones.size(); boneIdx++) {
                YSMGeoModel.Bone bone = bones.get(boneIdx);
                List<Vector3f> vertices = baked.get(bone.name);
                if (vertices == null || vertices.size() < 4) {
                    continue;
                }
                if (YSMJointMapper.resolveJointId(bone, model) < 0 || YSMJointMapper.isDirectlyMapped(bone)
                        || YsmBindArmature.tierOf(bone.name, baseForms, Set.of()) != 0) {
                    continue;
                }
                Vector3f pivot = pivotOf(bone, worlds, scaleW, scaleH);
                Vector3f centroid = centroidOf(vertices);
                if (pivot == null || centroid == null) {
                    continue;
                }
                Vector3f rest = new Vector3f(centroid).sub(pivot);
                float lever = rest.length();
                if (!Float.isFinite(lever) || lever < 0.01F) {
                    continue;
                }
                float upShare = rest.y / lever;
                candidates++;
                boolean selected = classified.contains(boneIdx);
                boolean wraps = YsmPhysicsParts.wrapsPivot(vertices, pivot);
                if (wraps) {
                    wrapsTotal++;
                }
                if (upShare <= 0.0F) {
                    belowHorizontal++;
                }
                int band = (int) Math.min(10.0F, Math.floor(Math.abs(upShare) * 10.0F));
                if (upShare < 0.0F) {
                    downBands[band]++;
                } else {
                    upBands[band]++;
                }
                if (upShare > -0.05F && upShare < 0.30F && gapRows.size() < 400) {
                    gapRows.add(String.format(Locale.ROOT,
                            "%s  %s  upShare %+.4f  lever %.3f  joint %d  slots %d  category %s  wraps %s  pivotOn %s%s",
                            modelName, bone.name, upShare, lever, YSMJointMapper.resolveJointId(bone, model),
                            vertices.size(), YsmPhysicsParts.categoryOf(bone.name), wraps,
                            pivotInsideOwnBox(vertices, pivot),
                            selected ? "  [classifier segment]" : ""));
                }
                for (int t = 0; t < thresholds.length; t++) {
                    if (!YsmPhysicsParts.risesFromPivot(rest, lever, thresholds[t])) {
                        continue;
                    }
                    fired[t]++;
                    firedModels.get(t).add(modelName);
                    if (pivotInsideOwnBox(vertices, pivot)) {
                        firedPivotOnPiece[t]++;
                    }
                    if (selected) {
                        firedSelected[t]++;
                        firedSelectedModels.get(t).add(modelName);
                        if (!pivotInsideOwnBox(vertices, pivot)) {
                            firedSelectedPivotOffPiece[t]++;
                        }
                    }
                    if (!wraps) {
                        firedNetNew[t]++;
                    }
                }
                // The rule as it ships, once per bone: the direction margin production uses, AND the
                // pivot not on the piece. Its gap is banded because the containment test is a boolean
                // at zero - if most of its fires sat a hair outside the surface, the "pivot is off the
                // piece" reading would be an artefact of tight boxes rather than a defect.
                if (YsmPhysicsParts.risesOffPivot(vertices, pivot, rest, lever)) {
                    shippedFired[0]++;
                    shippedModels.get(0).add(modelName);
                    if (wraps) {
                        shippedAlreadyWrapped[0]++;
                    } else {
                        shippedNetNew[0]++;
                    }
                    if (selected) {
                        shippedSelected[0]++;
                        shippedSelectedModels.get(0).add(modelName);
                    }
                    double gap = YsmPhysicsParts.pivotGapFromGeometry(vertices, pivot);
                    int gapBand = gap < 0.005D ? 0 : gap < 0.02D ? 1 : gap < 0.05D ? 2 : gap < 0.2D ? 3 : 4;
                    shippedGapBands[gapBand]++;
                    if (selected) {
                        shippedSelectedGapBands[gapBand]++;
                    }
                    if (shippedRows.size() < 300) {
                        shippedRows.add(String.format(Locale.ROOT,
                                "%s  %s  upShare %+.4f  lever %.3f  pivotGap %.3f  joint %d  slots %d  category %s%s",
                                modelName, bone.name, upShare, lever, gap,
                                YSMJointMapper.resolveJointId(bone, model), vertices.size(),
                                YsmPhysicsParts.categoryOf(bone.name),
                                selected ? "  [classifier segment]" : ""));
                    }
                }
                // The rows are for the shipped margin, whatever it is: read live from production so
                // this report cannot describe a threshold the mod does not use.
                if (YsmPhysicsParts.risesFromPivot(rest, lever)) {
                    long[] categoryFires = firesByCategory.computeIfAbsent(
                            YsmPhysicsParts.categoryOf(bone.name).name(), key -> new long[2]);
                    categoryFires[0]++;
                    if (selected) {
                        categoryFires[1]++;
                    }
                    if (firedRows.size() < 400) {
                        firedRows.add(String.format(Locale.ROOT,
                                "%s  %s  upShare %+.4f  lever %.3f  joint %d  slots %d  category %s  wraps %s  pivotOn %s%s",
                                modelName, bone.name, upShare, lever,
                                YSMJointMapper.resolveJointId(bone, model), vertices.size(),
                                YsmPhysicsParts.categoryOf(bone.name), wraps,
                                pivotInsideOwnBox(vertices, pivot),
                                selected ? "  [classifier segment]" : ""));
                    }
                }
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("# The piece whose own geometry sits above its pivot, over the corpus\n\n")
                .append("The rule is `YsmPhysicsParts#risesOffPivot`: a segment's own `rest` ")
                .append("(`centroid - pivot`, measured from that piece's own geometry) must not point ")
                .append("upward by more than the shipped margin, **and** its pivot must not be on that ")
                .append("geometry. The tables below measure each half separately, because the first half ")
                .append("alone is what the round started with and the second is what keeps it off models and ")
                .append("pieces that are already right; see `## The rule as it ships` at the end for the pair. ")
                .append("Everything below is measured in mesh bind space, from the packages themselves, over ")
                .append("every bone that could ever be a segment: own geometry (>= 4 slots), a joint, tier 0, ")
                .append("not directly mapped.\n\n")
                .append(String.format(Locale.ROOT,
                        "parsed=%d skipped=%d candidate bones=%d, of which classifier segments=%d; "
                                + "%d candidates point down or level (upShare <= 0) and %d already wrap their "
                                + "pivot (round-19 rule)%n%n",
                        parsed, skipped, candidates, selectedBones, belowHorizontal, wrapsTotal));

        report.append("## The distribution: how much of the lever points up\n\n")
                .append("| band of |upShare| | pointing down | pointing up |\n|---|---|---|\n");
        for (int i = 0; i < 11; i++) {
            String label = i == 10 ? "0.9 .. 1.0" : String.format(Locale.ROOT, "%.1f .. %.1f", i / 10.0, (i + 1) / 10.0);
            report.append(String.format(Locale.ROOT, "| %s | %d | %d |%n", label, downBands[i], upBands[i]));
        }

        report.append("\n## What each margin would drop\n\n")
                .append("`pivot on piece` counts the fires whose pivot is still inside the piece's own bounding box ")
                .append("(a hinge); `pivot off piece` counts those whose pivot is outside it (the reported defect's class). ")
                .append("The last column counts, among the classifier's own segments, the ones dropped whose pivot is off the piece.\n\n")
                .append("| margin (share of lever) | ~angle above horizontal | bones fired | models | of the classifier's segments | in models | net-new (not already dropped by the wrap rule) | pivot on piece | pivot off piece | of the segments: pivot off piece |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (int t = 0; t < thresholds.length; t++) {
            report.append(String.format(Locale.ROOT, "| %.2f | %.1f deg | %d | %d | %d | %d | %d | %d | %d | %d |%n",
                    thresholds[t], Math.toDegrees(Math.asin(Math.min(1.0, thresholds[t]))),
                    fired[t], firedModels.get(t).size(), firedSelected[t],
                    firedSelectedModels.get(t).size(), firedNetNew[t],
                    firedPivotOnPiece[t], fired[t] - firedPivotOnPiece[t],
                    firedSelectedPivotOffPiece[t]));
        }
        report.append(String.format(Locale.ROOT,
                "%nthe shipped margin is %.2f (%.1f deg above horizontal)%n",
                YsmPhysicsParts.risesFromPivotMargin(),
                Math.toDegrees(Math.asin(Math.min(1.0, YsmPhysicsParts.risesFromPivotMargin())))));

        report.append("\n## The rule as it ships: the direction test AND the pivot not on the piece\n\n")
                .append("`YsmPhysicsParts#risesOffPivot` = `risesFromPivot` (the direction, at the margin above) ")
                .append("and not `pivotOnGeometry` (the pivot is outside the bounding box of the piece's own ")
                .append("geometry). The second condition is a containment test with no threshold to place; it is ")
                .append("what keeps a piece that merely stands up from a pivot that is still on it.\n\n")
                .append(String.format(Locale.ROOT,
                        "- all candidate bones: **%d** dropped, in %d model(s)%n"
                                + "- the production classifier's own segments: **%d of %d** dropped (%.1f per cent), in **%d** of the %d parseable models%n"
                                + "- net of the round-19 wrap rule: %d of the %d are new drops (the two rules overlap on %d bone(s))%n%n",
                        shippedFired[0], shippedModels.get(0).size(),
                        shippedSelected[0], selectedBones,
                        selectedBones == 0 ? 0.0 : 100.0 * shippedSelected[0] / selectedBones,
                        shippedSelectedModels.get(0).size(), parsed,
                        shippedNetNew[0], shippedFired[0], shippedAlreadyWrapped[0]));
        report.append("How far outside its own geometry each dropped pivot sits (the containment condition's ")
                .append("own distribution - the reason it is a boolean and not a tuned distance). The second ")
                .append("column is the population that matters: the bones the production classifier would ")
                .append("actually simulate.\n\n")
                .append("| pivot gap | bones | of the classifier's segments |\n|---|---|---|\n");
        String[] gapLabels = {"under 0.005 blocks", "0.005 .. 0.02", "0.02 .. 0.05", "0.05 .. 0.2", "0.2 blocks and more"};
        for (int i = 0; i < gapLabels.length; i++) {
            report.append(String.format(Locale.ROOT, "| %s | %d | %d |%n",
                    gapLabels[i], shippedGapBands[i], shippedSelectedGapBands[i]));
        }
        report.append("\n### every bone the shipped rule drops (up to 300)\n\n```\n");
        for (String line : shippedRows) {
            report.append(line).append('\n');
        }
        report.append("```\n");

        report.append("\n## The shipped margin: what it drops, by family\n\n")
                .append("| family | bones fired | of them classifier segments |\n|---|---|---|\n");
        for (Map.Entry<String, long[]> entry : firesByCategory.entrySet()) {
            report.append(String.format(Locale.ROOT, "| %s | %d | %d |%n",
                    entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        report.append("\n### every bone the shipped margin drops (up to 400)\n\n```\n");
        for (String line : firedRows) {
            report.append(line).append('\n');
        }
        report.append("```\n\n## every candidate whose up-component is between -0.05 and +0.30 (up to 400)\n\n")
                .append("This is the band the threshold has to be chosen inside. A band with rows in it is ")
                .append("a band where the threshold decides something; an empty one is where it does not.\n\n```\n");
        for (String line : gapRows) {
            report.append(line).append('\n');
        }
        report.append("```\n");
        write("ysm-hang-rule-corpus.md", report.toString());
        System.out.println(report.substring(0, Math.min(6000, report.length())));

        // The invariant a threshold cannot break, asserted rather than described: a piece whose
        // geometry points down is never dropped by this rule. If the predicate's sign were flipped,
        // this is what fails - and the live margin is read from production, so a change there cannot
        // leave this report describing a threshold the mod does not use.
        assertFalse(YsmPhysicsParts.risesFromPivot(new Vector3f(0.0F, -1.0F, 0.0F), 1.0F),
                "a piece pointing straight down must not rise from its pivot");
        assertTrue(YsmPhysicsParts.risesFromPivot(new Vector3f(0.0F, 1.0F, 0.0F), 1.0F),
                "a piece whose geometry is straight above its pivot must rise from it");
        assertTrue(belowHorizontal > 0, "the corpus must contain pieces that point down");
    }

    /**
     * Whether a pivot is still on the piece it belongs to: inside the piece's own bounding box.
     *
     * <p>A boolean and not a distance, deliberately. The distance from the pivot to the geometry was
     * measured in an earlier round and is knife-edge between the two models this rule is calibrated
     * against (0.048 blocks on the reported model's shin piece against 0.037 on the worst piece of the
     * known-good one), so it cannot carry a threshold. Containment can: a hinge is a point on the
     * piece, and a pivot that is not even inside the box the piece occupies is not a hinge whatever
     * the margin.
     */
    private static boolean pivotInsideOwnBox(List<Vector3f> vertices, Vector3f pivot) {
        if (vertices == null || vertices.isEmpty() || pivot == null) {
            return false;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        int used = 0;
        for (Vector3f v : vertices) {
            if (v == null) {
                continue;
            }
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
            used++;
        }
        if (used == 0) {
            return false;
        }
        float slack = 1.0E-4F;
        return pivot.x >= minX - slack && pivot.x <= maxX + slack
                && pivot.y >= minY - slack && pivot.y <= maxY + slack
                && pivot.z >= minZ - slack && pivot.z <= maxZ + slack;
    }

    private static void write(String name, String content) throws java.io.IOException {
        Path out = Paths.get("build", "reports", name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, content, StandardCharsets.UTF_8);
    }

    /**
     * Every candidate measurement of one bone's own geometry about its pivot - all in mesh bind
     * space, all built from the same vertex list the production rule would see:
     *
     * <ul>
     *   <li>{@code spread} - largest over smallest eigenvalue of the unit-direction covariance
     *       ({@link YsmPhysicsParts#directionSpread}): 1 means the geometry surrounds the pivot.</li>
     *   <li>{@code octants} - how many of the eight octants around the pivot hold a non-trivial share
     *       of the vertices (at least 1 per cent and at least 2 of them): a piece hanging from its
     *       pivot fills one or two, a shell around it fills most.</li>
     *   <li>{@code interior} - the pivot's smallest distance to the six faces of the geometry's own
     *       bounding box, as a share of that box's extent along the same axis: 0 means the pivot
     *       touches the box (it is on the piece's surface), 0.15 means it is 15 per cent of the box
     *       inside every face.</li>
     *   <li>{@code behindReach} - how far the geometry reaches <b>against</b> the lever, in levers:
     *       {@code max(0, -min((v - pivot) . restHat)) / |rest|}. This is the reference
     *       implementation's own {@code wrapsPivot} measure, normalised by the lever instead of an
     *       absolute 0.12 blocks.</li>
     *   <li>{@code behindFraction} - the share of vertices behind the plane through the pivot
     *       perpendicular to the lever.</li>
     *   <li>{@code aboveLever} - how far the geometry rises above the pivot in the model's own frame,
     *       in levers. Reported for information: it is orientation-dependent, so a lock of hair that
     *       points up scores as high as a band, which is why it cannot be the rule on its own.</li>
     * </ul>
     */
    private static WrapStats wrapStats(List<Vector3f> vertices, Vector3f pivot, Vector3f centroid,
                                       float lever, float spread) {
        Vector3f rest = new Vector3f(centroid).sub(pivot);
        float restLength = rest.length();
        float ux = restLength < 1.0E-9F ? 0.0F : rest.x / restLength;
        float uy = restLength < 1.0E-9F ? 0.0F : rest.y / restLength;
        float uz = restLength < 1.0E-9F ? 0.0F : rest.z / restLength;
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        int[] octantCounts = new int[8];
        int behind = 0;
        int used = 0;
        float minProjection = 0.0F;
        for (Vector3f v : vertices) {
            if (v == null) {
                continue;
            }
            float dx = v.x - pivot.x;
            float dy = v.y - pivot.y;
            float dz = v.z - pivot.z;
            float projection = dx * ux + dy * uy + dz * uz;
            minProjection = Math.min(minProjection, projection);
            if (projection < 0.0F) {
                behind++;
            }
            octantCounts[(dx >= 0.0F ? 1 : 0) | (dy >= 0.0F ? 2 : 0) | (dz >= 0.0F ? 4 : 0)]++;
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
            used++;
        }
        int minOctantVertices = Math.max(2, used / 100);
        int octants = 0;
        for (int count : octantCounts) {
            if (count >= minOctantVertices) {
                octants++;
            }
        }
        float interior = Math.min(
                Math.min(margin(pivot.x - minX, maxX - minX), margin(maxX - pivot.x, maxX - minX)),
                Math.min(
                        Math.min(margin(pivot.y - minY, maxY - minY), margin(maxY - pivot.y, maxY - minY)),
                        Math.min(margin(pivot.z - minZ, maxZ - minZ), margin(maxZ - pivot.z, maxZ - minZ))));

        // The pivot's place inside the material that is level with it: the mean horizontal offset of
        // the vertices within a band of the pivot's own height, over their mean horizontal distance
        // from the pivot. Zero when the pivot is the centre of what surrounds it; approaching one when
        // it sits on the rim of a cross-section that reaches away from it.
        double localX = 0;
        double localZ = 0;
        double localRadius = 0;
        int localSlots = 0;
        double wholeRadius = 0;
        float band = Math.max(0.04F, 0.25F * lever);
        for (Vector3f v : vertices) {
            if (v == null) {
                continue;
            }
            float dx = v.x - pivot.x;
            float dz = v.z - pivot.z;
            wholeRadius += Math.hypot(dx, dz);
            if (Math.abs(v.y - pivot.y) <= band) {
                localX += dx;
                localZ += dz;
                localRadius += Math.hypot(dx, dz);
                localSlots++;
            }
        }
        float localCentre = 0.0F;
        float localBackReach = 0.0F;
        if (localSlots >= 4 && localRadius > 1.0E-6D) {
            double meanX = localX / localSlots;
            double meanZ = localZ / localSlots;
            double meanRadius = localRadius / localSlots;
            localCentre = (float) (Math.hypot(meanX, meanZ) / meanRadius);
            double ux2 = meanX / Math.max(1.0E-9D, Math.hypot(meanX, meanZ));
            double uz2 = meanZ / Math.max(1.0E-9D, Math.hypot(meanX, meanZ));
            double furthestBack = 0;
            for (Vector3f v : vertices) {
                if (v == null || Math.abs(v.y - pivot.y) > band) {
                    continue;
                }
                furthestBack = Math.max(furthestBack, -((v.x - pivot.x) * ux2 + (v.z - pivot.z) * uz2));
            }
            localBackReach = (float) (furthestBack / meanRadius);
        }
        float globalOffset = wholeRadius < 1.0E-6D ? 0.0F
                : (float) (Math.hypot(centroid.x - pivot.x, centroid.z - pivot.z) / (wholeRadius / Math.max(1, used)));
        float[] eigenvalues = offsetEigenvalues(vertices, pivot);
        float eigenRatio = eigenvalues[2] < 1.0E-9F ? 1.0E6F : eigenvalues[0] / eigenvalues[2];

        // The piece reaches across the body's own left-right axis (the model's mirror plane) on both
        // sides, and the pivot sits well away from that axis: a band worn around the body, whose
        // rotation about a point on its rim slides it off. The bilateral half of the test is what
        // keeps a lock of hair hanging on one side of the head out of it.
        float halfWidth = (maxX - minX) * 0.5F;
        float xMargin = Math.max(0.02F, halfWidth * 0.15F);
        boolean bilateral = minX <= -xMargin && maxX >= xMargin;
        float bandOffset = bilateral && halfWidth > 1.0E-5F ? Math.abs(pivot.x) / halfWidth : 0.0F;

        // The stricter reading of the same statement: the piece is *centred* on the body's mirror
        // plane (its own x span is symmetric about it within 15 per cent), so it is worn around the
        // trunk rather than being a limb, a weapon or a decoration that merely crosses the axis.
        boolean symmetricAboutAxis = halfWidth > 1.0E-5F
                && Math.abs(minX + maxX) <= halfWidth * 0.30F;
        float bandOffsetStrict = symmetricAboutAxis ? Math.abs(pivot.x) / halfWidth : 0.0F;

        // ... and the piece's material also surrounds the pivot's own vertical line: the horizontal
        // directions of its vertices cover the whole circle, which is what a band does.
        int[] sectors = new int[8];
        for (Vector3f v : vertices) {
            if (v == null) {
                continue;
            }
            double angle = Math.atan2(v.z - pivot.z, v.x - pivot.x);
            sectors[(int) Math.floor((angle + Math.PI) / (Math.PI / 4.0D)) & 7]++;
        }
        int coveredSectors = 0;
        for (int count : sectors) {
            if (count >= Math.max(3, used / 20)) {
                coveredSectors++;
            }
        }
        float bandOffsetStrictRing = coveredSectors >= 6 ? bandOffsetStrict : 0.0F;

        // Both structural statements at once: the piece is a band round the body's axis (the two
        // halves above) AND its material surrounds the pivot rather than hanging off it - the
        // direction-spread gate the round proposed, used as a gate rather than as the rule.
        float bandSpread = spread < 2.6F ? bandOffsetStrict : 0.0F;
        float bandSpreadStrict = spread < 2.4F ? bandOffsetStrict : 0.0F;

        // The shipped rule itself, so the report's blast radius is the rule's and not the sweep's
        // reading of it.
        float shipped = YsmPhysicsParts.wrapsPivot(vertices, pivot) ? 1.0F : 0.0F;

        return new WrapStats(spread, octants, interior, Math.max(0.0F, -minProjection) / lever,
                used == 0 ? 0.0F : (float) behind / used, (maxY - pivot.y) / lever,
                localCentre, localBackReach, globalOffset, eigenRatio, bandOffset, bandOffsetStrict,
                bandOffsetStrictRing, bandSpread, bandSpreadStrict, shipped, vertices.size(), lever, rest);
    }

    /** The offset covariance's eigenvalues, largest first. */
    private static float[] offsetEigenvalues(List<Vector3f> vertices, Vector3f pivot) {
        double xx = 0;
        double xy = 0;
        double xz = 0;
        double yy = 0;
        double yz = 0;
        double zz = 0;
        int used = 0;
        for (Vector3f v : vertices) {
            if (v == null) {
                continue;
            }
            double dx = v.x - pivot.x;
            double dy = v.y - pivot.y;
            double dz = v.z - pivot.z;
            xx += dx * dx;
            xy += dx * dy;
            xz += dx * dz;
            yy += dy * dy;
            yz += dy * dz;
            zz += dz * dz;
            used++;
        }
        if (used == 0) {
            return new float[]{0, 0, 0};
        }
        double[][] matrix = {
                {xx / used, xy / used, xz / used},
                {xy / used, yy / used, yz / used},
                {xz / used, yz / used, zz / used}};
        double[] values = YsmPhysicsParts.jacobiEigenvalues(matrix);
        java.util.Arrays.sort(values);
        return new float[]{(float) values[2], (float) values[1], (float) values[0]};
    }

    /** A margin as a share of the extent it is measured across; a degenerate axis has no interior. */
    private static float margin(float distance, float extent) {
        return extent < 1.0E-5F ? 0.0F : distance / extent;
    }

    /**
     * The bone's own geometry, relative to its pivot, slice by slice: what each candidate metric is
     * trying to see, printed so it can be looked at instead of guessed.
     */
    private static String shapeDump(List<Vector3f> vertices, Vector3f pivot, Vector3f centroid) {
        StringBuilder sb = new StringBuilder();
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        int left = 0;
        int right = 0;
        int front = 0;
        int back = 0;
        double radius = 0;
        int n = 0;
        float centreX = 0;
        float centreZ = 0;
        for (Vector3f v : vertices) {
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
            if (v.x < pivot.x) {
                left++;
            } else {
                right++;
            }
            if (v.z < pivot.z) {
                back++;
            } else {
                front++;
            }
            radius += Math.hypot(v.x - pivot.x, v.z - pivot.z);
            centreX += v.x;
            centreZ += v.z;
            n++;
        }
        centreX /= Math.max(1, n);
        centreZ /= Math.max(1, n);
        sb.append(String.format(Locale.ROOT,
                "pivot %s, centroid %s, lever %.3f; extents dx %.3f..%.3f (%.3f), dy %.3f..%.3f (%.3f), "
                        + "dz %.3f..%.3f (%.3f)%n",
                fmt(pivot), fmt(centroid), centroid.distance(pivot),
                minX - pivot.x, maxX - pivot.x, maxX - minX,
                minY - pivot.y, maxY - pivot.y, maxY - minY,
                minZ - pivot.z, maxZ - pivot.z, maxZ - minZ));
        sb.append(String.format(Locale.ROOT,
                "geometry centre offset from pivot: dx %+.3f dz %+.3f; vertices with x<pivot.x: %d/%d, "
                        + "z<pivot.z: %d/%d; mean horizontal radius from pivot %.3f%n%n",
                centreX - pivot.x, centreZ - pivot.z, left, n, back, n, radius / Math.max(1, n)));
        sb.append("| dy slice | slots | mean dx | mean dz | min dx | max dx | min dz | max dz |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (float top = maxY; top > minY - 1.0E-4F; top -= 0.05F) {
            float low = top - 0.05F;
            int slots = 0;
            double sumX = 0;
            double sumZ = 0;
            float loX = Float.MAX_VALUE;
            float hiX = -Float.MAX_VALUE;
            float loZ = Float.MAX_VALUE;
            float hiZ = -Float.MAX_VALUE;
            for (Vector3f v : vertices) {
                if (v.y > top + 1.0E-4F || v.y < low) {
                    continue;
                }
                float dx = v.x - pivot.x;
                float dz = v.z - pivot.z;
                slots++;
                sumX += dx;
                sumZ += dz;
                loX = Math.min(loX, dx);
                hiX = Math.max(hiX, dx);
                loZ = Math.min(loZ, dz);
                hiZ = Math.max(hiZ, dz);
            }
            if (slots == 0) {
                continue;
            }
            sb.append(String.format(Locale.ROOT, "| %+.2f..%+.2f | %d | %+.3f | %+.3f | %+.3f | %+.3f | %+.3f | %+.3f |%n",
                    low, top, slots, sumX / slots, sumZ / slots, loX, hiX, loZ, hiZ));
        }
        return sb.toString();
    }

    /** One bone's candidate wrap measurements. */
    private static final class WrapStats {
        final float spread;
        final int octants;
        final float interior;
        final float behindReach;
        final float behindFraction;
        final float aboveLever;
        final float localCentre;
        final float localBackReach;
        final float globalOffset;
        final float eigenRatio;
        final float bandOffset;
        final float bandOffsetStrict;
        final float bandOffsetStrictRing;
        final float bandSpread;
        final float bandSpreadStrict;
        final float shipped;
        final int slots;
        final float lever;
        final Vector3f rest;

        WrapStats(float spread, int octants, float interior, float behindReach, float behindFraction,
                  float aboveLever, float localCentre, float localBackReach, float globalOffset,
                  float eigenRatio, float bandOffset, float bandOffsetStrict, float bandOffsetStrictRing,
                  float bandSpread, float bandSpreadStrict, float shipped, int slots, float lever,
                  Vector3f rest) {
            this.spread = spread;
            this.octants = octants;
            this.interior = interior;
            this.behindReach = behindReach;
            this.behindFraction = behindFraction;
            this.aboveLever = aboveLever;
            this.localCentre = localCentre;
            this.localBackReach = localBackReach;
            this.globalOffset = globalOffset;
            this.eigenRatio = eigenRatio;
            this.bandOffset = bandOffset;
            this.bandOffsetStrict = bandOffsetStrict;
            this.bandOffsetStrictRing = bandOffsetStrictRing;
            this.bandSpread = bandSpread;
            this.bandSpreadStrict = bandSpreadStrict;
            this.shipped = shipped;
            this.slots = slots;
            this.lever = lever;
            this.rest = rest;
        }

        float value(String metric) {
            switch (metric) {
                case "spreadBelow":
                    return spread;
                case "octants":
                    return octants;
                case "interior":
                    return interior;
                case "behindReach":
                    return behindReach;
                case "behindFraction":
                    return behindFraction;
                case "aboveLever":
                    return aboveLever;
                case "localCentre":
                    return localCentre;
                case "localBackReach":
                    return localBackReach;
                case "globalOffset":
                    return globalOffset;
                case "eigenRatioBelow":
                    return eigenRatio;
                case "bandOffset":
                    return bandOffset;
                case "bandOffsetStrict":
                    return bandOffsetStrict;
                case "bandOffsetStrictRing":
                    return bandOffsetStrictRing;
                case "bandSpread":
                    return bandSpread;
                case "bandSpreadStrict":
                    return bandSpreadStrict;
                default:
                    return shipped;
            }
        }

        static boolean fires(String metric, float value, float threshold) {
            return "spreadBelow".equals(metric) || "eigenRatioBelow".equals(metric)
                    ? value < threshold : value >= threshold;
        }

        String describe() {
            return String.format(Locale.ROOT,
                    "spread %7.3f  oct %d  interior %+.3f  backReach %.3f  backFrac %.3f  aboveLev %+.2f  "
                            + "localCentre %.3f  localBack %.3f  globalOff %.3f  eigRatio %5.2f  bandOffset %.3f  "
                            + "bandOffsetStrict %.3f  ring %.3f  slots %5d  lever %.3f",
                    spread, octants, interior, behindReach, behindFraction, aboveLever, localCentre,
                    localBackReach, globalOffset, eigenRatio, bandOffset, bandOffsetStrict,
                    bandOffsetStrictRing, slots, lever) + String.format(Locale.ROOT, "  bandSpread %.3f/%.3f", bandSpread, bandSpreadStrict);
        }
    }

    /** The bone's pivot in mesh space: the authored pivot through the same chain and scale as the mesh. */
    private static Vector3f pivotOf(YSMGeoModel.Bone bone, Map<String, Matrix4f> worlds, float scaleW, float scaleH) {
        Matrix4f world = worldOf(bone, worlds, 0);
        Vector3f pivot = new Vector3f(bone.pivotX, bone.pivotY, bone.pivotZ).mulPosition(world);
        pivot.mul(scaleW, scaleH, scaleW);
        return Float.isFinite(pivot.x) && Float.isFinite(pivot.y) && Float.isFinite(pivot.z) ? pivot : null;
    }

    /** The model's authored central head control, in mesh space - the oracle for the neck's position. */
    private static Vector3f authoredHeadPivot(List<YSMGeoModel.Bone> bones, float scaleW, float scaleH) {
        YSMGeoModel.Bone allHead = null;
        YSMGeoModel.Bone mHead = null;
        YSMGeoModel.Bone head = null;
        for (YSMGeoModel.Bone bone : bones) {
            String normalized = YSMJointMapper.normalize(bone.name);
            if (normalized.equals("allhead") && allHead == null) {
                allHead = bone;
            } else if (normalized.equals("mhead") && mHead == null) {
                mHead = bone;
            } else if (normalized.equals("head") && head == null && bone.parent != null
                    && YSMJointMapper.normalize(bone.parent.name).equals("allhead")) {
                head = bone;
            }
        }
        YSMGeoModel.Bone chosen = mHead != null ? mHead : (head != null ? head : allHead);
        if (chosen == null) {
            return null;
        }
        return new Vector3f(chosen.pivotX * scaleW, chosen.pivotY * scaleH, chosen.pivotZ * scaleW);
    }

    private static Matrix4f worldOf(YSMGeoModel.Bone bone, Map<String, Matrix4f> cache, int depth) {
        Matrix4f cached = cache.get(bone.name);
        if (cached != null) {
            return cached;
        }
        if (depth > YSMGeoModel.MAX_BONE_DEPTH) {
            return new Matrix4f();
        }
        Matrix4f local = new Matrix4f();
        local.translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        local.rotateZ(bone.rotZ);
        local.rotateY(bone.rotY);
        local.rotateX(bone.rotX);
        local.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);
        Matrix4f world = bone.parent == null
                ? local
                : new Matrix4f(worldOf(bone.parent, cache, depth + 1)).mul(local);
        cache.put(bone.name, world);
        return world;
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
        int n = 0;
        for (Vector3f v : vertices) {
            if (v.y >= maxY - 0.05F) {
                acc.add(v);
                n++;
            }
        }
        return n == 0 ? new Vector3f(vertices.get(0)) : acc.div(n);
    }

    private static int ringSlots(List<Vector3f> vertices, float topY) {
        int n = 0;
        for (Vector3f v : vertices) {
            if (v.y >= topY - 0.05F) {
                n++;
            }
        }
        return n;
    }

    private static Vector3f centroidOf(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        Vector3f acc = new Vector3f();
        for (Vector3f v : vertices) {
            acc.add(v);
        }
        return acc.div(vertices.size());
    }

    private static Vector3f midpoint(Vector3f a, Vector3f b) {
        if (a == null) {
            return b == null ? null : new Vector3f(b);
        }
        if (b == null) {
            return new Vector3f(a);
        }
        return new Vector3f((a.x + b.x) * 0.5F, (a.y + b.y) * 0.5F, (a.z + b.z) * 0.5F);
    }

    private static double horizontal(Vector3f a, Vector3f b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static String fmt(Vector3f v) {
        return v == null ? "-" : String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }
}
