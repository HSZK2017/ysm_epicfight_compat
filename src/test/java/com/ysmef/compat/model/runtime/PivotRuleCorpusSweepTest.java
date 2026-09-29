package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.BoneAlternateForms;
import com.ysmef.compat.model.JointTable;
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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A measurement tool, not an assertion: which pivot-selection rule the model corpus supports.
 *
 * <p>Three candidate rules are evaluated over every parseable corpus model and compared with an
 * independent structural invariant instead of with each other's numbers: <b>a fixed biped's joints are
 * mirror symmetric</b>. Whatever a model's proportions or naming, its right knee and its left knee must
 * sit at mirrored positions; a rule that leaves one knee 0.6 blocks below the other has measured one of
 * them on the wrong geometry, and the rule that removes such a pair is the better rule.
 *
 * <pre>
 *   R0  today:   the narrowest tier that carries any geometry (mapped bones override unmapped inside
 *                that tier)
 *   A   mapped first across tiers, hidden last
 *   B   the alternate-form tier merged into the strict tier (variants are no longer excluded from a
 *       joint's pivot), hidden last
 * </pre>
 *
 * <p>What it found, and why the shipped rule is the parent-relative one instead: A moves 34 joints and
 * hands the chest pivot to a {@code backpack} (0.6 blocks up) and the shoulder to a stub arm bone
 * (1.1 blocks down, 雪狐桑); the union of tiers 0..1 moves 674 joints and hands a hand pivot to a VFX
 * bone 5.6 blocks above a 2-block model (see the [union] report). The parent-relative criterion - the
 * candidate whose top is closer to the joint's parent - moves four joints in two models, all of them
 * the shape it exists for, and worsens no model's mirror symmetry.
 *
 * <p>The hidden-in-default-form set is taken as empty (the game derives it by running the model's
 * parallel animations, which this offline read does not do), so tier 3 is populated only by the
 * accessory/held-item names here. Stated because it is the one input the sweep cannot reproduce.
 */
class PivotRuleCorpusSweepTest {

    /** Joint pairs that must mirror each other on any biped rig. */
    private static final int[][] PAIRS = {
            {JointTable.THIGH_R, JointTable.THIGH_L},
            {JointTable.LEG_R, JointTable.LEG_L},
            {JointTable.KNEE_R, JointTable.KNEE_L},
            {JointTable.SHOULDER_R, JointTable.SHOULDER_L},
            {JointTable.ARM_R, JointTable.ARM_L},
            {JointTable.HAND_R, JointTable.HAND_L},
            {JointTable.ELBOW_R, JointTable.ELBOW_L},
            {JointTable.TOOL_R, JointTable.TOOL_L}};

    @Test
    void comparePivotRulesOverTheCorpus() throws Exception {
        String root = System.getenv("YSMEF_YSM_CORPUS_ROOT");
        assumeTrue(root != null && !root.isEmpty(), "set YSMEF_YSM_CORPUS_ROOT to sweep the model corpus");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));

        int parsed = 0;
        int failed = 0;
        int improvedA = 0;
        int worseA = 0;
        int improvedB = 0;
        int worseB = 0;
        int pairedModels = 0;
        double sumA0 = 0;
        double sumA1 = 0;
        double sumB0 = 0;
        double sumB1 = 0;
        List<String> worst = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        report.append("# pivot rule / mirror-symmetry comparison\n\n");
        report.append("model | R0 max pair error | A | B | which pairs moved, R0 -> A -> B\n|---|---|---|---|---\n");

        for (Path file : files) {
            YSMGeoModel model;
            float widthScale;
            float heightScale;
            try {
                YsmBinaryReader.BinaryModel binary = YsmBinaryReader.read(
                        YsmFileCrypto.decryptYsmFile(Files.readAllBytes(file)));
                model = YSMGeoModel.fromBinary(binary);
                widthScale = binary.widthScale;
                heightScale = binary.heightScale;
            } catch (Throwable t) {
                failed++;
                continue;
            }
            parsed++;
            Set<String> baseForms = BoneAlternateForms.baseFormsPresent(
                    model.bonesByName.keySet().toArray(new String[0]));
            Map<Integer, List<Vector3f>[][]> buckets = new HashMap<>();
            for (YSMGeoModel.Bone bone : model.bonesByName.values()) {
                if (bone.quads.isEmpty()) {
                    continue;
                }
                int joint = YSMJointMapper.resolveJointId(bone, model);
                if (joint < 0 || joint >= JointTable.COUNT) {
                    continue;
                }
                int tier = YsmBindArmature.tierOf(bone.name, baseForms, Set.of());
                boolean mapped = YSMJointMapper.isDirectlyMapped(bone);
                List<Vector3f>[][] byTier = buckets.computeIfAbsent(joint, k -> newBuckets());
                List<Vector3f> target = byTier[tier][mapped ? 0 : 1];
                if (target == null) {
                    target = new ArrayList<>();
                    byTier[tier][mapped ? 0 : 1] = target;
                }
                bakeInto(target, bone, widthScale, heightScale);
            }

            Map<Integer, Vector3f> r0 = pivots(buckets, Rule.R0);
            Map<Integer, Vector3f> ra = pivots(buckets, Rule.MAPPED_FIRST);
            Map<Integer, Vector3f> rb = pivots(buckets, Rule.MERGED_VARIANTS);
            double e0 = symmetryError(r0);
            double ea = symmetryError(ra);
            double eb = symmetryError(rb);
            if (Double.isFinite(e0) || Double.isFinite(ea) || Double.isFinite(eb)) {
                pairedModels++;
                sumA0 += err(e0);
                sumA1 += err(ea);
                sumB0 += err(e0);
                sumB1 += err(eb);
            }
            if (ea < e0 - 1e-4) {
                improvedA++;
            } else if (ea > e0 + 1e-4) {
                worseA++;
            }
            if (eb < e0 - 1e-4) {
                improvedB++;
            } else if (eb > e0 + 1e-4) {
                worseB++;
            }
            if (Math.abs(ea - e0) > 1e-4 || Math.abs(eb - e0) > 1e-4) {
                String line = file.getFileName() + " | " + fmt(e0) + " | " + fmt(ea) + " | " + fmt(eb) + " | "
                        + movedPairs(r0, ra, rb);
                if (worst.size() < 60) {
                    worst.add(line);
                }
                report.append(line).append('\n');
            }
        }

        String summary = String.format(Locale.ROOT,
                "corpus=%d parsed=%d undecryptable=%d | mirror-symmetry error (max joint-pair distance, blocks)\n"
                        + "  R0 sum=%.2f mean=%.4f | A sum=%.2f mean=%.4f (improved %d, worse %d) | "
                        + "B sum=%.2f mean=%.4f (improved %d, worse %d)",
                parsed + failed, parsed, failed,
                sumA0, sumA0 / Math.max(1, pairedModels), sumA1, sumA1 / Math.max(1, pairedModels),
                improvedA, worseA, sumB1, sumB1 / Math.max(1, pairedModels), improvedB, worseB);
        report.append('\n').append(summary).append('\n');
        Path out = Paths.get("build", "reports", "ysm-pivot-rule-compare.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(summary);
        System.out.println(String.join("\n", worst));
    }

    /**
     * What including the alternate-form tier in a joint's own pivot would do: every joint whose pivot
     * would move by more than 0.05 blocks, with the names of the bones that lifted it, and the
     * mirror-symmetry error of both rules.
     */
    @Test
    void unionVariantEffect() throws Exception {
        String root = System.getenv("YSMEF_YSM_CORPUS_ROOT");
        assumeTrue(root != null && !root.isEmpty(), "set YSMEF_YSM_CORPUS_ROOT to sweep the model corpus");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));

        int parsed = 0;
        int changedJointCount = 0;
        int changedModels = 0;
        List<String> lines = new ArrayList<>();
        for (Path file : files) {
            YSMGeoModel model;
            float sw;
            float sh;
            try {
                YsmBinaryReader.BinaryModel binary = YsmBinaryReader.read(
                        YsmFileCrypto.decryptYsmFile(Files.readAllBytes(file)));
                model = YSMGeoModel.fromBinary(binary);
                sw = binary.widthScale;
                sh = binary.heightScale;
            } catch (Throwable t) {
                continue;
            }
            parsed++;
            Set<String> baseForms = BoneAlternateForms.baseFormsPresent(
                    model.bonesByName.keySet().toArray(new String[0]));
            Map<Integer, List<Object[]>[][]> buckets = new HashMap<>();
            for (YSMGeoModel.Bone bone : model.bonesByName.values()) {
                if (bone.quads.isEmpty()) {
                    continue;
                }
                int joint = YSMJointMapper.resolveJointId(bone, model);
                if (joint < 0 || joint >= JointTable.COUNT || !isPivotReadJoint(joint)) {
                    continue;
                }
                int tier = YsmBindArmature.tierOf(bone.name, baseForms, Set.of());
                boolean mapped = YSMJointMapper.isDirectlyMapped(bone);
                List<Object[]>[][] byTier = buckets.computeIfAbsent(joint, k -> {
                    @SuppressWarnings("unchecked")
                    List<Object[]>[][] arrays = new List[4][2];
                    return arrays;
                });
                if (byTier[tier][mapped ? 0 : 1] == null) {
                    byTier[tier][mapped ? 0 : 1] = new ArrayList<>();
                }
                List<Vector3f> vertices = new ArrayList<>();
                bakeInto(vertices, bone, sw, sh);
                byTier[tier][mapped ? 0 : 1].add(new Object[]{bone.name, vertices});
            }
            boolean any = false;
            for (Map.Entry<Integer, List<Object[]>[][]> entry : buckets.entrySet()) {
                List<Object[]>[][] byTier = entry.getValue();
                float r0 = -1.0f;
                int r0Tier = -1;
                for (int tier = 0; tier < 4 && r0Tier < 0; tier++) {
                    if (byTier[tier][0] != null || byTier[tier][1] != null) {
                        r0 = tierTop(byTier[tier][0], byTier[tier][1]);
                        r0Tier = tier;
                    }
                }
                float union = -1.0f;
                List<Vector3f> unionVertices = new ArrayList<>();
                for (int tier = 0; tier <= 1; tier++) {
                    for (int m = 0; m < 2; m++) {
                        if (byTier[tier][m] != null) {
                            for (Object[] e : byTier[tier][m]) {
                                @SuppressWarnings("unchecked")
                                List<Vector3f> vertices = (List<Vector3f>) e[1];
                                unionVertices.addAll(vertices);
                            }
                        }
                    }
                }
                if (unionVertices.isEmpty()) {
                    union = r0;
                } else {
                    union = topOf(unionVertices).y;
                }
                if (r0Tier < 0 || Math.abs(union - r0) <= 0.05f) {
                    continue;
                }
                changedJointCount++;
                any = true;
                StringBuilder who = new StringBuilder();
                for (int tier = 0; tier <= 1; tier++) {
                    for (int m = 0; m < 2; m++) {
                        if (byTier[tier][m] == null) {
                            continue;
                        }
                        for (Object[] e : byTier[tier][m]) {
                            @SuppressWarnings("unchecked")
                            List<Vector3f> vertices = (List<Vector3f>) e[1];
                            float top = topOf(vertices).y;
                            if (top > r0 + 0.05f) {
                                who.append(e[0]).append("(t").append(tier).append(m == 0 ? ",mapped," : ",unmapped,")
                                        .append(f(top)).append(") ");
                            }
                        }
                    }
                }
                lines.add(String.format(Locale.ROOT, "%s %-10s R0=%.3f(t%d) union=%.3f  lifted by: %s",
                        file.getFileName(), JointTable.nameOf(entry.getKey()), r0, r0Tier, union, who));
            }
            if (any) {
                changedModels++;
            }
        }
        lines.sort(Comparator.comparingDouble((String s) -> {
            int i = s.indexOf("R0=");
            int j = s.indexOf("(t");
            int k = s.indexOf("union=");
            double r0 = Double.parseDouble(s.substring(i + 3, j));
            double u = Double.parseDouble(s.substring(k + 6, s.indexOf("  lifted", k)));
            return -Math.abs(u - r0);
        }));
        StringBuilder out = new StringBuilder();
        out.append("parsed=").append(parsed).append(" models with a joint moved >0.05=").append(changedModels)
                .append(" joints moved=").append(changedJointCount).append('\n');
        for (String line : lines) {
            out.append(line).append('\n');
        }
        Path report = Paths.get("build", "reports", "ysm-pivot-union-effect.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);
    }

    /**
     * A parent-relative rule: a joint's pivot may only be taken from its mapped (segment) geometry when
     * that geometry's top is <b>closer to the joint's parent pivot</b> than the geometry the current
     * rule picks - i.e. when the segment candidate actually reaches up towards the segment it hangs
     * from. Scoped to the joints whose parent pivot is already derived (LEG under THIGH, ARM under
     * CHEST, HAND under ARM), because that is what makes it decidable at all.
     */
    @Test
    void parentRelativeRule() throws Exception {
        String root = System.getenv("YSMEF_YSM_CORPUS_ROOT");
        assumeTrue(root != null && !root.isEmpty(), "set YSMEF_YSM_CORPUS_ROOT to sweep the model corpus");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));

        int parsed = 0;
        int changed = 0;
        int changedJoints = 0;
        int better = 0;
        int worse = 0;
        double sumR0 = 0;
        double sumG = 0;
        List<String> lines = new ArrayList<>();
        for (Path file : files) {
            YSMGeoModel model;
            float sw;
            float sh;
            try {
                YsmBinaryReader.BinaryModel binary = YsmBinaryReader.read(
                        YsmFileCrypto.decryptYsmFile(Files.readAllBytes(file)));
                model = YSMGeoModel.fromBinary(binary);
                sw = binary.widthScale;
                sh = binary.heightScale;
            } catch (Throwable t) {
                continue;
            }
            parsed++;
            Set<String> baseForms = BoneAlternateForms.baseFormsPresent(
                    model.bonesByName.keySet().toArray(new String[0]));
            Map<Integer, List<Object[]>[][]> buckets = new HashMap<>();
            for (YSMGeoModel.Bone bone : model.bonesByName.values()) {
                if (bone.quads.isEmpty()) {
                    continue;
                }
                int joint = YSMJointMapper.resolveJointId(bone, model);
                if (joint < 0 || joint >= JointTable.COUNT || !isPivotReadJoint(joint)) {
                    continue;
                }
                int tier = YsmBindArmature.tierOf(bone.name, baseForms, Set.of());
                boolean mapped = YSMJointMapper.isDirectlyMapped(bone);
                List<Object[]>[][] byTier = buckets.computeIfAbsent(joint, k -> {
                    @SuppressWarnings("unchecked")
                    List<Object[]>[][] arrays = new List[4][2];
                    return arrays;
                });
                if (byTier[tier][mapped ? 0 : 1] == null) {
                    byTier[tier][mapped ? 0 : 1] = new ArrayList<>();
                }
                List<Vector3f> vertices = new ArrayList<>();
                bakeInto(vertices, bone, sw, sh);
                byTier[tier][mapped ? 0 : 1].add(new Object[]{bone.name, vertices});
            }
            Map<Integer, Float> r0 = new HashMap<>();
            Map<Integer, Float> g = new HashMap<>();
            for (Map.Entry<Integer, List<Object[]>[][]> entry : buckets.entrySet()) {
                r0.put(entry.getKey(), r0Top(entry.getValue()));
            }
            // parent references, derived the way computePivots derives them
            float hip = midpoint(r0.get(JointTable.THIGH_R), r0.get(JointTable.THIGH_L));
            float neck = r0Top(buckets.get(JointTable.CHEST));
            float chest = midpoint(hip, neck);
            for (Map.Entry<Integer, List<Object[]>[][]> entry : buckets.entrySet()) {
                int joint = entry.getKey();
                float base = r0.get(joint);
                float parent;
                if (joint == JointTable.LEG_R) {
                    parent = r0.getOrDefault(JointTable.THIGH_R, hip);
                } else if (joint == JointTable.LEG_L) {
                    parent = r0.getOrDefault(JointTable.THIGH_L, hip);
                } else if (joint == JointTable.ARM_R) {
                    parent = chest;
                } else if (joint == JointTable.ARM_L) {
                    parent = chest;
                } else if (joint == JointTable.HAND_R) {
                    parent = g.getOrDefault(JointTable.ARM_R, chest);
                } else if (joint == JointTable.HAND_L) {
                    parent = g.getOrDefault(JointTable.ARM_L, chest);
                } else {
                    g.put(joint, base);
                    continue;
                }
                float mappedTop = -1.0f;
                String mappedNames = "";
                for (int tier = 0; tier < 4 && mappedTop < 0; tier++) {
                    if (entry.getValue()[tier][0] != null) {
                        mappedTop = top(entry.getValue()[tier][0]);
                        mappedNames = names(entry.getValue()[tier][0]);
                    }
                }
                float chosen = base;
                if (mappedTop > 0 && base > 0 && Math.abs(mappedTop - base) > 0.02f
                        && Math.abs(mappedTop - parent) < Math.abs(base - parent)) {
                    chosen = mappedTop;
                    changedJoints++;
                    lines.add(String.format(Locale.ROOT, "%s %-8s R0=%.3f -> %.3f (parent %.3f) mapped=%s",
                            file.getFileName(), JointTable.nameOf(joint), base, mappedTop, parent, mappedNames));
                }
                g.put(joint, chosen);
            }
            double e0 = symmetryErrorOfTops(r0);
            double eg = symmetryErrorOfTops(g);
            if (Math.abs(e0 - eg) > 1e-4) {
                changed++;
                if (eg < e0) {
                    better++;
                } else {
                    worse++;
                }
            }
            sumR0 += err(e0);
            sumG += err(eg);
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
                "parsed=%d joints changed=%d models with a symmetry change=%d (better %d, worse %d) "
                        + "symmetry sum R0=%.2f G=%.2f%n",
                parsed, changedJoints, changed, better, worse, sumR0, sumG));
        for (String line : lines) {
            out.append(line).append('\n');
        }
        Path report = Paths.get("build", "reports", "ysm-pivot-parent-relative.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);
    }

    private static float r0Top(List<Object[]>[][] byTier) {
        if (byTier == null) {
            return -1.0f;
        }
        for (int tier = 0; tier < 4; tier++) {
            if (byTier[tier][0] != null || byTier[tier][1] != null) {
                return tierTop(byTier[tier][0], byTier[tier][1]);
            }
        }
        return -1.0f;
    }

    private static double symmetryErrorOfTops(Map<Integer, Float> tops) {
        double worst = -1.0;
        for (int[] pair : PAIRS) {
            Float r = tops.get(pair[0]);
            Float l = tops.get(pair[1]);
            if (r == null || l == null || r <= 0 || l <= 0) {
                continue;
            }
            worst = Math.max(worst, Math.abs(r - l));
        }
        return worst;
    }

    private static float midpoint(Float a, Float b) {
        if (a == null || a <= 0) {
            return b == null ? -1.0f : b;
        }
        if (b == null || b <= 0) {
            return a;
        }
        return (a + b) * 0.5f;
    }

    /** The joints whose own geometry the production `computePivots` reads (the rest are derived). */
    private static boolean isPivotReadJoint(int joint) {
        return joint == JointTable.THIGH_R || joint == JointTable.THIGH_L
                || joint == JointTable.LEG_R || joint == JointTable.LEG_L
                || joint == JointTable.ARM_R || joint == JointTable.ARM_L
                || joint == JointTable.HAND_R || joint == JointTable.HAND_L
                || joint == JointTable.CHEST;
    }

    private enum Rule { R0, MAPPED_FIRST, MERGED_VARIANTS }

    /**
     * Why a mapped bone ever sits in a higher tier than an unmapped one for the same joint: the
     * aggregate that decides whether "mapped first across tiers" is a targeted repair or a blunt one.
     */
    @Test
    void whyMappedSitsHigher() throws Exception {
        String root = System.getenv("YSMEF_YSM_CORPUS_ROOT");
        assumeTrue(root != null && !root.isEmpty(), "set YSMEF_YSM_CORPUS_ROOT to sweep the model corpus");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));

        Map<String, Integer> shapeCounts = new HashMap<>();
        Map<String, Integer> jointCounts = new HashMap<>();
        Map<String, Integer> jointShapes = new HashMap<>();
        List<String> biggest = new ArrayList<>();
        List<double[]> deltas = new ArrayList<>();
        int parsed = 0;
        for (Path file : files) {
            YSMGeoModel model;
            float sw;
            float sh;
            try {
                YsmBinaryReader.BinaryModel binary = YsmBinaryReader.read(
                        YsmFileCrypto.decryptYsmFile(Files.readAllBytes(file)));
                model = YSMGeoModel.fromBinary(binary);
                sw = binary.widthScale;
                sh = binary.heightScale;
            } catch (Throwable t) {
                continue;
            }
            parsed++;
            Set<String> baseForms = BoneAlternateForms.baseFormsPresent(
                    model.bonesByName.keySet().toArray(new String[0]));
            // joint -> tier -> mapped? -> {vertices, bone names}
            Map<Integer, List<Object[]>[][]> buckets = new HashMap<>();
            for (YSMGeoModel.Bone bone : model.bonesByName.values()) {
                if (bone.quads.isEmpty()) {
                    continue;
                }
                int joint = YSMJointMapper.resolveJointId(bone, model);
                if (joint < 0 || joint >= JointTable.COUNT) {
                    continue;
                }
                int tier = YsmBindArmature.tierOf(bone.name, baseForms, Set.of());
                boolean mapped = YSMJointMapper.isDirectlyMapped(bone);
                List<Object[]>[][] byTier = buckets.computeIfAbsent(joint, k -> {
                    @SuppressWarnings("unchecked")
                    List<Object[]>[][] arrays = new List[4][2];
                    return arrays;
                });
                if (byTier[tier][mapped ? 0 : 1] == null) {
                    byTier[tier][mapped ? 0 : 1] = new ArrayList<>();
                }
                List<Vector3f> vertices = new ArrayList<>();
                bakeInto(vertices, bone, sw, sh);
                byTier[tier][mapped ? 0 : 1].add(new Object[]{bone.name, vertices});
            }
            for (Map.Entry<Integer, List<Object[]>[][]> entry : buckets.entrySet()) {
                List<Object[]>[][] byTier = entry.getValue();
                int anyTier = -1;
                for (int tier = 0; tier < 4 && anyTier < 0; tier++) {
                    if (byTier[tier][0] != null || byTier[tier][1] != null) {
                        anyTier = tier;
                    }
                }
                if (anyTier < 0) {
                    continue;
                }
                int mappedTier = -1;
                for (int tier = 0; tier < 4 && mappedTier < 0; tier++) {
                    if (byTier[tier][0] != null) {
                        mappedTier = tier;
                    }
                }
                if (mappedTier <= anyTier) {
                    continue;
                }
                jointCounts.merge(JointTable.nameOf(entry.getKey()), 1, Integer::sum);
                String shape = "any-tier " + anyTier + " unmapped vs mapped tier " + mappedTier;
                jointShapes.merge(JointTable.nameOf(entry.getKey()) + " | " + shape, 1, Integer::sum);
                shapeCounts.merge(shape, 1, Integer::sum);
                double oldTop = tierTop(byTier[anyTier][0], byTier[anyTier][1]);
                double newTop = top(byTier[mappedTier][0]);
                deltas.add(new double[]{Math.abs(newTop - oldTop)});
                String detail = file.getFileName() + " " + JointTable.nameOf(entry.getKey())
                        + " tier" + anyTier + " unmapped=" + names(byTier[anyTier][1])
                        + " (top " + f(oldTop) + ")"
                        + " tier" + mappedTier + " mapped=" + names(byTier[mappedTier][0])
                        + " (top " + f(newTop) + ")";
                biggest.add(detail);
            }
        }
        deltas.sort(Comparator.comparingDouble((double[] d) -> -d[0]));
        biggest.sort(Comparator.comparingDouble((String s) -> -Double.parseDouble(
                s.substring(s.lastIndexOf("(top ") + 5, s.lastIndexOf(")")))));
        StringBuilder out = new StringBuilder();
        out.append("models parsed=").append(parsed).append('\n');
        out.append("\n## joints where a mapped bone sits in a higher tier than an unmapped one\n");
        jointCounts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> out.append(String.format(Locale.ROOT, "%-12s %d%n", e.getKey(), e.getValue())));
        out.append("\n## tier shapes\n");
        shapeCounts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> out.append(String.format(Locale.ROOT, "%-40s %d%n", e.getKey(), e.getValue())));
        out.append("\n## per joint x shape\n");
        jointShapes.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40)
                .forEach(e -> out.append(String.format(Locale.ROOT, "%-70s %d%n", e.getKey(), e.getValue())));
        out.append("\n## 40 largest top differences\n");
        for (int i = 0; i < Math.min(40, biggest.size()); i++) {
            out.append(biggest.get(i)).append('\n');
        }
        out.append("\n## |new-old| percentiles: p50=");
        out.append(deltas.isEmpty() ? "-" : f(deltas.get(deltas.size() / 2)[0]));
        out.append(" p90=").append(deltas.isEmpty() ? "-" : f(deltas.get(deltas.size() / 10)[0]));
        out.append(" max=").append(deltas.isEmpty() ? "-" : f(deltas.get(0)[0])).append('\n');
        Path report = Paths.get("build", "reports", "ysm-pivot-tier-why.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);
    }

    private static String names(List<Object[]> bucket) {
        if (bucket == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bucket.size() && i < 4; i++) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(bucket.get(i)[0]);
        }
        if (bucket.size() > 4) {
            sb.append("+").append(bucket.size() - 4);
        }
        return sb.toString();
    }

    private static float top(List<Object[]> bucket) {
        if (bucket == null) {
            return -1;
        }
        List<Vector3f> all = new ArrayList<>();
        for (Object[] entry : bucket) {
            @SuppressWarnings("unchecked")
            List<Vector3f> vertices = (List<Vector3f>) entry[1];
            all.addAll(vertices);
        }
        return all.isEmpty() ? -1.0f : topOf(all).y;
    }

    /** The tier's own answer, as the production merge does it: the mapped bucket wins when present. */
    private static float tierTop(List<Object[]> mapped, List<Object[]> unmapped) {
        return mapped != null ? top(mapped) : top(unmapped);
    }

    @SuppressWarnings("unchecked")
    private static List<Vector3f>[][] newBuckets() {
        return new List[4][2];
    }

    /** The pivot the rule would derive for one joint, from the joint's four tiers and mapped flag. */
    private static Map<Integer, Vector3f> pivots(Map<Integer, List<Vector3f>[][]> buckets, Rule rule) {
        Map<Integer, Vector3f> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Vector3f>[][]> entry : buckets.entrySet()) {
            List<Vector3f>[][] byTier = entry.getValue();
            List<Vector3f> chosen = null;
            switch (rule) {
                case R0 -> {
                    for (int tier = 0; tier < 4 && chosen == null; tier++) {
                        chosen = byTier[tier][0] != null ? byTier[tier][0] : byTier[tier][1];
                    }
                }
                case MAPPED_FIRST -> {
                    int[][] passes = {{0, 0}, {1, 0}, {2, 0}, {0, 1}, {1, 1}, {2, 1}, {3, 0}, {3, 1}};
                    for (int[] pass : passes) {
                        if (chosen == null && byTier[pass[0]][pass[1]] != null) {
                            chosen = byTier[pass[0]][pass[1]];
                        }
                    }
                }
                case MERGED_VARIANTS -> {
                    List<Vector3f> strictMapped = merge(byTier[0][0], byTier[1][0]);
                    List<Vector3f> strictUnmapped = merge(byTier[0][1], byTier[1][1]);
                    chosen = strictMapped != null ? strictMapped : strictUnmapped;
                    if (chosen == null) {
                        chosen = byTier[2][0] != null ? byTier[2][0] : byTier[2][1];
                    }
                    if (chosen == null) {
                        chosen = byTier[3][0] != null ? byTier[3][0] : byTier[3][1];
                    }
                }
                default -> throw new IllegalStateException();
            }
            if (chosen != null && !chosen.isEmpty()) {
                out.put(entry.getKey(), topOf(chosen));
            }
        }
        return out;
    }

    private static List<Vector3f> merge(List<Vector3f> a, List<Vector3f> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        List<Vector3f> merged = new ArrayList<>(a.size() + b.size());
        merged.addAll(a);
        merged.addAll(b);
        return merged;
    }

    /** The production topOf: centroid of the vertices within 0.05 of the maximum Y. */
    private static Vector3f topOf(List<Vector3f> vertices) {
        float maxY = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            maxY = Math.max(maxY, v.y);
        }
        Vector3f acc = new Vector3f();
        int n = 0;
        for (Vector3f v : vertices) {
            if (v.y >= maxY - 0.05f) {
                acc.add(v);
                n++;
            }
        }
        return n == 0 ? new Vector3f(vertices.get(0)) : acc.div(n);
    }

    private static double symmetryError(Map<Integer, Vector3f> pivots) {
        double worst = -1.0;
        for (int[] pair : PAIRS) {
            Vector3f r = pivots.get(pair[0]);
            Vector3f l = pivots.get(pair[1]);
            if (r == null || l == null) {
                continue;
            }
            double error = Math.max(Math.abs(r.y - l.y),
                    Math.max(Math.abs(r.z - l.z), Math.abs(r.x + l.x)));
            worst = Math.max(worst, error);
        }
        return worst;
    }

    private static String movedPairs(Map<Integer, Vector3f> r0, Map<Integer, Vector3f> ra, Map<Integer, Vector3f> rb) {
        StringBuilder sb = new StringBuilder();
        for (int[] pair : PAIRS) {
            Vector3f a = r0.get(pair[0]);
            Vector3f b = ra.get(pair[0]);
            Vector3f c = rb.get(pair[0]);
            double da = a == null || b == null ? 0 : a.y - b.y;
            double db = a == null || c == null ? 0 : a.y - c.y;
            if (Math.abs(da) > 0.02 || Math.abs(db) > 0.02) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(JointTable.nameOf(pair[0])).append(' ')
                        .append(a == null ? "-" : f(a.y)).append(" -> ")
                        .append(b == null ? "-" : f(b.y)).append(" -> ")
                        .append(c == null ? "-" : f(c.y));
            }
        }
        return sb.toString();
    }

    private static double err(double v) {
        return Double.isFinite(v) && v > 0 ? v : 0.0;
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.3f", v) : "-";
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static void bakeInto(List<Vector3f> target, YSMGeoModel.Bone bone, float scaleW, float scaleH) {
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
        for (YSMGeoModel.Quad quad : bone.quads) {
            for (Vector3f corner : quad.positions) {
                if (corner == null) {
                    continue;
                }
                Vector3f p = new Vector3f(corner).mulPosition(world);
                target.add(new Vector3f(p.x * scaleW, p.y * scaleH, p.z * scaleW));
            }
        }
    }
}
