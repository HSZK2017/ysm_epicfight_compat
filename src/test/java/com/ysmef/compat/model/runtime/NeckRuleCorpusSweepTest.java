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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measurement, not an assertion: candidate neck rules over the whole model corpus, so a change to the
 * neck can be judged by its blast radius before it ships. Run with {@code YSMEF_YSM_CORPUS_ROOT}.
 *
 * <p>The rules compared, all of which keep the neck's height at the top of the chest geometry:
 *
 * <ul>
 *   <li><b>A</b> the top ring's own mean (the shipped rule) - the neck's whole position;</li>
 *   <li><b>B</b> A's height with the horizontal position of the <i>closest approach</i> between the
 *       Chest-bound and the Head-bound geometry, i.e. the seam where the head's base meets the
 *       chest's collar. This is a property of both joints at once and cannot be moved by an ornament
 *       that has no other joint's geometry next to it.</li>
 * </ul>
 *
 * <p>Reported per model: how far the horizontal position moves, and the two rules' height difference.
 */
class NeckRuleCorpusSweepTest {

    /** Up to this many vertices per joint are sampled for the closest-approach search (grid-bucketed). */
    private static final int SAMPLE_LIMIT = 6000;

    @Test
    void neckRulesOverTheCorpus() throws Exception {
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
        int skipped = 0;
        int withSeam = 0;
        int moved = 0;
        double sumMove = 0;
        double worst = 0;
        String worstModel = "-";
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
                skipped++;
                continue;
            }
            parsed++;
            Set<String> baseForms = BoneAlternateForms.baseFormsPresent(
                    model.bonesByName.keySet().toArray(new String[0]));
            List<Vector3f> chestVertices = new ArrayList<>();
            List<Vector3f> headVertices = new ArrayList<>();
            for (YSMGeoModel.Bone bone : model.bonesByName.values()) {
                if (bone.quads.isEmpty()) {
                    continue;
                }
                int joint = YSMJointMapper.resolveJointId(bone, model);
                int tier = YsmBindArmature.tierOf(bone.name, baseForms, Set.of());
                if (tier != 0) {
                    continue;
                }
                if (joint == JointTable.CHEST) {
                    bakeInto(chestVertices, bone, sw, sh);
                } else if (joint == JointTable.HEAD) {
                    bakeInto(headVertices, bone, sw, sh);
                }
            }
            if (chestVertices.isEmpty() || headVertices.isEmpty()) {
                continue;
            }
            Vector3f ring = meanTopRing(chestVertices);
            if (ring == null) {
                continue;
            }
            Vector3f seam = closestApproach(chestVertices, headVertices, ring.y);
            if (seam == null) {
                continue;
            }
            withSeam++;
            float move = (float) Math.hypot(ring.x - seam.x, ring.z - seam.z);
            sumMove += move;
            if (move > 0.02f) {
                moved++;
            }
            if (move > worst) {
                worst = move;
                worstModel = file.getFileName().toString();
            }
            if (move > 0.02f && lines.size() < 400) {
                lines.add(String.format(Locale.ROOT,
                        "%s  neck x,z %+.3f,%+.3f -> %+.3f,%+.3f  moved %.3f  (seam y %.3f)",
                        file.getFileName(), ring.x, ring.z, seam.x, seam.z, move, seam.y));
            }
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT,
                "%n### neck rules over the corpus%n%n- parsed=%d, undecryptable/unparseable=%d, "
                        + "models with both chest and head geometry=%d%n- the neck's horizontal position "
                        + "moves on %d of them (%.0f%%); mean move %.4f blocks, worst %.3f blocks (%s)%n%n",
                parsed, skipped, withSeam, moved, withSeam == 0 ? 0 : 100.0 * moved / withSeam,
                withSeam == 0 ? 0 : sumMove / withSeam, worst, worstModel));
        lines.sort(Comparator.naturalOrder());
        for (String line : lines) {
            out.append("- ").append(line).append('\n');
        }
        Path report = Paths.get("build", "reports", "ysm-neck-corpus.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println(out.substring(0, Math.min(4000, out.length())));
    }

    /**
     * The midpoint of the closest pair of vertices between two geometry sets, horizontally like the
     * given rule and vertically at the pair's own y. Grid-bucketed, because a real model carries tens of
     * thousands of vertices per joint.
     */
    private static Vector3f closestApproach(List<Vector3f> a, List<Vector3f> b, float fallbackY) {
        List<Vector3f> left = sample(a);
        List<Vector3f> right = sample(b);
        if (left.isEmpty() || right.isEmpty()) {
            return null;
        }
        float cell = 0.25f;
        Map<Long, List<Vector3f>> grid = new HashMap<>();
        for (Vector3f v : right) {
            grid.computeIfAbsent(key(v, cell), k -> new ArrayList<>()).add(v);
        }
        float best = Float.MAX_VALUE;
        Vector3f bestA = null;
        Vector3f bestB = null;
        for (Vector3f v : left) {
            int cx = (int) Math.floor(v.x / cell);
            int cy = (int) Math.floor(v.y / cell);
            int cz = (int) Math.floor(v.z / cell);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        List<Vector3f> bucket = grid.get(key(cx + dx, cy + dy, cz + dz));
                        if (bucket == null) {
                            continue;
                        }
                        for (Vector3f w : bucket) {
                            float d = v.distanceSquared(w);
                            if (d < best) {
                                best = d;
                                bestA = v;
                                bestB = w;
                            }
                        }
                    }
                }
            }
        }
        if (bestA == null || !Float.isFinite(best) || best > 0.36f) {
            return null;
        }
        Vector3f mid = new Vector3f(bestA).add(bestB).mul(0.5f);
        // The rule keeps the shipped height; the seam is used for its horizontal position only.
        mid.y = fallbackY;
        return mid;
    }

    private static long key(Vector3f v, float cell) {
        return key((int) Math.floor(v.x / cell), (int) Math.floor(v.y / cell),
                (int) Math.floor(v.z / cell));
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /** A bounded, evenly spaced sample, so a 60 000-vertex joint does not make this quadratic. */
    private static List<Vector3f> sample(List<Vector3f> vertices) {
        if (vertices.size() <= SAMPLE_LIMIT) {
            return vertices;
        }
        int step = Math.max(1, vertices.size() / SAMPLE_LIMIT);
        List<Vector3f> out = new ArrayList<>(SAMPLE_LIMIT + 1);
        for (int i = 0; i < vertices.size(); i += step) {
            out.add(vertices.get(i));
        }
        return out;
    }

    private static Vector3f meanTopRing(List<Vector3f> vertices) {
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
        return n == 0 ? null : acc.div(n);
    }

    private static void bakeInto(List<Vector3f> target, YSMGeoModel.Bone bone, float scaleW, float scaleH) {
        Map<String, Matrix4f> worlds = new HashMap<>();
        Matrix4f world = worldOf(bone, worlds);
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

    private static Matrix4f worldOf(YSMGeoModel.Bone bone, Map<String, Matrix4f> cache) {
        Matrix4f cached = cache.get(bone.name);
        if (cached != null) {
            return cached;
        }
        Matrix4f local = new Matrix4f();
        local.translate(bone.pivotX, bone.pivotY, bone.pivotZ);
        local.rotateZ(bone.rotZ);
        local.rotateY(bone.rotY);
        local.rotateX(bone.rotX);
        local.translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);
        Matrix4f world = bone.parent == null
                ? local
                : new Matrix4f(worldOf(bone.parent, cache)).mul(local);
        cache.put(bone.name, world);
        return world;
    }
}
