package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.ysm.YsmBinaryReader;
import com.ysmef.compat.ysm.YsmFileCrypto;
import com.ysmef.compat.model.JointTable;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in audit of which formerly simulated parts the head-contact rule would keep rigid. */
class YsmHeadContactCorpusAuditTest {
    @Test
    void reportChangesAcrossTheBinaryModelCorpus() throws Exception {
        String root = System.getenv("YSMEF_YSM_CORPUS_ROOT");
        assumeTrue(root != null && !root.isEmpty(), "set YSMEF_YSM_CORPUS_ROOT");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".ysm"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));

        int parsed = 0;
        int skipped = 0;
        int segments = 0;
        int held = 0;
        StringBuilder report = new StringBuilder("# Head contact geometry audit\n\n")
                .append("Formerly simulated pieces selected by the fallback physics classifier. ")
                .append("The new rule requires direct, broad contact with a mapped head joint.\n\n")
                .append("model | rigid head-contact pieces\n---|---\n");
        for (Path file : files) {
            YsmBinaryReader.BinaryModel binary;
            YSMGeoModel model;
            try {
                binary = YsmBinaryReader.read(YsmFileCrypto.decryptYsmFile(Files.readAllBytes(file)));
                model = YSMGeoModel.fromBinary(binary);
            } catch (Exception unreadable) {
                skipped++;
                continue;
            }
            parsed++;
            YsmLegSkirtCollisionProbeTest.Rig rig;
            try {
                rig = YsmLegSkirtCollisionProbeTest.fromRawModel(model, binary.widthScale,
                        binary.heightScale, file.getFileName().toString());
            } catch (Exception unreadable) {
                skipped++;
                continue;
            }
            List<String> affected = new ArrayList<>();
            for (YsmLegSkirtCollisionProbeTest.Segment segment : rig.segments) {
                segments++;
                if (YsmHeadContactConstraint.holds(rig.bones, segment.boneIndex,
                        rig.ownVerticesForAudit())) {
                    held++;
                    float[] extent = extent(rig, segment.boneIndex);
                    affected.add(segment.name + " (overlap " + String.format(Locale.ROOT, "%.2f",
                            extent[0]) + ", below " + String.format(Locale.ROOT, "%.2f", extent[1]) + ")");
                }
            }
            if (!affected.isEmpty()) {
                report.append(file.getFileName()).append(" | ").append(affected).append('\n');
            }
        }
        report.insert(report.indexOf("model |"), "Parsed " + parsed + " packages, skipped " + skipped
                + ", formerly simulated " + segments + " pieces, now rigid " + held + ".\n\n");
        Path output = Path.of("build", "reports", "ysm-head-contact-corpus.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
        assertTrue(parsed > 500 && segments > 10000, "corpus was not read completely: " + report);
    }

    private static float[] extent(YsmLegSkirtCollisionProbeTest.Rig rig, int bone) {
        Map<Integer, List<Vector3f>> vertices = rig.ownVerticesForAudit();
        List<Vector3f> own = vertices.get(bone);
        for (int at = rig.bones[bone].parent; at >= 0; at = rig.bones[at].parent) {
            YSMRuntimeModel.BoneRt ancestor = rig.bones[at];
            if (ancestor.mapped && ancestor.joint == JointTable.HEAD) {
                List<Vector3f> head = vertices.get(at);
                if (own == null || head == null) {
                    return new float[]{0.0F, Float.POSITIVE_INFINITY};
                }
                float[] lo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
                float[] hi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
                for (Vector3f vertex : head) {
                    lo[0] = Math.min(lo[0], vertex.x); lo[1] = Math.min(lo[1], vertex.y);
                    lo[2] = Math.min(lo[2], vertex.z);
                    hi[0] = Math.max(hi[0], vertex.x); hi[1] = Math.max(hi[1], vertex.y);
                    hi[2] = Math.max(hi[2], vertex.z);
                }
                int inside = 0;
                float ownLow = Float.POSITIVE_INFINITY;
                for (Vector3f vertex : own) {
                    ownLow = Math.min(ownLow, vertex.y);
                    if (vertex.x >= lo[0] - 0.02F && vertex.x <= hi[0] + 0.02F
                            && vertex.y >= lo[1] - 0.02F && vertex.y <= hi[1] + 0.02F
                            && vertex.z >= lo[2] - 0.02F && vertex.z <= hi[2] + 0.02F) {
                        inside++;
                    }
                }
                return new float[]{(float) inside / own.size(),
                        Math.max(0.0F, lo[1] - ownLow) / Math.max(0.001F, hi[1] - lo[1])};
            }
        }
        return new float[]{0.0F, Float.POSITIVE_INFINITY};
    }
}
