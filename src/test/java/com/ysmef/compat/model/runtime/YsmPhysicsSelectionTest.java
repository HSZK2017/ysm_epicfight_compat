package com.ysmef.compat.model.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which bones get simulated - and the two ways a garment ends up half simulated.
 *
 * <p>Both failures look the same on screen and neither one crashes or logs: some panels move and
 * the ones beside them do not, so the half that moves looks like the half that has been dragged
 * off to the side. Which half is decided by rules that are invisible in the numbers, so they are
 * pinned here instead.
 *
 * <p>The bone tables below are the maid's own shape, reduced to the bones that matter: a waist
 * container whose name reads as cloth ({@code RightClothe}, {@code LeftClothe}), the panel tops
 * under it, and the two segments below each top. Every name is one the shipped model uses.
 */
class YsmPhysicsSelectionTest {

    private static final int JOINT_TORSO = 7;

    /**
     * A panel top whose parent is named as cloth is still a panel top.
     *
     * <p>{@code isChainRoot} asks whether the bone <i>above</i> one reads as something that hangs,
     * and that question sets how firmly a piece is held - it says nothing about where pieces begin.
     * Gating the selection on it keeps precisely the pieces whose parent is named after a panel
     * ({@code RB2}, {@code LM2}) and drops the ones whose parent is named after the garment
     * ({@code RightClothe}, {@code LeftClothe}): one side of the skirt simulated and the other
     * rigid, decided by nothing but the author's spelling.
     */
    @Test
    void aPanelTopUnderAGarmentContainerIsStillSelected() {
        YSMRuntimeModel.BoneRt[] bones = maidSkirt();
        Set<String> selected = names(bones, select(bones, Integer.MAX_VALUE));

        for (String expected : new String[]{"RB", "RB2", "RB3", "LM", "LM2", "LM3"}) {
            assertTrue(selected.contains(expected),
                    expected + " hangs on the skirt and must be simulated; selected were " + selected);
        }
    }

    /**
     * And a bone is never simulated twice, however many candidates collect it.
     *
     * <p>{@code RB}, {@code RB2} and {@code RB3} are all candidates - each is asked about its own
     * parent - and each collects the piece it hangs in, so {@code RB2}'s piece contains
     * {@code RB3} and {@code RB}'s contains both. Collecting them into separate lists and
     * concatenating puts {@code RB3} in the segment list twice; both copies write their delta to the
     * same mesh part and the later write wins, and both copies spend the bone budget, which is what
     * pushes the pieces at the end of the list out of the simulation altogether.
     */
    @Test
    void aBoneIsSimulatedOnceHoweverManyPiecesContainIt() {
        YSMRuntimeModel.BoneRt[] bones = maidSkirt();
        List<Integer> selected = select(bones, Integer.MAX_VALUE);

        Set<Integer> unique = new HashSet<>(selected);
        assertEquals(selected.size(), unique.size(),
                "the same bone was selected more than once: " + names(bones, selected));
    }

    /**
     * The cap drops whole pieces and says how many, rather than cutting one in half.
     *
     * <p>A piece cut in half is a panel whose top swings and whose hem does not - it tears at the
     * seam, and nothing in the log says why. Dropping whole pieces keeps every simulated piece
     * intact, and the count is what the warning in the log is made of.
     */
    @Test
    void theCapDropsWholePiecesAndCountsThem() {
        YSMRuntimeModel.BoneRt[] bones = maidSkirt();
        int cap = 5;
        int[] dropped = {0};

        List<Integer> selected = YsmPhysicsParts.selectBones(bones, carriesGeometry(), cap, dropped);

        assertTrue(selected.size() <= cap, "the cap must hold: " + selected.size() + " bones");
        assertTrue(dropped[0] > 0, "a cap this tight must have left a piece out");
        // The left-hand panel is one piece of three bones and does not fit beside the three
        // right-hand ones, so it is out in one go - never a waistband without its hem.
        boolean left = selected.contains(3);
        assertEquals(left, selected.contains(4), "LM and LM2 hang in the same piece");
        assertEquals(left, selected.contains(5), "LM and LM3 hang in the same piece");
        assertEquals(select(bones, cap), selected, "the selection must not depend on the run");
    }

    /** A model with nothing hanging selects nothing, and does not throw. */
    @Test
    void aModelWithNothingHangingSelectsNothing() {
        YSMRuntimeModel.BoneRt[] bones = {mapped("Root", 0, -1), mapped("Body", JOINT_TORSO, 0)};
        assertTrue(select(bones, Integer.MAX_VALUE).isEmpty());
    }

    /**
     * A bracket is not a piece of cloth.
     *
     * <p>The shipped maid models each group as a bracket over its panels - {@code FM} over
     * {@code FM1} and {@code FM2} - and once every candidate was taken the log listed the brackets
     * beside the panels they hold. Both swinging is the group turned twice, which is what "the parts
     * are in the wrong place" is. The fix has to tell a bracket from the top of a chain:
     * {@code RB -> RB2 -> RB3} is one panel in three bones and all three belong.
     */
    @Test
    void aBracketOverTwoPanelsIsNotSwungItself() {
        YSMRuntimeModel.BoneRt[] bones = {
                unmapped("FM", JOINT_TORSO, 9),      // 0  bracket over the two panels below
                unmapped("FM1", JOINT_TORSO, 0),     // 1
                unmapped("FM2", JOINT_TORSO, 0),     // 2
                unmapped("FM1a", JOINT_TORSO, 1),    // 3  the panel's own second segment
                unmapped("RB", JOINT_TORSO, 9),      // 4  one panel, three bones
                unmapped("RB2", JOINT_TORSO, 4),     // 5
                unmapped("RB3", JOINT_TORSO, 5),     // 6
                mapped("Root", JOINT_TORSO, -1),     // 7
                unmapped("Clothe", JOINT_TORSO, 7),  // 8
                unmapped("FrontClothe", JOINT_TORSO, 8)}; // 9

        Set<String> selected = names(bones, select(bones, YsmPhysicsTuning.AUTO));

        assertTrue(!selected.contains("FM"),
                "FM holds two panels and must not swing as well as them; selected were " + selected);
        assertTrue(selected.contains("FM1") && selected.contains("FM2") && selected.contains("FM1a"),
                "the panels under it must still swing: " + selected);
        assertTrue(selected.contains("RB") && selected.contains("RB2") && selected.contains("RB3"),
                "a single chain is not a bracket - every bone of it belongs: " + selected);
    }

    /**
     * The automatic limit is the classification itself: every piece the model has, none dropped.
     *
     * <p>A number in a config file cannot know how many pieces a model has, and a number that is too
     * small does not degrade gracefully - whole pieces are left out, so part of a garment swings and
     * the rest is bolted to the pose. With the limit off, the answer is every piece, and the count of
     * dropped pieces stays at zero so nothing is reported as left out.
     */
    @Test
    void theAutomaticLimitKeepsEveryPieceTheModelHas() {
        YSMRuntimeModel.BoneRt[] bones = maidSkirt();
        int[] dropped = {0};

        List<Integer> selected = YsmPhysicsParts.selectBones(
                bones, carriesGeometry(), YsmPhysicsTuning.AUTO, dropped);

        assertEquals(6, selected.size(), "both panels, all three bones each: " + names(bones, selected));
        assertEquals(0, dropped[0], "nothing may be left out when the model decides the limit");
    }

    /**
     * A bone with no name of its own is not a piece of cloth.
     *
     * <p>A candidate is accepted when it <i>or an ancestor</i> reads as something that hangs, which
     * is what lets an individual strand be swung when only its container is named. The cost is that
     * a placeholder gets swept in with it: the shipped maid has a {@code bone5} under a garment
     * container, listed among the skirt's pieces in the log, and its lever is a full block - the
     * longest in the model. Swinging that moves whatever it carries like a one-block arm.
     *
     * <p>The veto has to stay narrow, so the second half of this test is the names it must not
     * touch: every real bone in this model is numbered or suffixed, and all of them belong.
     */
    @Test
    void aPlaceholderNameIsNotAPieceOfCloth() {
        YSMRuntimeModel.BoneRt[] bones = {
                unmapped("bone5", JOINT_TORSO, 7),    // 0  a placeholder under the garment
                unmapped("RB", JOINT_TORSO, 7),       // 1  a real panel
                unmapped("RB2", JOINT_TORSO, 1),      // 2
                unmapped("bone_12", JOINT_TORSO, 7),  // 3  the other common spelling
                unmapped("LM2", JOINT_TORSO, 7),      // 4  numbered, but a real name
                unmapped("FFM3_1", JOINT_TORSO, 7),   // 5  suffixed, and real
                null,                                 // 6  (the shared predicate's spare slot)
                unmapped("Clothe", JOINT_TORSO, 8),   // 7  the garment container
                mapped("Root", JOINT_TORSO, -1)};     // 8

        Set<String> selected = names(bones, select(bones, YsmPhysicsTuning.AUTO));

        assertTrue(!selected.contains("bone5"),
                "a placeholder must not be swung just because it hangs under cloth: " + selected);
        assertTrue(!selected.contains("bone_12"), "nor its underscore spelling: " + selected);
        for (String real : new String[]{"RB", "RB2", "LM2", "FFM3_1"}) {
            assertTrue(selected.contains(real),
                    real + " is a real bone name and must survive the veto: " + selected);
        }
    }

    /**
     * A bone that draws nothing must not be selected, and must not spend the bone budget either.
     *
     * <p>The two halves are one defect seen from two sides. A bone with no geometry of its own and
     * none under it produces no segment - {@code YsmPhysicsParts.buildSegment} returns null for it,
     * because there is no centroid to measure a lever to - but it is still counted in the list
     * {@code selectBones} builds, so on a model at the cap it displaces a piece that would have
     * moved. Nothing in the log says so: the segment list it prints has the bone missing, and the
     * count that was spent is not printed at all.
     *
     * <p>The shape is the one that puts a mapped container in the way: Epic Fight owns the container,
     * so the classifier skips it, and the name-hanging bone underneath then inherits the hint from a
     * bone which was never itself a chain.
     */
    @Test
    void aBoneThatDrawsNothingIsNotSelectedAndSpendsNoBudget() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Root", JOINT_TORSO, -1),        // 0
                mapped("HairRoot", JOINT_TORSO, 0),     // 1  drawn, and Epic Fight's to pose
                unmapped("HairTip", JOINT_TORSO, 1)};   // 2  nothing drawn, nothing under it
        IntPredicate drawnOnHairRoot = index -> index == 1;
        int[] dropped = {0};

        List<Integer> selected = YsmPhysicsParts.selectBones(bones, drawnOnHairRoot,
                YsmPhysicsTuning.AUTO, dropped);

        assertTrue(!selected.contains(2),
                "HairTip has nothing to swing and must not be simulated: " + names(bones, selected));
        assertEquals(0, dropped[0],
                "and nothing had to be dropped to achieve it: a bone that cannot move must not"
                        + " push a piece that can out of the simulation");
    }

    /**
     * The container is skipped and the bone the vertices are on is swung instead - asserted
     * through the selection, because "the strand is still simulated" is the half that a rule
     * phrased as "reject containers" quietly loses.
     */
    @Test
    void theContainerIsSkippedAndTheBoneThatIsDrawnIsSelected() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Root", JOINT_TORSO, -1),        // 0
                unmapped("LongHair", JOINT_TORSO, 0),   // 1  the name; not the mesh
                unmapped("LongHair2", JOINT_TORSO, 1)}; // 2  the mesh
        IntPredicate drawnOnTheChild = index -> index == 2;

        List<Integer> selected = YsmPhysicsParts.selectBones(bones, drawnOnTheChild,
                YsmPhysicsTuning.AUTO, new int[1]);

        assertTrue(!selected.contains(1),
                "LongHair draws nothing: " + names(bones, selected));
        assertTrue(selected.contains(2),
                "LongHair2 is the drawn strand and must be simulated: " + names(bones, selected));
    }

    /**
     * The shipped tail, in the shape the log shows, is still selected whole - and it is the case
     * that says where the lever test has to live.
     *
     * <p>{@code Tail -> Tail2 -> ... -> Tail7} each declare a cube of their own in the geometry
     * file, so every one of them passes the geometry rule legitimately. What is wrong with
     * {@code Tail4}..{@code Tail7} on the real model is not that they draw nothing: it is that the
     * cube each of them draws sits on its own pivot, so the lever the simulation would rotate about
     * is two to ten centimetres and three of the four rest directions come out pointing up. The
     * classifier cannot see any of that - the lever is measured in
     * {@code YsmPhysicsParts.buildSegment} from vertex positions - so this test asserts the
     * selection that is correct <i>at this seam</i>, and names the gap instead of hiding it.
     */
    @Test
    void theMaidTailIsStillSelectedWholeBecauseItsGeometryIsItsOwn() {
        YSMRuntimeModel.BoneRt[] bones = new YSMRuntimeModel.BoneRt[9];
        bones[0] = mapped("Root", JOINT_TORSO, -1);
        bones[1] = unmapped("UpBody", JOINT_TORSO, 0);
        String[] tail = {"Tail", "Tail2", "Tail3", "Tail4", "Tail5", "Tail6", "Tail7"};
        for (int i = 0; i < tail.length; i++) {
            bones[2 + i] = unmapped(tail[i], JOINT_TORSO, 1 + i);
        }
        IntPredicate everyTailBoneIsDrawn = index -> index >= 2;

        List<Integer> selected = YsmPhysicsParts.selectBones(bones, everyTailBoneIsDrawn,
                YsmPhysicsTuning.AUTO, new int[1]);

        for (String name : tail) {
            assertTrue(selected.contains(indexOf(bones, name)),
                    name + " draws its own cube and hangs, so at the classifier's seam it is a"
                            + " legitimate part; the lever is what rejects it, and the lever is not"
                            + " visible here. Selected were " + names(bones, selected));
        }
    }

    private static int indexOf(YSMRuntimeModel.BoneRt[] bones, String name) {
        for (int i = 0; i < bones.length; i++) {
            if (bones[i] != null && name.equals(bones[i].name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("no bone named " + name);
    }

    private static List<Integer> select(YSMRuntimeModel.BoneRt[] bones, int cap) {
        return YsmPhysicsParts.selectBones(bones, carriesGeometry(), cap, new int[1]);
    }

    /** Every bone in these tables carries mesh except the two garment containers. */
    private static IntPredicate carriesGeometry() {
        return index -> index != 7 && index != 8;
    }

    /**
     * The maid's skirt, in the shape that produced the report - including the detail that matters
     * most here, which is the <i>order</i> of the bone table:
     *
     * <pre>
     *   0 RB3 &lt;- 1 RB2 &lt;- 2 RB &lt;- 7 RightClothe &lt;- 6 Root [mapped]
     *   3 LM  &lt;- 4 LM2 &lt;- 5 LM3 &lt;- 8 LeftClothe  &lt;- 6 Root [mapped]
     * </pre>
     *
     * <p>The right-hand panel is listed leaf first and the left-hand one top first, exactly as the
     * shipped model lists them. That is what makes {@code RB3}, {@code RB2} and {@code RB} three
     * separate candidates, each collecting the piece it hangs in.
     */
    private static YSMRuntimeModel.BoneRt[] maidSkirt() {
        return new YSMRuntimeModel.BoneRt[]{
                unmapped("RB3", JOINT_TORSO, 1),          // 0
                unmapped("RB2", JOINT_TORSO, 2),          // 1
                unmapped("RB", JOINT_TORSO, 7),           // 2
                unmapped("LM", JOINT_TORSO, 8),           // 3
                unmapped("LM2", JOINT_TORSO, 3),          // 4
                unmapped("LM3", JOINT_TORSO, 4),          // 5
                mapped("Root", JOINT_TORSO, -1),          // 6
                unmapped("RightClothe", JOINT_TORSO, 6),  // 7
                unmapped("LeftClothe", JOINT_TORSO, 6)};  // 8
    }

    private static Set<String> names(YSMRuntimeModel.BoneRt[] bones, List<Integer> indices) {
        Set<String> names = new HashSet<>();
        for (int index : indices) {
            names.add(bones[index].name);
        }
        return names;
    }

    private static YSMRuntimeModel.BoneRt mapped(String name, int joint, int parent) {
        YSMRuntimeModel.BoneRt bone = new YSMRuntimeModel.BoneRt();
        bone.name = name;
        bone.joint = joint;
        bone.mapped = true;
        bone.parent = parent;
        return bone;
    }

    private static YSMRuntimeModel.BoneRt unmapped(String name, int joint, int parent) {
        YSMRuntimeModel.BoneRt bone = new YSMRuntimeModel.BoneRt();
        bone.name = name;
        bone.joint = joint;
        bone.mapped = false;
        bone.parent = parent;
        return bone;
    }
}
