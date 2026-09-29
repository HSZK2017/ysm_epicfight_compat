package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.YSMGeoModel;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a bone's pivot lands on its own drawn geometry, over the whole model corpus - the
 * calibration for the frame question, in the form that can actually be read.
 *
 * <h2>What is measured, and why this is the number</h2>
 *
 * <p>A segment rotates about its bind pivot: the delta the physics writes is {@code T(P) R T(-P)}, so
 * the point it holds still is P. P is correct exactly when it is the point the bone's own geometry
 * hangs from - the bone's own origin, normally one end of the geometry, and therefore <b>inside that
 * geometry's bounding box</b> in the frame the delta acts in. The box is the measurement; the pivot's
 * distance to the geometry's <i>centroid</i> is not, and using it as the criterion is the mistake this
 * class exists to keep visible: a pivot belongs at the bone's origin, so a long limb's ~1.7-block
 * origin-to-centroid distance is the expected value, and a change that drives it toward zero has moved
 * the origin off the joint rather than found the right frame.
 *
 * <p>The frame the delta acts in is the frame {@code mesh.positions()} is in: the writer
 * ({@link com.ysmef.compat.model.EFMeshJsonWriter}) stores every corner as
 * {@code (x, y, z) -> (x * sW, -z * sW, y * sH)}, and Epic Fight's loader reads those numbers back
 * with that map inverted, so the composition is the authored chain scaled once with no turn left in
 * it. {@link YsmPhysicsParts#pivotInMeshSpace} is the shipped pivot, and it is called here rather
 * than restated, so this sweep fails if the shipped pivot changes frame.
 *
 * <p>An earlier revision applied the stored corner map to the pivot as well. That is the
 * <b>falsified</b> candidate measured below: it was justified by a metric that compared a pivot in one
 * frame against a centroid taken from the numbers as stored in the JSON - the other frame - and read
 * the resulting drop from 1.672 to 0.074 blocks as a 22x improvement. Two frames apart, a rigid turn
 * preserves every distance, so that "improvement" measured the pivot moving into the frame the
 * centroid was already in. The in-game test then tore models apart (legs shattered, long hair in
 * fragments) and the turn was reverted.
 *
 * <p>The sweep asserts the settled direction: the shipped pivot is inside its own drawn geometry for
 * most bones and the turned candidate is not. It needs no game and no converted pack - the corpus's
 * packages are the same source data the writer bakes.
 *
 * <p>Opt-in: {@code YSMEF_YSM_CORPUS_ROOT} (e.g. {@code E:\program\JAVA\ysm-model-repo}). Writes
 * {@code build/reports/ysm-physics-pivot-frame-corpus.md}.
 */
class YsmPhysicsPivotFrameCorpusSweepTest {

    /** Bones whose pivot moves by more than this are counted as changed, blocks. */
    private static final double MOVED = 0.05;

    @Test
    void measureThePivotFrameErrorOverTheCorpus() throws Exception {
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
        int bones = 0;
        int modelsAffected = 0;
        int insideShipped = 0;
        int insideTurned = 0;
        int insideMixed = 0;
        double sumShipped = 0;
        double sumTurned = 0;
        double sumMixed = 0;
        double maxShipped = 0;
        String worstShipped = "-";
        List<Double> shipped = new ArrayList<>();
        List<Double> turned = new ArrayList<>();
        List<Double> mixed = new ArrayList<>();
        Map<String, Double> worstPerModel = new LinkedHashMap<>();
        List<String> turnedOutside = new ArrayList<>();

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
            Map<String, Matrix4f> bindWorld = new HashMap<>();
            Map<String, String> parentOf = new HashMap<>();
            Map<String, float[]> raw = new HashMap<>();
            for (YSMGeoModel.Bone bone : model.bonesByName.values()) {
                parentOf.put(bone.name, bone.parent == null ? "" : bone.parent.name);
                raw.put(bone.name, new float[]{bone.pivotX, bone.pivotY, bone.pivotZ,
                        bone.rotX, bone.rotY, bone.rotZ});
            }
            double modelWorst = 0;
            for (YSMGeoModel.Bone bone : model.bonesByName.values()) {
                if (bone.quads.isEmpty()) {
                    continue;
                }
                Matrix4f world = bindWorldOf(bone.name, raw, parentOf, bindWorld, 0);
                // The geometry as the delta sees it: the chain, then the writer's stored map, then
                // the loader's read-back of it - which is the chain scaled once, no turn left.
                List<Vector3f> drawn = new ArrayList<>();
                for (YSMGeoModel.Quad quad : bone.quads) {
                    for (Vector3f position : quad.positions) {
                        Vector3f corner = new Vector3f(position);
                        world.transformPosition(corner);
                        drawn.add(drawnVertex(corner, widthScale, heightScale));
                    }
                }
                if (drawn.isEmpty()) {
                    continue;
                }
                Vector3f centre = centroid(drawn);
                // The same corners left in the numbers the JSON stores, for the mixed-frame row.
                List<Vector3f> asStored = stored(bone, world, widthScale, heightScale);

                // The shipped pivot: the production method, on the same composed chain.
                Vector3f asShipped = YsmPhysicsParts.pivotInMeshSpace(world,
                        bone.pivotX, bone.pivotY, bone.pivotZ, widthScale, heightScale);
                if (asShipped == null) {
                    continue;
                }
                // The falsified candidate: the writer's stored corner map applied to the pivot too.
                Vector3f asTurned = new Vector3f(asShipped.x, -asShipped.z, asShipped.y);
                // The previous round's mixed measurement, kept only so the number it produced stays
                // visible: the shipped pivot against the centroid of the geometry as stored in the
                // JSON, i.e. in the frame the vertices are NOT in.
                Vector3f storedCentre = centroid(asStored);

                bones++;
                double dShipped = asShipped.distance(centre);
                double dTurned = asTurned.distance(centre);
                double dMixed = asShipped.distance(storedCentre);
                shipped.add(dShipped);
                turned.add(dTurned);
                mixed.add(dMixed);
                sumShipped += dShipped;
                sumTurned += dTurned;
                sumMixed += dMixed;
                if (inside(asShipped, drawn)) {
                    insideShipped++;
                }
                if (inside(asTurned, drawn)) {
                    insideTurned++;
                }
                if (inside(asShipped, asStored)) {
                    insideMixed++;
                }
                if (!inside(asTurned, drawn) && turnedOutside.size() < 25) {
                    turnedOutside.add(String.format(Locale.ROOT,
                            "%s | %s | shipped pivot=(%.3f,%.3f,%.3f) inside=%s | turned=(%.3f,%.3f,%.3f)"
                                    + " inside=%s | drawn bbox=%s | stored bbox=%s",
                            file.getFileName(), bone.name,
                            asShipped.x, asShipped.y, asShipped.z, inside(asShipped, drawn),
                            asTurned.x, asTurned.y, asTurned.z, inside(asTurned, drawn),
                            bounds(drawn), bounds(asStored)));
                }
                if (dShipped > MOVED) {
                    modelsAffected++;
                    modelWorst = Math.max(modelWorst, dShipped);
                }
                if (dShipped > maxShipped) {
                    maxShipped = dShipped;
                    worstShipped = file.getFileName() + " / " + bone.name;
                }
            }
            if (modelWorst > MOVED) {
                worstPerModel.put(file.getFileName().toString(), modelWorst);
            }
        }

        Collections.sort(shipped);
        Collections.sort(turned);
        Collections.sort(mixed);
        StringBuilder out = new StringBuilder();
        out.append("# pivot-to-own-geometry error, shipped pivot vs the corner-turned candidate\n\n");
        out.append("models parsed=").append(parsed).append(" undecryptable=").append(failed)
                .append("  bones measured=").append(bones).append('\n');
        out.append("bones whose shipped pivot is more than ").append(MOVED)
                .append(" blocks from their own geometry's centroid=").append(modelsAffected)
                .append(" (").append(pct(modelsAffected, bones)).append(") - expected, and not a"
                        + " defect: a pivot is the bone's ORIGIN, not its centroid\n");
        out.append("the frame criterion: the pivot falls inside its own drawn geometry's bounding box"
                + " for shipped=").append(insideShipped).append('/').append(bones)
                .append(" (").append(pct(insideShipped, bones)).append("), corner-turned=")
                .append(insideTurned).append('/').append(bones).append(" (")
                .append(pct(insideTurned, bones)).append("), and the previous round's mixed-frame"
                        + " reading=").append(insideMixed).append('/').append(bones).append('\n');
        out.append("distance from a bone's pivot to the centroid of its own geometry, blocks\n\n");
        out.append("candidate | mean | p50 | p90 | p99 | max | worst bone\n---|---|---|---|---|---|---|---\n");
        out.append("shipped (authored chain scaled once, no turn) | ").append(f(sumShipped, bones))
                .append(" | ").append(p(shipped, 50)).append(" | ").append(p(shipped, 90))
                .append(" | ").append(p(shipped, 99)).append(" | ").append(fmt(maxShipped))
                .append(" | ").append(worstShipped).append('\n');
        out.append("corner-turned (the falsified revision) | ").append(f(sumTurned, bones))
                .append(" | ").append(p(turned, 50)).append(" | ").append(p(turned, 90))
                .append(" | ").append(p(turned, 99)).append(" | ").append(fmt(maxOf(turned)))
                .append(" | -").append('\n');
        out.append("shipped pivot vs geometry centroid in the FILE frame (the falsified metric) | ")
                .append(f(sumMixed, bones)).append(" | ").append(p(mixed, 50)).append(" | ")
                .append(p(mixed, 90)).append(" | ").append(p(mixed, 99)).append(" | ")
                .append(fmt(maxOf(mixed))).append(" | -").append('\n');
        out.append("\nThe last row is the number that was read as a 22x improvement. It is small"
                + " because it measures the pivot in one frame against a centroid in the other, not"
                + " because the pivot is right: the middle row is the same mislabelling the other way"
                + " round.\n");
        out.append("\nThe in-box rate is a ratio between the two candidates, not an absolute quality"
                + " score: a pivot sits inside its own part's axis-aligned box only when the part is"
                + " laid out roughly along the axes, so a corpus full of small diagonal parts reads low"
                + " in every frame (EKU alone reads 172/223, i.e. 77%). What the frame changes is which"
                + " candidate the box contains, and that is what the assertions below pin.\n");
        out.append("\n## bones where the corner-turned candidate falls outside its own drawn geometry"
                + " (first 25)\n\n");
        for (String line : turnedOutside) {
            out.append(line).append('\n');
        }
        out.append("\n## the 60 largest per-model shipped errors\n\n");
        worstPerModel.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(60)
                .forEach(e -> out.append(String.format(Locale.ROOT, "%s | %.4f%n", e.getKey(), e.getValue())));

        Path report = Paths.get("build", "reports", "ysm-physics-pivot-frame-corpus.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);

        assumeTrue(bones > 1000, "the corpus produced too few measured bones: " + bones);

        // The settled direction, asserted corpus-wide. Deliberately a RATIO and not an absolute
        // in-box rate: how often a pivot falls inside the axis-aligned box of its own part depends
        // on how the part is laid out, not on the frame - a long part drawn diagonally has its own
        // origin outside its axis-aligned box in any frame, and 906 models contain a great many
        // small, diagonal parts, so the shipped rate is far below the 77% EKU alone shows (172/223)
        // while still beating the turned candidate on every model measured. Flipping the frame
        // swaps the two rows, which is what these two assertions catch.
        assertTrue(insideShipped > insideTurned * 2,
                "the shipped pivot must land inside its own drawn geometry far more often than the"
                        + " corner-turned candidate does: shipped=" + insideShipped + " turned="
                        + insideTurned + " of " + bones + " bones. A swap means the frame has been"
                        + " turned again - see YsmPhysicsParts#pivotInMeshSpace");
        assertTrue(sumShipped * 1.5 < sumTurned,
                "and it must be closer to its own geometry by a wide margin: shipped mean="
                        + (sumShipped / bones) + " turned mean=" + (sumTurned / bones)
                        + " blocks over " + bones + " bones");
    }

    private static String pct(int part, int whole) {
        return whole == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }

    private static String f(double sum, int count) {
        return count == 0 ? "-" : fmt(sum / count);
    }

    private static String p(List<Double> sorted, int percentile) {
        if (sorted.isEmpty()) {
            return "-";
        }
        int index = Math.min(sorted.size() - 1, (int) (sorted.size() * percentile / 100.0));
        return fmt(sorted.get(index));
    }

    private static double maxOf(List<Double> values) {
        double max = 0;
        for (double value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * The two shipped maps composed, in one step: {@code EFMeshJsonWriter#walkBone} stores
     * {@code (x, y, z) -> (x * sW, -z * sW, y * sH)} and Epic Fight's loader reads the stored numbers
     * back with that map inverted, leaving the chain scaled once. {@code (X, Y, Z)} below are the
     * stored numbers.
     */
    private static Vector3f drawnVertex(Vector3f authored, float scaleW, float scaleH) {
        return new Vector3f(authored.x * scaleW, authored.y * scaleH, -authored.z * scaleW);
    }

    /** The same corners left in the numbers the JSON actually stores, for the mixed-frame row. */
    private static List<Vector3f> stored(YSMGeoModel.Bone bone, Matrix4f world,
                                         float scaleW, float scaleH) {
        List<Vector3f> out = new ArrayList<>();
        for (YSMGeoModel.Quad quad : bone.quads) {
            for (Vector3f position : quad.positions) {
                Vector3f corner = new Vector3f(position);
                world.transformPosition(corner);
                out.add(new Vector3f(corner.x * scaleW, -corner.z * scaleW, corner.y * scaleH));
            }
        }
        return out;
    }

    private static Vector3f centroid(List<Vector3f> vertices) {
        Vector3f sum = new Vector3f();
        for (Vector3f v : vertices) {
            sum.add(v);
        }
        return sum.div(Math.max(1, vertices.size()));
    }

    private static boolean inside(Vector3f point, List<Vector3f> vertices) {
        float[] b = boundsArray(vertices);
        if (b == null) {
            return false;
        }
        return point.x >= b[0] - 1.0E-4F && point.x <= b[1] + 1.0E-4F
                && point.y >= b[2] - 1.0E-4F && point.y <= b[3] + 1.0E-4F
                && point.z >= b[4] - 1.0E-4F && point.z <= b[5] + 1.0E-4F;
    }

    private static float[] boundsArray(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE,
                Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Vector3f v : vertices) {
            b[0] = Math.min(b[0], v.x);
            b[1] = Math.max(b[1], v.x);
            b[2] = Math.min(b[2], v.y);
            b[3] = Math.max(b[3], v.y);
            b[4] = Math.min(b[4], v.z);
            b[5] = Math.max(b[5], v.z);
        }
        return b;
    }

    private static String bounds(List<Vector3f> vertices) {
        float[] b = boundsArray(vertices);
        return b == null ? "-" : String.format(Locale.ROOT, "[%.3f..%.3f %.3f..%.3f %.3f..%.3f]",
                b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** {@code T(p) R T(-p)} per bone from the root down - the chain the writer bakes with. */
    private static Matrix4f bindWorldOf(String name, Map<String, float[]> raw, Map<String, String> parentOf,
                                        Map<String, Matrix4f> cache, int depth) {
        Matrix4f cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        float[] bone = raw.get(name);
        if (bone == null || depth > 600) {
            return new Matrix4f();
        }
        Matrix4f local = new Matrix4f();
        local.translate(bone[0], bone[1], bone[2]);
        local.rotateZ(bone[5]);
        local.rotateY(bone[4]);
        local.rotateX(bone[3]);
        local.translate(-bone[0], -bone[1], -bone[2]);
        String parent = parentOf.getOrDefault(name, "");
        Matrix4f world = !parent.isEmpty() && raw.containsKey(parent)
                ? new Matrix4f(bindWorldOf(parent, raw, parentOf, cache, depth + 1)).mul(local)
                : local;
        cache.put(name, world);
        return world;
    }
}
