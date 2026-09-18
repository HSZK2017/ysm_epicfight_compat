package com.ysmef.compat.acceptance;

import com.ysmef.compat.model.runtime.YsmPhysicsChains;
import com.ysmef.compat.model.runtime.YSMRuntimeModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent acceptance for which bones may become simulated parts (tasks T3 and T4).
 *
 * <p>The suites' subjects are the two selection helpers the implementation tests also use,
 * reached here through {@link AcceptanceSupport} rather than through package-private
 * access, so the checks cannot be satisfied by editing the implementation's own test
 * package.
 *
 * <h2>What is being asserted, and why it is not a restatement</h2>
 *
 * <p>The shipped log for {@code wine_fox/01_taisho_maid} lists {@code Tail4}, {@code Tail5},
 * {@code Tail6} and {@code Tail7} as simulated parts that never move: their own angle is
 * {@code 0.0deg} in every sample, their lever is 0.021 to 0.098 blocks, and three of the
 * four rest directions point <i>up</i> ({@code (-0.17,0.95,0.24)}, {@code (-0.03,1.0,0.01)},
 * {@code (0.2,0.9,-0.37)}) where a tail must hang down. A part that cannot move still
 * spends the bone budget and still writes a transform, so the contract asserted here is
 * simply: <b>a bone with no geometry of its own and none under it must not be selected</b>,
 * and <b>a bone whose geometry belongs to a child must not be selected in the child's
 * place</b>.
 *
 * <p>The three shapes the task names - empty geometry, geometry belonging to a descendant,
 * and an unusably short lever - are covered where they are reachable. The lever is not:
 * the lever is computed inside {@code YsmPhysicsParts.buildSegment}, which needs a real
 * {@code YSMMesh} (an Epic Fight skinned mesh with a VBO) and therefore cannot be
 * instantiated without a game. That gap is stated in the report rather than papered over
 * with a test that does not reach it.
 */
class HangingPieceSelectionAcceptanceTest {

    private static final int JOINT_TORSO = 7;

    /**
     * A bone with no geometry and nothing geometric under it must not be swung, even
     * though its name and its ancestors read as hair.
     *
     * <p>The table is the shape that produces this on a real model: the container is
     * directly mapped, so Epic Fight owns it and the classifier skips it, and the
     * geometry-less leaf underneath then inherits the "hair" hint from a bone that was
     * never itself a chain. The leaf has no geometry of its own and no descendants, so
     * there is nothing for it to swing: it contributes a zero-length or wrongly-signed
     * lever, which is exactly the {@code rest} pointing up and the 0.02 lever in the log.
     */
    @Test
    @DisplayName("a geometry-less leaf under a mapped container is not selected")
    void aGeometryLessLeafIsNotSelected() {
        YSMRuntimeModel.BoneRt[] bones = {
                AcceptanceSupport.bone("Root", JOINT_TORSO, -1, true),      // 0
                AcceptanceSupport.bone("HairRoot", JOINT_TORSO, 0, true),   // 1  mapped: Epic Fight's
                AcceptanceSupport.bone("HairTip", JOINT_TORSO, 1, false)}; // 2  no geometry at all
        IntPredicate carriesGeometry = index -> index == 1;

        List<YsmPhysicsChains.Chain> chains = AcceptanceSupport.chains(bones, carriesGeometry);
        assertFalse(chains.stream().anyMatch(c -> c.boneName().equals("HairTip")),
                "HairTip holds no geometry and has no descendants, so there is nothing for it to"
                        + " swing; classified chains were " + AcceptanceSupport.chainNames(chains));

        int[] dropped = {0};
        List<Integer> selected = AcceptanceSupport.selectBones(bones, carriesGeometry,
                Integer.MAX_VALUE, dropped);
        assertFalse(selected.contains(2),
                "HairTip must not spend part of the bone budget; selected were "
                        + AcceptanceSupport.names(bones, selected));
        assertEquals(0, dropped[0], "nothing should have to be dropped from a two-bone model");
    }

    /**
     * A container whose geometry lives on its child is not swung in the child's place.
     *
     * <p>This is the other half of the same rule and the one the classifier already
     * documents: swinging the container rotates a subtree whose geometry is somewhere
     * else, and the strands underneath - the ones that are actually drawn - then have no
     * lever of their own. The assertion that the child <i>is</i> selected is the part that
     * matters most, because a fix that simply rejects both would leave the hair rigid.
     */
    @Test
    @DisplayName("a container is skipped and its geometric child is swung instead")
    void theGeometricChildIsSwungNotItsContainer() {
        YSMRuntimeModel.BoneRt[] bones = {
                AcceptanceSupport.bone("Root", JOINT_TORSO, -1, true),      // 0
                AcceptanceSupport.bone("LongHair", JOINT_TORSO, 0, false),  // 1  container, no geometry
                AcceptanceSupport.bone("LongHair1", JOINT_TORSO, 1, false)}; // 2  the drawn strand
        IntPredicate carriesGeometry = index -> index == 2;

        List<YsmPhysicsChains.Chain> chains = AcceptanceSupport.chains(bones, carriesGeometry);
        assertFalse(chains.stream().anyMatch(c -> c.boneName().equals("LongHair")),
                "LongHair holds no geometry while its child does; chains were "
                        + AcceptanceSupport.chainNames(chains));
        assertTrue(chains.stream().anyMatch(c -> c.boneName().equals("LongHair1")),
                "the strand that is actually drawn must be the one swung; chains were "
                        + AcceptanceSupport.chainNames(chains));

        List<Integer> selected = AcceptanceSupport.selectBones(bones, carriesGeometry,
                Integer.MAX_VALUE, new int[1]);
        assertTrue(selected.contains(2),
                "the drawn strand must be simulated; selected were "
                        + AcceptanceSupport.names(bones, selected));
    }

    /**
     * The rule must not over-reach: a genuine hanging chain is still selected, whole.
     *
     * <p>Every fix in this area is one predicate away from rejecting everything, which is
     * how the feature first shipped and moved nothing at all. A three-bone ponytail whose
     * geometry is on the bones themselves is the control case.
     */
    @Test
    @DisplayName("a genuine hanging chain with its own geometry is still fully selected")
    void aGenuineChainSurvivesTheRule() {
        YSMRuntimeModel.BoneRt[] bones = {
                AcceptanceSupport.bone("Root", JOINT_TORSO, -1, true),     // 0
                AcceptanceSupport.bone("BackHair", JOINT_TORSO, 0, false), // 1
                AcceptanceSupport.bone("BackHair2", JOINT_TORSO, 1, false),// 2
                AcceptanceSupport.bone("BackHair3", JOINT_TORSO, 2, false)};// 3
        IntPredicate carriesGeometry = index -> index >= 1;

        List<Integer> selected = AcceptanceSupport.selectBones(bones, carriesGeometry,
                Integer.MAX_VALUE, new int[1]);
        for (int index = 1; index <= 3; index++) {
            assertTrue(selected.contains(index),
                    bones[index].name + " is drawn and hangs; it must be simulated. Selected were "
                            + AcceptanceSupport.names(bones, selected));
        }
    }

    /**
     * The cap drops whole pieces and counts what it dropped.
     *
     * <p>A piece cut in half is a panel whose top swings and whose hem does not, which tears
     * at the seam; the number is what the warning in the log is made of, so a cap that bites
     * silently is a defect even when the selection itself is right.
     *
     * <p>The pieces are asserted as sets - {@code RB/RB2/RB3} and {@code LM/LM2/LM3} - rather
     * than as a bone count, because the property is "a piece is in or out", not "five bones".
     */
    @Test
    @DisplayName("the cap drops whole pieces, counts them, and never splits one")
    void theCapDropsWholePiecesAndCountsThem() {
        YSMRuntimeModel.BoneRt[] bones = maidSkirt();
        IntPredicate carriesGeometry = index -> index != 7 && index != 8;
        int[] dropped = {0};

        List<Integer> selected = AcceptanceSupport.selectBones(bones, carriesGeometry, 5, dropped);

        assertTrue(selected.size() <= 5,
                "the cap must hold: " + selected.size() + " bones selected");
        assertTrue(dropped[0] > 0,
                "a cap of 5 over two three-bone panels must report what it left out");
        assertAllOrNothing(bones, selected, new int[]{0, 1, 2}, "RB/RB2/RB3");
        assertAllOrNothing(bones, selected, new int[]{3, 4, 5}, "LM/LM2/LM3");
    }

    private static void assertAllOrNothing(YSMRuntimeModel.BoneRt[] bones, List<Integer> selected,
                                           int[] piece, String label) {
        int inside = 0;
        for (int index : piece) {
            if (selected.contains(index)) {
                inside++;
            }
        }
        assertTrue(inside == 0 || inside == piece.length,
                "the piece " + label + " is split by the cap: " + inside + " of " + piece.length
                        + " bones are simulated. Selected were " + AcceptanceSupport.names(bones, selected));
    }

    /**
     * The full bibliography of a real model is still selected when the cap is off.
     *
     * <p>Without this, {@code theCapDropsWholePiecesAndCountsThem} could be satisfied by a
     * classifier that returns nothing at all.
     */
    @Test
    @DisplayName("with the cap off every panel of the maid fixture is selected")
    void withoutTheCapNothingIsDropped() {
        YSMRuntimeModel.BoneRt[] bones = maidSkirt();
        IntPredicate carriesGeometry = index -> index != 7 && index != 8;
        int[] dropped = {0};

        List<Integer> selected = AcceptanceSupport.selectBones(bones, carriesGeometry,
                Integer.MAX_VALUE, dropped);

        assertEquals(0, dropped[0], "nothing may be dropped when there is no cap");
        for (int index = 0; index <= 5; index++) {
            assertTrue(selected.contains(index),
                    bones[index].name + " must be simulated; selected were "
                            + AcceptanceSupport.names(bones, selected));
        }
    }

    /**
     * The maid's skirt, in the shape the implementation tests use - reproduced here rather
     * than shared, so a change to the implementation's fixture cannot change what this
     * suite measures.
     *
     * <pre>
     *   0 RB3 &lt;- 1 RB2 &lt;- 2 RB &lt;- 7 RightClothe &lt;- 6 Root [mapped]
     *   3 LM  &lt;- 4 LM2 &lt;- 5 LM3 &lt;- 8 LeftClothe  &lt;- 6 Root [mapped]
     * </pre>
     */
    private static YSMRuntimeModel.BoneRt[] maidSkirt() {
        List<YSMRuntimeModel.BoneRt> bones = new ArrayList<>();
        bones.add(AcceptanceSupport.bone("RB3", JOINT_TORSO, 1, false));          // 0
        bones.add(AcceptanceSupport.bone("RB2", JOINT_TORSO, 2, false));          // 1
        bones.add(AcceptanceSupport.bone("RB", JOINT_TORSO, 7, false));           // 2
        bones.add(AcceptanceSupport.bone("LM", JOINT_TORSO, 8, false));           // 3
        bones.add(AcceptanceSupport.bone("LM2", JOINT_TORSO, 3, false));          // 4
        bones.add(AcceptanceSupport.bone("LM3", JOINT_TORSO, 4, false));          // 5
        bones.add(AcceptanceSupport.bone("Root", JOINT_TORSO, -1, true));         // 6
        bones.add(AcceptanceSupport.bone("RightClothe", JOINT_TORSO, 6, false));  // 7
        bones.add(AcceptanceSupport.bone("LeftClothe", JOINT_TORSO, 6, false));   // 8
        return bones.toArray(new YSMRuntimeModel.BoneRt[0]);
    }
}
