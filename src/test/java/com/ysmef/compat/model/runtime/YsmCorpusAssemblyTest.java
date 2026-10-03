package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.YSMGeoModel;
import com.ysmef.compat.ysm.YsmBinaryReader;
import com.ysmef.compat.ysm.YsmFileCrypto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The corpus assembles pieces: a raw {@code .ysm} through the production seams yields segments.
 *
 * <p>This is the cheap half of the corpus blast-radius gate. The gate itself
 * ({@code YsmCollisionPointProbeTest#whatMovingTheTestPointDoesOverTheCorpus}) drives every model it
 * can assemble for 360 frames, which is an hours-long measurement; this test asks the question that
 * has to be true before any of it means anything - <b>does {@code fromRawModel} assemble a rig at
 * all?</b> - on a sample spread across the corpus, in seconds.
 *
 * <p>It exists because that question was silently false. `Rig.fromRawModel` was handed an EMPTY mesh
 * part map, and the selection reads it before it reads anything else: {@code ownsItsGeometry} refuses
 * a bone that declares no part, so every bone was refused, {@code selectBones} returned nothing, and
 * every one of the 906 packages in the corpus produced a rig with no pieces. The sweep reported
 * {@code parsed > 500} and {@code pieces = 0} - one assertion passing, the next failing, and nothing
 * between them saying why.
 *
 * <p>Not every package is a garment: a mechanical arm whose bones are named {@code cogwheel} and
 * {@code lan2} has nothing that hangs, so the classification correctly finds no chain in it. The
 * invariant that separates "this model has nothing to simulate" from "the assembler is broken" is
 * therefore the pairing below: <b>a model with a hang-classified chain must yield segments</b>, and
 * a corpus of character models must yield pieces in numbers.
 */
class YsmCorpusAssemblyTest {

    /** The same opt-in corpus root every other sweep in this suite reads. */
    private static final String CORPUS_ROOT = "YSMEF_YSM_CORPUS_ROOT";

    /**
     * How many packages this check assembles, spread evenly across the corpus rather than taken from
     * the front. Small enough to be a test rather than a sweep, large enough that "the assembler
     * works" is a statement about the corpus: the bug it guards against was uniform, so one model
     * proves it and a spread sample bounds it.
     */
    private static final int SAMPLE = 60;

    /**
     * How many of the sampled packages must yield pieces. Measured, not guessed: the round-10 run of
     * this test on {@code E:\program\JAVA\ysm-model-repo} reported the number in the round-10 report,
     * and the floor is set below it with room for a corpus that grows more non-garment models - and
     * far above the zero the assembler produced when it was handed an empty part map.
     */
    private static final int MIN_ASSEMBLED = 30;

    @Test
    void rawCorpusModelsAssembleSegmentsAndVolumes() throws IOException {
        String root = System.getenv(CORPUS_ROOT);
        assumeTrue(root != null && !root.isEmpty(), "set " + CORPUS_ROOT + " to sweep the model corpus");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Paths.get(root))) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".ysm"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));
        assumeTrue(!files.isEmpty(), "no .ysm packages under " + root);
        int stride = Math.max(1, files.size() / SAMPLE);

        int parsed = 0;
        int assembled = 0;
        int withHangingNames = 0;
        int withRealChains = 0;
        int realChainsButEmpty = 0;
        int pieces = 0;
        int fewestPieces = Integer.MAX_VALUE;
        int fewestVolumes = Integer.MAX_VALUE;
        String fewestPiecesModel = null;
        String fewestVolumesModel = null;
        for (int at = 0; at < files.size() && parsed < SAMPLE; at += stride) {
            Path file = files.get(at);
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
                // A package this sweep cannot read is the legacy-container question, which is not
                // this test's: it is counted by the corpus gate's own `failed` tally.
                continue;
            }
            parsed++;
            String stem = file.getFileName().toString();
            YsmLegSkirtCollisionProbeTest.Rig rig;
            try {
                rig = YsmLegSkirtCollisionProbeTest.fromRawModel(model, scaleW, scaleH, stem);
            } catch (Throwable t) {
                continue;
            }
            int volumes = rig.colliders() == null ? 0 : rig.colliders().count();
            // Whether this model has anything that hangs at all, asked of the classification with the
            // geometry requirement lifted: a model that fails even that has no garment for the
            // selection to refuse, and says nothing about the assembler.
            boolean hangsByName = !YsmPhysicsChains.build(rig.bones, index -> true).isEmpty();
            if (hangsByName) {
                withHangingNames++;
            }
            // Whether this model has a chain the selection can actually use, asked with the SAME
            // geometry rule the selection uses - rebuilt here out of the two facts the rig exposes,
            // `ownVertexCount` and `selectionParts`, which are exactly the two halves of
            // `YsmPhysicsParts.ownsItsGeometry` (its minimum is 4 vertices). This is the number the
            // empty-part-map bug zeroed: with an empty map, `selectionParts` is empty for every bone
            // and this count is 0 for every package in the corpus.
            boolean hasRealChains = !YsmPhysicsChains.build(rig.bones,
                    index -> rig.ownVertexCount(index) >= 4
                            && rig.selectionParts(index).length > 0).isEmpty();
            if (hasRealChains) {
                withRealChains++;
                if (rig.segments.isEmpty()) {
                    // Not automatically a fault - a chain whose geometry wraps its own pivot or rises
                    // off it is refused on purpose - so this is reported with the tally of reasons
                    // rather than asserted on. See `buildDrops`.
                    realChainsButEmpty++;
                    System.out.println("YSCORPUS-ASSEMBLY no pieces though a chain survives the "
                            + "geometry rule: `" + stem + "`: " + whyNoPieces(rig));
                }
            }
            if (rig.segments.isEmpty()) {
                continue;
            }
            assertTrue(volumes > 0,
                    "`" + stem + "` assembled " + rig.segments.size() + " pieces and no collision "
                            + "volumes, so every collision question on it would be vacuous. "
                            + whyNoPieces(rig));
            assembled++;
            pieces += rig.segments.size();
            if (rig.segments.size() < fewestPieces) {
                fewestPieces = rig.segments.size();
                fewestPiecesModel = stem;
            }
            if (volumes < fewestVolumes) {
                fewestVolumes = volumes;
                fewestVolumesModel = stem;
            }
        }
        System.out.println("YSCORPUS-ASSEMBLY packages=" + files.size() + " stride=" + stride
                + " parsed=" + parsed + " assembled=" + assembled
                + " withHangingNames=" + withHangingNames + " withRealChains=" + withRealChains
                + " realChainsButEmpty=" + realChainsButEmpty + " pieces=" + pieces
                + " fewestPieces=" + fewestPieces + " (`" + fewestPiecesModel + "`)"
                + " fewestVolumes=" + fewestVolumes + " (`" + fewestVolumesModel + "`)");
        assertTrue(withRealChains > 0,
                "no sampled package has a chain that survives the geometry rule: the selection is "
                        + "being handed a part map that refuses every bone (parsed=" + parsed
                        + ", withHangingNames=" + withHangingNames + ")");
        assertTrue(assembled >= MIN_ASSEMBLED,
                "the sample must assemble pieces: parsed=" + parsed + ", assembled=" + assembled
                        + ", withHangingNames=" + withHangingNames + ", withRealChains="
                        + withRealChains + " (the assembler produced ZERO pieces for every package "
                        + "when it was handed an empty mesh part map)");
        assertTrue(pieces > 0, "the corpus must assemble pieces, pieces=" + pieces);
    }

    /**
     * Which of the selection's conditions refused every bone of this rig.
     *
     * <p>The chain of conditions is long - a joint, not directly mapped, not a vetoed name, hanging,
     * carrying geometry of its own, not a continuation of an accepted chain, not a wrapper, and
     * reaching a mapped ancestor - and "no pieces" on its own says nothing about which one bit. This
     * runs the classification with the geometry requirement lifted, so the answer separates "the
     * geometry predicate refused everything" (a rig-assembly fault) from "nothing in this model
     * hangs" (a property of the model).
     */
    private static String whyNoPieces(YsmLegSkirtCollisionProbeTest.Rig rig) {
        YSMRuntimeModel.BoneRt[] bones = rig.bones;
        int mapped = 0;
        int jointed = 0;
        int withGeometry = 0;
        int vetoed = 0;
        int hangable = 0;
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < bones.length; i++) {
            YSMRuntimeModel.BoneRt bone = bones[i];
            int ownVertices = rig.ownVertexCount(i);
            if (bone.mapped) {
                mapped++;
            }
            if (bone.joint >= 0) {
                jointed++;
            }
            if (ownVertices > 0) {
                withGeometry++;
            }
            if (YsmPhysicsChains.isVetoed(bone.name)) {
                vetoed++;
            }
            boolean mappedAncestor = false;
            for (int parent = bone.parent; parent >= 0 && parent < bones.length;
                 parent = bones[parent].parent) {
                if (bones[parent].mapped) {
                    mappedAncestor = true;
                    break;
                }
            }
            if (!bone.mapped && mappedAncestor) {
                hangable++;
            }
            if (sample.length() < 400) {
                sample.append('[').append(bone.name).append(" joint=").append(bone.joint)
                        .append(" mapped=").append(bone.mapped).append(" geometry=")
                        .append(ownVertices).append("] ");
            }
        }
        int chainsAll = YsmPhysicsChains.build(bones, index -> true).size();
        int selectedAll = YsmPhysicsParts.selectBones(bones, index -> true,
                YsmPhysicsTuning.maxChains(), new int[1]).size();
        StringBuilder chainList = new StringBuilder();
        for (YsmPhysicsChains.Chain chain : YsmPhysicsChains.build(bones, index -> true)) {
            if (chainList.length() < 300) {
                chainList.append('[').append(chain.boneName()).append(" geom=")
                        .append(rig.ownVertexCount(chain.boneIndex())).append(" mapped=")
                        .append(bones[chain.boneIndex()].mapped).append(" parts=")
                        .append(rig.selectionParts(chain.boneIndex()).length).append("] ");
            }
        }
        return "bones=" + bones.length + " mapped=" + mapped + " withJoint=" + jointed
                + " withGeometry=" + withGeometry + " vetoedNames=" + vetoed
                + " unmappedWithMappedAncestor=" + hangable
                + "; chains without the geometry requirement=" + chainsAll
                + ", selected without it=" + selectedAll
                + "; the rig's own selection offered " + rig.lastSelected + " bone(s) and drafted "
                + rig.lastDrafted + ", refusals " + rig.buildDrops
                + "; those chains: " + chainList
                + "; bones: " + sample;
    }
}
