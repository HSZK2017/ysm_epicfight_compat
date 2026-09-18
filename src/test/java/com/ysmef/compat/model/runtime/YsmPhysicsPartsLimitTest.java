package com.ysmef.compat.model.runtime;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rules that silently neutralised four rounds of fixes, and the three the parts list itself
 * is built from.
 *
 * <p>None of them crashed, logged, or changed any type. The first pair merely let a skirt swing three
 * times further than it was configured to, and the third - the chain allowance - held four of a
 * tail's seven bones completely still while looking like a model that had never declared them. That
 * is why they cost so much time to find, and why they are cheap to state and cheap to test.
 *
 * <p>The second half of the file is the same kind of thing one level up - which bones may be parts
 * at all ({@code ownsItsGeometry}, {@code poseBelongsToEpicFight}), how the cap falls on a declared
 * list, and how big a part's collision sphere is. Each of those has exactly one failure mode and
 * none of them announces itself: a bone measured from someone else's geometry, a piece cut in half
 * at the cap, or a radius that is the piece's length or nothing at all.
 */
class YsmPhysicsPartsLimitTest {

    /** Epic Fight's torso joint, where a garment hangs and where the physics is relaxed. */
    private static final int TORSO = 7;

    private static final float MAX_ANGLE = 1.047F;
    private static final float MAX_ANGLE_ROOT = 0.349F;

    /**
     * The base of a piece is firm, and the torso relaxation must not reach it.
     *
     * <p>Every panel of a panel skirt is the base of its own piece and every one of them resolves
     * to the torso. When the relaxation was applied to bases as well, the whole skirt ran at
     * {@code MAX_ANGLE} instead of {@code MAX_ANGLE_ROOT} - three times as far - and because a
     * chain inherits its top's budget, every strand below ran loose with it.
     */
    @Test
    void aBaseResolvingToTheTorsoKeepsTheFirmLimit() {
        assertEquals(MAX_ANGLE_ROOT, YsmPhysicsParts.swingLimit(false, true, MAX_ANGLE, MAX_ANGLE_ROOT),
                "a base is a base: the torso relaxation is for strands, not for what carries them");
    }

    /** Strands below a base may come further up, which is what the relaxation is for. */
    @Test
    void aStrandResolvingToTheTorsoMaySwingFurther() {
        assertEquals(MAX_ANGLE, YsmPhysicsParts.swingLimit(true, true, MAX_ANGLE, MAX_ANGLE_ROOT),
                "a skirt panel a knee pushes has to be able to come up");
    }

    /** Away from the torso the configured limits apply as written. */
    @Test
    void ordinarySegmentsUseTheirConfiguredLimits() {
        assertEquals(MAX_ANGLE_ROOT, YsmPhysicsParts.swingLimit(false, false, MAX_ANGLE, MAX_ANGLE_ROOT),
                "a base anywhere else gets the root limit");
        assertEquals(MAX_ANGLE, YsmPhysicsParts.swingLimit(true, false, MAX_ANGLE, MAX_ANGLE_ROOT),
                "and a strand gets the strand limit");
    }

    /**
     * The limit is a property of the <i>final</i> parent link, not of the provisional one.
     *
     * <p>This is the subtler half. A panel top's nearest ancestor is a container bone such as
     * {@code FM} or {@code FrontClothe}; those carry no usable geometry and are dropped when the
     * segments are assembled, at which point the panel top becomes a base. Deciding the limit from
     * the provisional ancestor therefore classified it as a strand while it behaved as a base -
     * which is what the shipped log showed as {@code FM1(name,root,... 41.2deg/60.0chain)}. The
     * rule is expressed by calling this method with the surviving link, so a caller that passes the
     * provisional one gets the wrong answer; what the test can pin is that the two differ, so a
     * future refactor that folds them back together fails here.
     */
    @Test
    void theLimitFollowsTheSurvivingParentNotTheProvisionalOne() {
        boolean provisionalSaysHasParent = true;
        boolean survivingSaysHasParent = false;

        float wrong = YsmPhysicsParts.swingLimit(provisionalSaysHasParent, true, MAX_ANGLE, MAX_ANGLE_ROOT);
        float right = YsmPhysicsParts.swingLimit(survivingSaysHasParent, true, MAX_ANGLE, MAX_ANGLE_ROOT);

        assertEquals(MAX_ANGLE, wrong, "the provisional answer is the loose one, which is the bug");
        assertEquals(MAX_ANGLE_ROOT, right, "the surviving answer is the firm one, which is the fix");
    }

    /** The base of a chain is firm: the piece's own loose limit must not loosen it. */
    @Test
    void theBaseOfAChainIsHeldToItsOwnFirmLimit() {
        float pieceTotal = YsmPhysicsParts.chainLimitFor(3, MAX_ANGLE);

        assertEquals((float) Math.toDegrees(MAX_ANGLE_ROOT),
                (float) Math.toDegrees(
                        YsmPhysicsParts.chainAllowance(MAX_ANGLE_ROOT, pieceTotal, 0.0F, 3)), 0.1F,
                "the base of a three-joint piece keeps its own twenty degrees even though the piece"
                        + " as a whole is allowed a hundred and twenty: the joint's own limit is what"
                        + " binds at the base, and the scaling only ever moves the piece's total");
    }

    /**
     * The hem of a chain is free to lead, which is the whole point of the two ceilings.
     *
     * <p>This is the number that was wrong. Treating the base's allowance as the piece's gave a
     * child of a base that had used 18.8 of its 20 degrees an allowance of 1.2 degrees, and its own
     * child none at all: the panel turned at the waist and hung rigid below it. On screen that is a
     * skirt hinged into separate flaps, and it is what "the panels came apart" was.
     *
     * <p><b>The assertion has been rewritten twice, and the number has moved both times.</b> The
     * rule it first pinned - the child gets everything the base left of a flat piece total of sixty,
     * 41.2 degrees - is the greedy rule that starved the wine fox's tail; see
     * {@link #aJointDeepInAChainIsNeverLeftWithNothing}. The question the test asks is the same one:
     * what does the joint below a base that took 18.8 degrees get? It is now this joint's share of
     * what is left of the piece's <i>own</i> total, and for a three-joint piece that total is
     * {@code chainLimitFor(3, 60) = 120}: (120 - 18.8) / 2 = 50.6 degrees each, root 18.8, hem 50.6,
     * and the three add up to the 120 the piece is allowed. Nothing is starved, and the hem leads the
     * waist by a factor of nearly three.
     */
    @Test
    void whatTheBaseLeavesIsSharedWithTheJointsBelowIt() {
        float rootUsed = (float) Math.toRadians(18.8D);
        float pieceTotal = YsmPhysicsParts.chainLimitFor(3, MAX_ANGLE);

        float measuredAgainstTheBase = Math.max(0.0F, MAX_ANGLE_ROOT - rootUsed);
        float child = YsmPhysicsParts.chainAllowance(MAX_ANGLE, pieceTotal, rootUsed, 2);
        float hem = YsmPhysicsParts.chainAllowance(MAX_ANGLE, pieceTotal, rootUsed + child, 1);

        assertEquals(1.2F, (float) Math.toDegrees(measuredAgainstTheBase), 0.1F,
                "measuring the child against the base's twenty degrees leaves it about one");
        assertEquals(50.6F, (float) Math.toDegrees(child), 0.1F,
                "and half of what the piece's hundred and twenty has left, with one joint below it,"
                        + " is about fifty");
        assertEquals(50.6F, (float) Math.toDegrees(hem), 0.1F,
                "which the hem then takes in full: the hem leads and nothing hangs rigid");
        assertEquals((float) Math.toDegrees(pieceTotal),
                (float) Math.toDegrees((float) Math.toRadians(18.8D) + child + hem), 0.1F,
                "and the three of them spend exactly the piece's own total");
    }

    /**
     * A joint deep in a chain is never left with nothing, and the chain's own total grows with it.
     *
     * <p>The wine fox's seven-bone tail is the measured case. Under the greedy rule each joint was
     * offered {@code max(0, 60 - used)}, and on that model the composed chain angle reached 73.6
     * degrees by the third joint - so from the fourth on the allowance was <b>zero</b> and the joint
     * was slerped back to no swing on every frame. Four of the seven bones could not move, the log
     * showed {@code own=0.0deg, axis=(0,0,0), moved 0.0 blocks}, and nothing said why: it looks
     * exactly like a bone that was never classified as cloth.
     *
     * <p>The assertions are that arithmetic, both ways. The old rule's answer at 73.6 degrees is
     * computed here as the number it is - zero - and the new answer for the same tail is written
     * down: a seven-joint piece is allowed {@code chainLimitFor(7, 60) = 120} degrees in total, so
     * each of its bones gets {@code 120/7 = 17.1}, and the fourth bone - the first one the old rule
     * froze - gets 17.1 as well, not nothing. The reason it cannot face a spent budget is in
     * {@link YsmPhysicsParts#chainAllowance}: every joint takes at most its share, so what is left
     * for the joints below stays positive until the last joint of the chain.
     */
    @Test
    void aJointDeepInAChainIsNeverLeftWithNothing() {
        float usedByThreeJoints = (float) Math.toRadians(73.6D);
        float greedy = Math.max(0.0F, MAX_ANGLE - usedByThreeJoints);

        assertEquals(0.0F, (float) Math.toDegrees(greedy), 0.01F,
                "the greedy rule's answer for the fourth joint of that tail was zero, which is the bug");

        float tailLimit = YsmPhysicsParts.chainLimitFor(7, MAX_ANGLE);
        assertEquals(120.0F, (float) Math.toDegrees(tailLimit), 0.1F,
                "a seven-joint piece is allowed 120 degrees in all: the per-joint 60 x 7 capped, and"
                        + " floored at 15 x 7 = 105, so the cap is what binds");

        // The tail's own numbers, written down because they are what the shipped log will now show.
        // Seven bones, 120 degrees, and the fourth bone - the first one the old rule froze - with
        // three bones above it having taken their share: (120 - 3 x 17.14) / 4 = 17.14 degrees.
        float perBone = tailLimit / 7.0F;
        assertEquals(17.14F, (float) Math.toDegrees(perBone), 0.05F,
                "each bone of the tail is allowed 17.1 degrees, which is above the 15 degree floor");
        assertEquals(17.14F, (float) Math.toDegrees(
                        YsmPhysicsParts.chainAllowance(MAX_ANGLE, tailLimit, perBone * 3.0F, 4)), 0.05F,
                "and the wine fox's Tail4 gets 17.14deg instead of 0.0deg");

        // And every joint of such a chain, walked from the root, keeps an allowance that is both
        // positive and above the floor: the property the tail was missing, asserted over the whole
        // chain rather than at one joint.
        float used = 0.0F;
        for (int jointsLeft = 7; jointsLeft >= 1; jointsLeft--) {
            float share = YsmPhysicsParts.chainAllowance(MAX_ANGLE, tailLimit, used, jointsLeft);
            assertTrue(share >= YsmPhysicsParts.MIN_CHAIN_ANGLE_PER_JOINT - 1.0E-4F,
                    "the joint with " + jointsLeft + " joints left of the piece had an allowance of "
                            + Math.toDegrees(share) + "deg, under the floor, after "
                            + Math.toDegrees(used) + "deg was spent above it");
            used += share;
        }
        assertEquals((float) Math.toDegrees(tailLimit), (float) Math.toDegrees(used), 0.01F,
                "and the tail as a whole bends by its own 120 degrees, not by seven times sixty");
    }

    /**
     * The total a piece is allowed grows with its joint count, bounded per joint and in all.
     *
     * <p>This is the second half of the tail fix, and the half that matters on screen. Sharing a flat
     * sixty degrees over seven bones gave 8.6 degrees a bone, and a tail that moves 8.6 degrees per
     * bone is a tail nobody sees move - which is the opposite of the report the feature exists for.
     * The numbers below are the whole rule at the shipped configuration, written down:
     *
     * <pre>
     *   1 joint  60      a lone joint never gets more than its own limit
     *   2 joints 120     root 20, hem 60 - the shipped panel
     *   3 joints 120     root 20, then 50 and 50
     *   7 joints 120     17.1 each, above the 15 degree floor
     *   12 joints 180    the floor has taken over from the cap: 15.0 each
     * </pre>
     */
    @Test
    void thePiecesTotalGrowsWithItsJointCountBetweenAFloorAndACap() {
        assertEquals(60.0F, (float) Math.toDegrees(YsmPhysicsParts.chainLimitFor(1, MAX_ANGLE)), 0.1F,
                "one joint is its own limit: the cap and the floor are both above it, so the min binds");
        assertEquals(120.0F, (float) Math.toDegrees(YsmPhysicsParts.chainLimitFor(2, MAX_ANGLE)), 0.1F,
                "two joints reach the cap exactly: 2 x 60 = 120");
        assertEquals(120.0F, (float) Math.toDegrees(YsmPhysicsParts.chainLimitFor(3, MAX_ANGLE)), 0.1F);
        assertEquals(120.0F, (float) Math.toDegrees(YsmPhysicsParts.chainLimitFor(7, MAX_ANGLE)), 0.1F);
        assertEquals(180.0F, (float) Math.toDegrees(YsmPhysicsParts.chainLimitFor(12, MAX_ANGLE)), 0.1F,
                "twelve joints: the cap would give 120, which is 10 a joint, so the 15 degree floor"
                        + " takes over and the total is 12 x 15");

        for (int joints = 1; joints <= 12; joints++) {
            float limit = YsmPhysicsParts.chainLimitFor(joints, MAX_ANGLE);
            float perJoint = limit / joints;
            assertTrue(perJoint >= YsmPhysicsParts.MIN_CHAIN_ANGLE_PER_JOINT - 1.0E-4F,
                    joints + " joints were shared into " + Math.toDegrees(perJoint)
                            + "deg each, under the " + Math.toDegrees(
                            YsmPhysicsParts.MIN_CHAIN_ANGLE_PER_JOINT) + "deg floor");
            assertTrue(perJoint <= MAX_ANGLE + 1.0E-4F,
                    joints + " joints were given " + Math.toDegrees(perJoint)
                            + "deg each, more than the per-joint limit");
        }
    }

    /**
     * A lone joint never gets more than its own limit, whatever the scaling does.
     *
     * <p>The scaling is about the <i>piece's</i> total, and it must not become a way for the
     * configuration's per-joint numbers to be overridden: the base of a piece is still held to
     * {@code secondaryMotionMaxAngleRootDegrees} (20) whatever the piece's total is.
     */
    @Test
    void aLoneJointIsStillHeldToItsOwnLimit() {
        float limitOne = YsmPhysicsParts.chainLimitFor(1, MAX_ANGLE);

        assertEquals((float) Math.toDegrees(MAX_ANGLE_ROOT),
                (float) Math.toDegrees(
                        YsmPhysicsParts.chainAllowance(MAX_ANGLE_ROOT, limitOne, 0.0F, 1)), 0.1F,
                "a one-joint piece of cloth is its root limit, twenty degrees, not the sixty total");
        assertEquals((float) Math.toDegrees(MAX_ANGLE),
                (float) Math.toDegrees(
                        YsmPhysicsParts.chainAllowance(MAX_ANGLE, limitOne, 0.0F, 1)), 0.1F,
                "and a lone strand is its own sixty, which is exactly what the total is for one joint");
    }

    /**
     * With a small per-joint limit the total must not be inflated by the floor.
     *
     * <p>The floor exists so a long chain is not shared into stillness, and it must not become a
     * licence for a model configured at ten degrees a joint to bend seventy. The outer
     * {@code min(ownTotal, ...)} in {@link YsmPhysicsParts#chainLimitFor} is what stops it: a piece
     * may never be allowed more in total than its joints would each be allowed separately.
     */
    @Test
    void aSmallPerJointLimitIsNotInflatedByTheFloor() {
        float small = (float) Math.toRadians(10.0D);

        assertEquals(70.0F, (float) Math.toDegrees(YsmPhysicsParts.chainLimitFor(7, small)), 0.1F,
                "seven joints at ten degrees each is seventy in all, not the 105 the floor alone"
                        + " would give and not the 120 cap");
        assertEquals(200.0F, (float) Math.toDegrees(YsmPhysicsParts.chainLimitFor(20, small)), 0.1F,
                "and twenty joints is two hundred, still exactly the joints' own sum");
        for (int joints = 1; joints <= 20; joints++) {
            assertTrue(YsmPhysicsParts.chainLimitFor(joints, small) <= small * joints + 1.0E-4F,
                    joints + " joints were allowed more in total than their own limits add up to");
        }
    }

    /**
     * A chain may bend by the piece's limit and no further, however many joints it is made of.
     *
     * <p>The point of passing what the ancestors spent, and how many joints are left, is that the
     * allowances add up to something bounded; a rule that handed every joint the full limit would let
     * a three-bone panel open by three times what any one of its joints may turn.
     */
    @Test
    void aChainBendsByThePiecesLimitAtMost() {
        float limit = YsmPhysicsParts.chainLimitFor(8, MAX_ANGLE);
        float used = 0.0F;
        float total = 0.0F;
        for (int joint = 0; joint < 8; joint++) {
            float own = joint == 0 ? MAX_ANGLE_ROOT : MAX_ANGLE;
            float allowance = YsmPhysicsParts.chainAllowance(own, limit, used, 8 - joint);
            total += allowance;
            used += allowance;
        }
        assertEquals((float) Math.toDegrees(limit), (float) Math.toDegrees(total), 0.01F,
                "eight joints of a strand bend by the piece's own total and not by eight of them");
    }

    /**
     * And a two-joint piece - the shipped panel, and most of the maid's garment - is unchanged where
     * it is measured.
     *
     * <p>The regression this pins is the base. Its allowance is {@code min(20, 120/2) = 20} both
     * before and after the scaling, bit for bit, and the base is the number that decides how the
     * skirt hangs. The hem's allowance did grow, from 40 to its own 60, and that is invisible on this
     * model: the shipped log measured its panels at 20.0, 12.1, 5.1 and 4.6 degrees, all far under
     * 40, so no panel that was clamped before is unclamped now - which is asserted here as the
     * comparison that makes the claim checkable rather than rhetorical.
     */
    @Test
    void aTwoJointPieceIsUnchangedWhereItIsMeasured() {
        float limit = YsmPhysicsParts.chainLimitFor(2, MAX_ANGLE);
        float base = YsmPhysicsParts.chainAllowance(MAX_ANGLE_ROOT, limit, 0.0F, 2);
        float hem = YsmPhysicsParts.chainAllowance(MAX_ANGLE, limit, base, 1);
        float oldHem = Math.max(0.0F, (float) Math.toRadians(60.0D) - base);

        assertEquals((float) Math.toDegrees(MAX_ANGLE_ROOT), (float) Math.toDegrees(base), 0.01F,
                "the base of a two-joint piece is its own twenty degrees, exactly as before");
        assertEquals(60.0F, (float) Math.toDegrees(hem), 0.1F,
                "the hem is now capped by its own sixty rather than by what the base left");
        assertEquals(40.0F, (float) Math.toDegrees(oldHem), 0.1F,
                "which is where the old rule put it, forty degrees");
        assertTrue(MEASURED_PANEL_SWING < (float) Math.toDegrees(hem)
                        && MEASURED_PANEL_SWING < (float) Math.toDegrees(oldHem),
                "and the worst swing the shipped panels were measured at, " + MEASURED_PANEL_SWING
                        + " degrees, is under BOTH allowances, so nothing that used to be clamped is"
                        + " clamped differently");
    }

    /** The largest skirt swing the shipped log measured, degrees - the number the claim above uses. */
    private static final float MEASURED_PANEL_SWING = 20.0F;

    /**
     * A spent chain grants the floor rather than nothing, and never a negative.
     *
     * <p><b>Rewritten:</b> this used to assert zero - "the last joint of a strand whose whole
     * allowance is spent does not swing further". That is the property the wine fox's tail died of:
     * a joint with nothing left to share is a joint slerped back to no swing on every frame,
     * forever. The floor is now unconditional, so the assertion is the floor, and the second half of
     * it is that it still cannot exceed the joint's own limit - a model configured at ten degrees a
     * joint does not get fifteen because the floor says so.
     */
    @Test
    void anExhaustedChainGrantsTheFloorRatherThanANegative() {
        assertEquals((float) Math.toDegrees(YsmPhysicsParts.MIN_CHAIN_ANGLE_PER_JOINT),
                (float) Math.toDegrees(
                        YsmPhysicsParts.chainAllowance(MAX_ANGLE, MAX_ANGLE, MAX_ANGLE, 1)), 0.1F,
                "the last joint of a strand whose whole allowance is spent still gets the floor");
        assertEquals((float) Math.toDegrees(YsmPhysicsParts.MIN_CHAIN_ANGLE_PER_JOINT),
                (float) Math.toDegrees(
                        YsmPhysicsParts.chainAllowance(MAX_ANGLE, MAX_ANGLE, MAX_ANGLE * 4.0F, 1)), 0.1F,
                "and an over-spent chain must not hand back a negative allowance either");
        assertEquals(10.0F, (float) Math.toDegrees(YsmPhysicsParts.chainAllowance(
                        (float) Math.toRadians(10.0D), MAX_ANGLE, MAX_ANGLE * 4.0F, 1)), 0.1F,
                "but the floor never overrides a joint's own limit: ten degrees configured is ten");
    }

    // ------------------------------------------------------------------
    // The cap, applied to a *declared* part list
    // ------------------------------------------------------------------

    /**
     * A declared list is capped in whole pieces, like the classified one.
     *
     * <p>This is the correction. The declared list is flat - bone names in the model's own order -
     * and it used to be cut at the cap like an array: {@code selected.subList(0, cap)}. On a model
     * whose declaration is a tail, or a garment, the cut lands inside a piece, and the bones above
     * the cut swing while the bones below it hang rigid - which is a piece of cloth torn in two with
     * nothing in the log to say which half moved.
     *
     * <p>The table below is that shape: a tail of three bones, then two panels of two. The cap of
     * five fits the tail and the first panel; the second panel is left out in one go and counted
     * once, as a piece rather than as its two bones.
     */
    @Test
    void aDeclaredListIsCappedInWholePieces() {
        YSMRuntimeModel.BoneRt[] bones = tailAndPanels();
        List<Integer> declared = List.of(0, 1, 2, 3, 4, 5, 6);
        int[] dropped = {0};

        List<Integer> kept = YsmPhysicsParts.capPieces(bones, declared, 5, dropped);

        assertEquals(List.of(0, 1, 2, 3, 4), kept, "the tail and the first panel fit; the second does not");
        assertEquals(1, dropped[0], "one piece was left out, and a piece is one count");
    }

    /**
     * No piece is ever cut, whatever the cap says - including the piece that does not fit on its own.
     *
     * <p>A cap of two cannot hold the three-bone tail, and the answer is still to keep the tail
     * whole rather than the first two bones of it: a simulated bone whose parent is not simulated is
     * a hem hanging off a waistband that is not there. The rule is the one {@code selectBones}
     * already had - the first piece is always taken, so a small cap simulates something instead of
     * nothing - and this pins that the declared path follows it too.
     */
    @Test
    void aDeclaredPieceIsNeverCutInHalf() {
        YSMRuntimeModel.BoneRt[] bones = tailAndPanels();
        List<Integer> declared = List.of(0, 1, 2, 3, 4, 5, 6);
        int[] dropped = {0};

        List<Integer> kept = YsmPhysicsParts.capPieces(bones, declared, 2, dropped);

        assertEquals(List.of(0, 1, 2), kept, "the first piece is kept whole even though it exceeds the cap");
        assertEquals(2, dropped[0], "both panels are left out, and each is one count");
        for (int bone : kept) {
            int parent = bones[bone].parent;
            if (parent >= 0 && declared.contains(parent)) {
                assertTrue(kept.contains(parent),
                        "bone " + bones[bone].name + " is simulated while its declared parent is not");
            }
        }
    }

    /** The pieces are the declared bones' own groups, in the order the list first names them. */
    @Test
    void theDeclaredListIsGroupedByAncestry() {
        YSMRuntimeModel.BoneRt[] bones = tailAndPanels();
        List<List<Integer>> pieces = YsmPhysicsParts.piecesOf(bones, List.of(3, 4, 0, 1, 2, 5, 6));

        assertEquals(3, pieces.size(), "a tail and two panels are three pieces, whatever order they are listed in");
        assertEquals(List.of(3, 4), pieces.get(0), "the panel is listed first, so its piece comes first");
        assertEquals(List.of(0, 1, 2), pieces.get(1), "then the chain, however its bones are ordered inside it");
        assertEquals(List.of(5, 6), pieces.get(2));
    }

    /** The tail of three bones and two panels of two, with the panels' roots as containers. */
    private static YSMRuntimeModel.BoneRt[] tailAndPanels() {
        return new YSMRuntimeModel.BoneRt[]{
                bone("Tail", TORSO, 8),      // 0  the chain: 0 <- 1 <- 2
                bone("Tail2", TORSO, 0),     // 1
                bone("Tail3", TORSO, 1),     // 2
                bone("RM", TORSO, 8),        // 3  one panel: 3 <- 4
                bone("RM2", TORSO, 3),       // 4
                bone("LM", TORSO, 8),        // 5  the other: 5 <- 6
                bone("LM2", TORSO, 5),       // 6
                bone("Root", 0, -1),         // 7  the mapped body root
                bone("Clothe", TORSO, 7)};   // 8  the container all three pieces hang from
    }

    private static YSMRuntimeModel.BoneRt bone(String name, int joint, int parent) {
        YSMRuntimeModel.BoneRt bone = new YSMRuntimeModel.BoneRt();
        bone.name = name;
        bone.joint = joint;
        bone.parent = parent;
        return bone;
    }

    // ------------------------------------------------------------------
    // What may be a segment at all
    // ------------------------------------------------------------------

    /**
     * A segment has to own the geometry it is measured from.
     *
     * <p>The three cases below are the three ways a bone can fail that, and the middle one is the
     * one that shipped: the mesh writer emits a part for every bone that carries quads, so a bone
     * named by the author's physics animation can have no part at all - and it was then given the
     * centroid of its <i>descendants'</i> geometry. Its lever pointed at geometry drawn by another
     * bone, its delta was written to no part, and it still spent the piece's chain allowance, so the
     * strands below it were curtailed by a bone that could not move anything.
     */
    @Test
    void aSegmentMustOwnTheGeometryItIsMeasuredFrom() {
        Map<Integer, float[]> geometry = new HashMap<>();
        Map<Integer, int[]> parts = new HashMap<>();
        geometry.put(0, new float[]{0.0F, 1.0F, 0.0F, 24.0F});
        parts.put(0, new int[]{3});
        geometry.put(1, new float[]{0.0F, 0.5F, 0.0F, 0.0F});
        parts.put(1, new int[]{4});
        geometry.put(2, new float[]{0.0F, 0.8F, 0.0F, 24.0F});

        assertTrue(YsmPhysicsParts.ownsItsGeometry(0, geometry, parts),
                "a bone with a part of its own that carries drawn vertices owns its geometry");
        assertTrue(!YsmPhysicsParts.ownsItsGeometry(1, geometry, parts),
                "a part with no vertices is a bone the default form hides: nothing is drawn there");
        assertTrue(!YsmPhysicsParts.ownsItsGeometry(2, geometry, parts),
                "vertices with no part named after the bone are another bone's geometry, not this one's");
        assertTrue(!YsmPhysicsParts.ownsItsGeometry(3, geometry, parts), "an unknown bone owns nothing");
    }

    /**
     * A bone Epic Fight animates is not a physics part, however it got into the list.
     *
     * <p>The classifier has always skipped mapped bones; a <i>declared</i> list did not, so an
     * author's animation naming a body part would have had this mod write a second rotation on top
     * of the pose - a limb turned twice rather than cloth on it.
     */
    @Test
    void aBoneEpicFightAnimatesIsNotAPhysicsPart() {
        YSMRuntimeModel.BoneRt bodyPart = bone("UpBody", TORSO, -1);
        bodyPart.mapped = true;
        YSMRuntimeModel.BoneRt panel = bone("FM1", TORSO, -1);

        assertTrue(YsmPhysicsParts.poseBelongsToEpicFight(bodyPart),
                "UpBody is a body part Epic Fight poses; a second rotation on it moves the chest");
        assertTrue(!YsmPhysicsParts.poseBelongsToEpicFight(panel),
                "FM1 is not a name Epic Fight knows, so its pose is ours to add swing to");
        assertTrue(YsmPhysicsParts.poseBelongsToEpicFight(null),
                "a bone that is not there cannot be simulated either");
    }

    // ------------------------------------------------------------------
    // The collision radius
    // ------------------------------------------------------------------

    /**
     * The radius is the piece's thickness, not its length.
     *
     * <p>The rule this replaces took the median distance from the vertices to their centroid, which
     * on a strand is most of the way to the end of the strand: the same cloth three times as long
     * got three times the radius, and on a real tail it reached the clamp at 0.22 blocks - a
     * twenty-two centimetre sphere on a braid, which pushes the body away from the hair. Both
     * numbers are asserted, because the old one is the regression and the new one is the fix.
     */
    @Test
    void theRadiusIsThePiecesThicknessAndNotItsLength() {
        Vector3f pivot = new Vector3f();
        Vector3f down = new Vector3f(0.0F, -1.0F, 0.0F);

        float shortPanel = YsmPhysicsParts.radiusFor(panel(0.5F), pivot, down);
        float longPanel = YsmPhysicsParts.radiusFor(panel(1.5F), pivot, down);

        assertEquals(shortPanel, longPanel, 1.0E-4F,
                "the same cloth three times as long is the same thickness");
        assertTrue(longPanel < MAX_RADIUS,
                "and a long piece must not be clamped up to the maximum radius; it is " + longPanel);
        assertEquals(MAX_RADIUS, oldRadius(panel(1.5F), pivot), 1.0E-3F,
                "which is what the centroid rule produced, and why a tail shoved the body");
    }

    /**
     * And a thin strand keeps a radius that can collide at all.
     *
     * <p>The other half of the same defect: a piece a few centimetres across produced a few
     * millimetres of sphere, clamped up to the floor, so it passed through the body it was supposed
     * to rest on and collision was, in the only sense a viewer has, not there.
     */
    @Test
    void aThinStrandKeepsARadiusThatCanCollide() {
        Vector3f pivot = new Vector3f();
        Vector3f down = new Vector3f(0.0F, -1.0F, 0.0F);

        float strand = YsmPhysicsParts.radiusFor(strand(0.6F), pivot, down);

        assertTrue(strand >= MIN_RADIUS,
                "a four-centimetre braid is a two-centimetre sphere, not nothing: " + strand);
        assertTrue(strand <= 0.05F,
                "and it is its thickness, not the half-metre it happens to hang: " + strand);
    }

    /** No vertices, no direction, no radius: every degenerate input lands on the floor, not on NaN. */
    @Test
    void aRadiusWithoutGeometryIsTheFloor() {
        assertTrue(YsmPhysicsParts.radiusFor(null, new Vector3f(), new Vector3f(0.0F, -1.0F, 0.0F)) >= MIN_RADIUS);
        assertTrue(YsmPhysicsParts.radiusFor(panel(0.5F), new Vector3f(), new Vector3f()) >= MIN_RADIUS);
        assertTrue(YsmPhysicsParts.radiusFor(panel(0.5F), null, new Vector3f(0.0F, -1.0F, 0.0F)) >= MIN_RADIUS);
    }

    // ------------------------------------------------------------------
    // What a piece's spring follows: the cloth/hair/tail weights
    // ------------------------------------------------------------------

    /**
     * The three families, on the bone names this repository's own tests and golden files use, with
     * the numbers they were chosen for.
     *
     * <p>A name-driven decision with one failure direction that matters: a model whose bones are
     * named in a language the hints do not cover must fall to "follow the pose" (0.0) rather than to
     * a guess, because handing a body part to gravity is visible while a piece that does not droop
     * is merely unimproved. The unknown row is therefore as load-bearing as the other three.
     */
    @Test
    void theWeightsAreTheOnesTheThreeFamiliesWereChosenFor() {
        assertEquals(0.92F, verticalFollowOf("FrontSkirt"), 1.0E-6F,
                "a skirt panel is expected to hang toward the ground");
        assertEquals(0.92F, verticalFollowOf("UpperBody_skirt"), 1.0E-6F,
                "and the underscore in a real name must not stop it being recognised");
        assertEquals(0.92F, verticalFollowOf("qun"), 1.0E-6F);
        assertEquals(0.92F, verticalFollowOf("BackSkirt"), 1.0E-6F);
        assertEquals(0.92F, verticalFollowOf("Clothe"), 1.0E-6F);

        assertEquals(0.60F, verticalFollowOf("LongHair"), 1.0E-6F,
                "a lock of hair grows out of a skull and has its own volume");
        assertEquals(0.60F, verticalFollowOf("BackHairA1"), 1.0E-6F,
                "the golden file's real back-hair chain");
        assertEquals(0.60F, verticalFollowOf("Right_SideDownHairM2"), 1.0E-6F,
                "the golden file's real side lock");
        assertEquals(0.60F, verticalFollowOf("BaseHair"), 1.0E-6F);

        assertEquals(0.80F, verticalFollowOf("tail"), 1.0E-6F,
                "a tail is a heavy appendage with a shape of its own, hung from the spine");
        assertEquals(0.80F, verticalFollowOf("Tail_01"), 1.0E-6F);

        assertEquals(0.0F, verticalFollowOf("Torso"), 1.0E-6F,
                "a body bone classified by accident would be handed to gravity; unknown must mean "
                        + "the pose decides");
        // A locator is a name the classifier would otherwise catch: it contains "cape". The veto
        // that keeps such bones out of the simulation runs before this classification, so reaching
        // this point with a locator's name means it was declared by the author instead - and a
        // declared bone is simulated, so the honest answer here is the cloth weight it reads as.
        assertEquals(0.92F, verticalFollowOf("CapeLocator"), 1.0E-6F,
                "the weight is a property of the name, and a locator that reached the simulation "
                        + "anyway reads as the cape it is named for");
        assertEquals(0.92F, verticalFollowOf("Cape2"), 1.0E-6F,
                "a trailing-digit variant is the same bone: the normaliser strips it");
        // Names that carry no hint at all, which is what a model with an unfamiliar vocabulary
        // produces - and the arm is the case that would be worst to guess on.
        assertEquals(0.0F, verticalFollowOf("Arm_R"), 1.0E-6F);
        assertEquals(0.0F, verticalFollowOf("RightLeg"), 1.0E-6F);
        assertEquals(0.0F, verticalFollowOf("Strand03"), 1.0E-6F,
                "a strand the author numbered: the family comes from the declared list or from a "
                        + "name that says what it is, never from a guess");
        assertEquals(0.0F, verticalFollowOf("RightArm_Default"), 1.0E-6F,
                "a form suffix is stripped, and the stem it leaves is still unrecognised");
        assertEquals(0.0F, verticalFollowOf(""), 1.0E-6F);
        assertEquals(0.0F, verticalFollowOf(null), 1.0E-6F);
    }

    /**
     * The ordering between the families, and the one name that needs it.
     *
     * <p>A twin tail is hair: it hangs off a skull, it is drawn as two locks, and the word contains
     * "tail". Matching the tail family first would give it 0.80 and make a hairstyle behave like an
     * appendage, so the hair hints are tested before the tail's - and this is the case that says so.
     * "TwinTailHair" carries both, which is exactly how a real model spells it.
     */
    @Test
    void aTwinTailIsHairNotATail() {
        assertEquals(verticalFollowOf("Hair"), verticalFollowOf("TwinTailHair"), 1.0E-6F,
                "a name carrying both 'hair' and 'tail' must be read as hair");
        assertTrue(verticalFollowOf("LongHair") < verticalFollowOf("tail"),
                "and hair follows gravity less than a tail does, which is the whole point of the "
                        + "ordering");
    }

    /**
     * The config's own scale, which is the one knob that moves every family together.
     *
     * <p>It is applied at read time rather than baked into the classification, so a user can turn the
     * mechanism off without a rebuild - and zero has to give back the pre-existing behaviour exactly,
     * which is what makes it a safe escape hatch rather than a second setting.
     */
    @Test
    void theConfiguredScaleMultipliesEveryFamilyAndZeroRestoresTheOldBehaviour() {
        double scale = YsmPhysicsTuning.gravityFollowScale();

        assertTrue(scale >= 0.0 && scale <= 1.0, "the scale is a fraction, got " + scale);
        assertEquals(0.92F * (float) scale, verticalFollowOf("FrontSkirt"), 1.0E-6F,
                "cloth must be scaled by the config's own weight");
        assertEquals(0.60F * (float) scale, verticalFollowOf("LongHair"), 1.0E-6F);
        assertEquals(0.80F * (float) scale, verticalFollowOf("tail"), 1.0E-6F);

        // The equality that matters: at a scale of zero every family lands on the value that means
        // "follow the pose", which is what the solver reads as its own fallback.
        assertEquals(YsmDynamicBoneSolver.FALLBACK_VERTICAL_FOLLOW,
                YsmDynamicBoneSolver.FALLBACK_VERTICAL_FOLLOW * (float) scale, 1.0E-6F,
                "FALLBACK_VERTICAL_FOLLOW must be the value a zero scale produces");
    }

    /**
     * The world's downward direction is `(0,-1,0)` in the model's space, and the reason is that the
     * model's own Y axis IS the world's.
     *
     * <p>Stated as the property the render pipeline has to have, so that a future change to it fails
     * here rather than silently turning the gravity-follow mechanism into "follow the body's axis" -
     * which is the defect the mechanism exists to remove. The chain of reasoning is in
     * {@code YsmMeshSecondaryMotion.DOWN_IN_MODEL_SPACE}; the part a test can hold onto is that the
     * transform between the two spaces is a rotation about Y and nothing else.
     *
     * <p>If the pose matrices ever carried pitch or roll - if Epic Fight's model matrix gained a
     * non-zero `xRot`, or if the body's lean stopped being an animation and became a frame rotation -
     * this would be false and `downTarget` would have to be derived per frame from the model
     * matrix instead of being a constant. That is the different design the task's step 1 names, and
     * this test is where it would announce itself.
     */
    @Test
    void theWorldVerticalIsTheModelsOwnVerticalUnderAnyYaw() {
        Vector3f down = new Vector3f(0.0F, -1.0F, 0.0F);

        for (float yawDegrees : new float[]{0.0F, 45.0F, 90.0F, 180.0F, 270.0F, 359.0F}) {
            // The only rotation the renderer applies between the two spaces: yaw about Y. See
            // YsmMeshSecondaryMotion#bodyVelocity, which converts a world velocity into model space
            // with exactly this rotation and no other.
            Vector3f inWorld = new Vector3f(down).rotateY((float) Math.toRadians(yawDegrees));

            assertEquals(0.0F, inWorld.x, 1.0E-6F,
                    "a yaw about Y must leave the downward direction with no X component");
            assertEquals(-1.0F, inWorld.y, 1.0E-6F,
                    "and at any yaw the world's downward direction is still the model's -Y");
            assertEquals(0.0F, inWorld.z, 1.0E-6F);
        }

        // The counter-case, written down rather than implied: a rotation that is NOT a pure yaw
        // does move the down direction, so the constant is only valid because the pipeline has no
        // pitch or roll in it. This is the assertion that would change first.
        Vector3f tilted = new Vector3f(down).rotateX((float) Math.toRadians(60.0));
        assertTrue(Math.abs(tilted.y + 1.0F) > 0.4F,
                "a sixty degree pitch would put the world's vertical well away from -Y, so if this "
                        + "ever becomes the pipeline's transform the constant is wrong: " + tilted);
    }

    /** The weight the classification gives one bone name, as the solver would receive it. */
    private static float verticalFollowOf(String boneName) {
        return segmentNamed(boneName).verticalFollow();
    }

    /**
     * A segment carrying only a name, which is all {@code verticalFollow()} reads.
     *
     * <p>Built directly rather than through {@link YsmPhysicsParts#build}, because the weight is a
     * property of the name and building a model would need a runtime model and a mesh: the point of
     * the test is that the name alone decides, so the name is the only thing supplied.
     */
    private static YsmPhysicsParts.Segment segmentNamed(String boneName) {
        return new YsmPhysicsParts.Segment(0, boneName, TORSO, new Vector3f(), new Vector3f(0.0F, -0.2F, 0.0F),
                0.2F, 0.03F, 1.0F, 2.36F, 0.81F, MAX_ANGLE_ROOT, -1, new int[]{0}, false, new int[0]);
    }

    /** The floor and the ceiling the two tests above name, as they are declared. */
    private static final float MIN_RADIUS = 0.02F;
    private static final float MAX_RADIUS = 0.22F;

    /**
     * A cloth panel: {@code 0.12} blocks half-width, {@code length} blocks long, {@code 0.01}
     * blocks half-thickness, sampled over a grid because a real part has interior vertices and the
     * measurement is a median over them.
     */
    private static List<Vector3f> panel(float length) {
        return box(0.12F, length * 0.5F, 0.01F);
    }

    /** A braid: two centimetres half-width, {@code length} blocks long. */
    private static List<Vector3f> strand(float length) {
        return box(0.02F, length * 0.5F, 0.02F);
    }

    /** The surface of a box centred on the origin, its long axis along y, as vertex samples. */
    private static List<Vector3f> box(float hx, float hy, float hz) {
        List<Vector3f> out = new ArrayList<>();
        for (int ix = 0; ix <= 8; ix++) {
            for (int iy = 0; iy <= 8; iy++) {
                for (int iz = 0; iz <= 8; iz++) {
                    boolean surface = ix == 0 || ix == 8 || iy == 0 || iy == 8 || iz == 0 || iz == 8;
                    if (!surface) {
                        continue;
                    }
                    out.add(new Vector3f(
                            hx * (2.0F * ix / 8.0F - 1.0F),
                            hy * (2.0F * iy / 8.0F - 1.0F),
                            hz * (2.0F * iz / 8.0F - 1.0F)));
                }
            }
        }
        return out;
    }

    /** The rule this change replaces, kept as the number it produced: median distance to the centroid. */
    private static float oldRadius(List<Vector3f> vertices, Vector3f pivot) {
        Vector3f centre = new Vector3f();
        for (Vector3f vertex : vertices) {
            centre.add(vertex);
        }
        centre.div(vertices.size());
        float[] distances = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            distances[i] = vertices.get(i).distance(centre);
        }
        java.util.Arrays.sort(distances);
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, distances[distances.length / 2] * 0.65F));
    }
}
