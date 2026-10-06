package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.ysm.YsmBinaryReader;
import com.ysmef.compat.ysm.YsmFileCrypto;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in, full-corpus check that geometric rejection cannot sever a surviving bone chain. */
class YsmPhysicsParentGeometryCorpusTest {

    @Test
    void survivingPiecesKeepTheirNearestSurvivingAncestor() throws Exception {
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
        int assembled = 0;
        int pieces = 0;
        int formerBrokenLinks = 0;
        int hingesOutsideOwnGeometry = 0;
        int hingesAwayFromSupport = 0;
        float maxAnchorShift = 0.0F;
        List<String> formerExamples = new ArrayList<>();
        List<String> hingeExamples = new ArrayList<>();
        List<String> unreadableExamples = new ArrayList<>();
        for (Path file : files) {
            YsmBinaryReader.BinaryModel binary;
            YSMGeoModel model;
            try {
                binary = YsmBinaryReader.read(YsmFileCrypto.decryptYsmFile(
                        Files.readAllBytes(file)));
                model = YSMGeoModel.fromBinary(binary);
            } catch (Exception exception) {
                skipped++;
                if (unreadableExamples.size() < 12) {
                    unreadableExamples.add(file.getFileName() + ": "
                            + exception.getClass().getSimpleName());
                }
                continue;
            }
            parsed++;
            YsmLegSkirtCollisionProbeTest.Rig rig =
                    YsmLegSkirtCollisionProbeTest.fromRawModel(model, binary.widthScale,
                            binary.heightScale, file.getFileName().toString());
            if (!rig.segments.isEmpty()) {
                assembled++;
            }
            Map<Integer, Integer> segmentOfBone = new HashMap<>();
            for (YsmLegSkirtCollisionProbeTest.Segment segment : rig.segments) {
                segmentOfBone.put(segment.boneIndex, segment.index);
            }
            Map<Integer, List<Vector3f>> vertices = ownVertices(rig);
            for (YsmLegSkirtCollisionProbeTest.Segment segment : rig.segments) {
                pieces++;
                int preferred = firstGeometryAncestor(rig, segment.boneIndex);
                int resolved = YsmPhysicsParts.resolveParent(segment.boneIndex, preferred,
                        rig.bones, segmentOfBone);
                assertEquals(segment.parent, resolved, file.getFileName() + "/" + segment.name
                        + " differs from the independently assembled nearest surviving parent");
                int previous = segmentOfBone.getOrDefault(preferred, -1);
                if (previous < 0 && resolved >= 0) {
                    formerBrokenLinks++;
                    if (formerExamples.size() < 30) {
                        formerExamples.add(file.getFileName() + " / " + segment.name
                                + " rejected immediate=" + (preferred < 0 ? "-"
                                : rig.bones[preferred].name)
                                + " surviving=" + rig.segments.get(resolved).name);
                    }
                }
                assertTrue(YsmDynamicBoneSolver.isFinite(segment.bindPivot)
                                && YsmDynamicBoneSolver.isFinite(segment.bindAnchor),
                        file.getFileName() + "/" + segment.name + " has a non-finite hinge");
                float shift = segment.bindAnchor.distance(segment.bindPivot);
                maxAnchorShift = Math.max(maxAnchorShift, shift);
                assertTrue(shift <= Math.min(YsmPhysicsParts.CONTACT_HINGE_LIMIT,
                                segment.lever) + 1.0E-4F,
                        file.getFileName() + "/" + segment.name
                                + " has a hinge outside its own geometric allowance: " + shift);
                if (shift > YsmPhysicsParts.ANCHOR_REPORT_DISTANCE) {
                    double ownGap = YsmPhysicsParts.pivotGapFromGeometry(segment.own,
                            segment.bindAnchor);
                    List<Vector3f> support = YsmPhysicsParts.restsOnGeometry(
                            rig.bones, segment.boneIndex, vertices);
                    double supportGap = YsmPhysicsParts.pivotGapFromGeometry(support,
                            segment.bindAnchor);
                    if (ownGap > 0.01D) {
                        hingesOutsideOwnGeometry++;
                    }
                    if (supportGap > 0.1D) {
                        hingesAwayFromSupport++;
                    }
                    if ((ownGap > 0.01D || supportGap > 0.1D)
                            && hingeExamples.size() < 30) {
                        hingeExamples.add(file.getFileName() + " / " + segment.name
                                + " shift=" + shift + " own-box-gap=" + ownGap
                                + " support-box-gap=" + supportGap);
                    }
                }
            }
        }
        assertTrue(parsed >= 700, "the audit did not read enough of the supplied corpus: " + parsed);
        assertTrue(pieces > 10000, "the audit did not assemble enough geometry: " + pieces);
        assertTrue(formerBrokenLinks > 0,
                "the corpus no longer exercises a rejected parent with a surviving grandparent");
        assertEquals(0, hingesAwayFromSupport,
                "a moved hinge still has no nearby support geometry");

        String report = "# Physics parent and hinge corpus audit\n\n"
                + "packages=" + files.size() + " parsed=" + parsed + " unreadable=" + skipped
                + " assembled=" + assembled + " segments=" + pieces + "\n\n"
                + "Links the old production parent lookup would sever after geometric rejection: "
                + formerBrokenLinks + "\n\n"
                + "Largest pivot-to-hinge shift: " + maxAnchorShift + " blocks\n\n"
                + "Moved hinges outside own geometry by >0.01 block: "
                + hingesOutsideOwnGeometry + "\n"
                + "Moved hinges outside support geometry by >0.1 block: "
                + hingesAwayFromSupport + "\n\n"
                + "## Former broken-link examples\n\n"
                + String.join("\n", formerExamples) + "\n\n"
                + "## Hinge examples requiring inspection\n\n"
                + String.join("\n", hingeExamples) + "\n\n"
                + "## First unreadable packages\n\n"
                + String.join("\n", unreadableExamples) + "\n";
        Path output = Path.of("build", "reports", "ysm-physics-parent-geometry-corpus.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println(report.substring(0, Math.min(1200, report.length())));
    }

    private static int firstGeometryAncestor(YsmLegSkirtCollisionProbeTest.Rig rig, int bone) {
        int parent = rig.bones[bone].parent;
        for (int guard = 0; parent >= 0 && parent < rig.bones.length && guard < rig.bones.length;
             guard++) {
            if (rig.ownVertexCount(parent) >= 4 && rig.selectionParts(parent).length > 0) {
                return parent;
            }
            int next = rig.bones[parent].parent;
            if (next == parent) {
                break;
            }
            parent = next;
        }
        return -1;
    }

    private static Map<Integer, List<Vector3f>> ownVertices(
            YsmLegSkirtCollisionProbeTest.Rig rig) {
        Map<Integer, List<Vector3f>> vertices = new HashMap<>();
        for (int bone = 0; bone < rig.bones.length; bone++) {
            List<Vector3f> own = rig.ownVertices(bone);
            if (own != null && !own.isEmpty()) {
                vertices.put(bone, own);
            }
        }
        return vertices;
    }
}
