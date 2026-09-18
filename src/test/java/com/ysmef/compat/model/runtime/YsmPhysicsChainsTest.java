package com.ysmef.compat.model.runtime;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which bones the secondary-motion simulation is allowed to swing.
 *
 * <p>This is the part of the feature that fails dangerously rather than visibly. A
 * wrongly accepted bone does not look like a physics bug - it looks like the model
 * tearing apart, because the "chain" that swings is a body part: the swing is applied
 * to a joint's pose, so a wrapper accepted as a lock of hair drags everything parented
 * under it. The two rules that prevent that are pinned here:
 *
 * <ul>
 *   <li>a bone Epic Fight poses directly is never swung - its pose belongs to the
 *       combat animation, and a model's "hair" bone that is really its head must not
 *       move on its own;</li>
 *   <li>a bone with a mapped body joint under it is a container, not a piece of cloth,
 *       and is rejected - this is the structural equivalent of the cube-count test
 *       upstream uses.</li>
 * </ul>
 *
 * <p>Both rules are about what is <i>under</i> the candidate, and that direction is not
 * a stylistic choice. What is deliberately <b>not</b> a rejection rule is hanging under a
 * mapped bone: hair hangs off a head and a skirt hangs off a torso in every real model,
 * so a rule phrased that way rejects the entire feature while looking entirely
 * reasonable. That rule was tried here, and the feature moved nothing.
 *
 * <p>Nor is the candidate's own resolved joint a rejection rule. {@code mapped} and
 * {@code joint} answer different questions, so a real model is full of bones that are
 * unmapped and still resolve to a trunk joint - and those are exactly the skirts and
 * sleeves worth swinging.
 */
class YsmPhysicsChainsTest {

    private static final int JOINT_ROOT = 0;
    private static final int JOINT_THIGH_R = 1;
    private static final int JOINT_TORSO = 7;
    private static final int JOINT_CHEST = 8;
    private static final int JOINT_HEAD = 9;
    private static final int JOINT_ARM_L = 16;

    /**
     * Bone names in {@link YSMRuntimeModel.BoneRt} are what an author wrote, so the
     * matching is by substring and case-insensitive: nothing guarantees "hair" is
     * spelled "Hair" or stands alone.
     */
    @Test
    void hangingPiecesArePickedUpByTheirNames() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                unmapped("Hair_L", JOINT_HEAD, 0),
                unmapped("twintail_R", JOINT_HEAD, 0),
                unmapped("RibbonArm", JOINT_ARM_L, 0),
                unmapped("RightHand", JOINT_ARM_L, 0),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertEquals(3, chains.size(), "hair, twin tail and ribbon should qualify; the hand should not");
        assertTrue(chains.stream().anyMatch(c -> c.boneName().equals("Hair_L")));
        assertTrue(chains.stream().anyMatch(c -> c.boneName().equals("twintail_R")),
                "matching is case-insensitive: authors do not capitalise consistently");
        assertTrue(chains.stream().anyMatch(c -> c.boneName().equals("RibbonArm")));
        assertFalse(chains.stream().anyMatch(c -> c.boneName().equals("RightHand")),
                "a bone that does not read as hanging must be left alone");
    }

    /**
     * Cloth that has an actual leg under it is rejected, while cloth that merely hangs
     * near the legs is not - the difference the whole rule turns on.
     *
     * <p>Both cases resolve to the same trunk joint, so nothing about the candidate's own
     * joint can tell them apart; only the subtree can. A skirt panel is swung and held
     * clear of the legs by the simulation instead ({@code aroundLegs}), because rotating
     * a panel carries the panel, whereas rotating a bone with the thigh under it carries
     * the leg.
     */
    @Test
    void clothWithALegUnderItIsRejected() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                // A container: the model's leg hangs under it.
                unmapped("SkirtGroup", JOINT_TORSO, 0),
                mapped("Thigh_R", 1, 1),
                // A panel: nothing under it but cloth, at the same resolved joint.
                unmapped("SkirtPanel", JOINT_TORSO, 0),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertTrue(chains.stream().noneMatch(c -> c.boneName().equals("SkirtGroup")),
                "a bone with a mapped thigh under it would swing the leg when it swings");
        assertTrue(chains.stream().anyMatch(c -> c.boneName().equals("SkirtPanel")),
                "the same joint on a bone with only cloth under it is swung normally");
    }

    /**
     * A directly mapped bone is Epic Fight's to pose. This is the head case: a model
     * whose head bone happens to be called something containing "hair" must still be
     * left to the combat animation.
     */
    @Test
    void directlyMappedBonesAreNeverSwung() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                mapped("HairHead", JOINT_HEAD, 0),
        };

        assertTrue(YsmPhysicsChains.build(bones).isEmpty(),
                "a mapped bone's pose belongs to Epic Fight's animation");
    }

    /**
     * A bone that is <i>unmapped</i> yet resolves to a trunk joint through an unmapped
     * parent is swung, and this is the case that decides whether the feature works at
     * all on real models.
     *
     * <p>{@code mapped} is the bone's own name appearing in the mapping table, while
     * {@code joint} is resolved by walking up to the nearest named ancestor, so the two
     * disagree all the time: the wine fox's {@code Skirt} is unmapped and resolves to the
     * torso through {@code qunzi}, and hair resolves to the head or the root through a
     * chain of helper bones. Reading that joint as "this bone drives the torso" left
     * three of eighteen real cached models with a single swingable bone. What a swing
     * carries is the subtree, and this one carries nothing but cloth.
     */
    @Test
    void unmappedClothResolvingToATrunkJointIsStillSwung() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                // Unmapped: its name is not in the mapping table, so Epic Fight does not
                // pose it. It resolves to the torso only because its parent does.
                unmapped("Skirt", JOINT_TORSO, 0),
                // A segment of that same piece, so it rides along rather than swinging
                // again on top of its parent.
                unmapped("BackSkirt", JOINT_TORSO, 1),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertEquals(1, chains.size(), "the piece is one swinging object, not one per segment");
        assertEquals("Skirt", chains.get(0).boneName(),
                "the subtree carries cloth and nothing else, so swinging its top moves cloth");
    }

    /**
     * The wrapper case, and the reason it is worth a test of its own: accepting this
     * bone would swing the leg, not the cloth, because the mapped thigh hangs
     * <i>under</i> it and the swing is applied to everything below.
     *
     * <p>The trunk is what must be under it. A mapped <i>head</i> underneath is not this
     * case at all - that is ordinary hair hanging off a head, and the classifier accepts
     * it, which is the other half of this rule.
     */
    @Test
    void aContainerForMappedBodyJointsIsRejected() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                // Reads as cloth, but the model's actual leg hangs under it.
                unmapped("SkirtGroup", JOINT_TORSO, 0),
                mapped("Thigh_R", JOINT_THIGH_R, 1),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertTrue(chains.stream().noneMatch(c -> c.boneName().equals("SkirtGroup")),
                "a bone containing a mapped body joint is a container; swinging it would move that body part");
    }

    /**
     * The regression this classifier first shipped with: testing the ancestors instead
     * of the subtree. Hair hangs off a head and a skirt hangs off a torso in every real
     * model, so a rule phrased that way rejects every candidate there is and the feature
     * moves nothing - which is exactly what happened.
     */
    @Test
    void hairHangingOffAMappedHeadIsAccepted() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                mapped("Head", JOINT_HEAD, 0),
                unmapped("Hair_L", JOINT_HEAD, 1),
                unmapped("Hair_R", JOINT_HEAD, 1),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertEquals(2, chains.size(),
                "a mapped head is above the hair, not under it: that is the normal case, not a wrapper");
    }

    /**
     * A strand may be named individually while only its container reads as hanging, and
     * the two rules that meet here are worth separating: the hint makes the strand
     * <i>eligible</i>, and being part of an already-swinging piece is what keeps it out.
     *
     * <p>An author names the container ("Hair_L") and numbers the strands ("Strand03"),
     * so a strand is only ever recognised through an ancestor. Once recognised it is
     * still not a chain of its own: a ponytail is one swinging object, and swinging every
     * segment as well would multiply each segment's motion by the one above it.
     */
    @Test
    void aStrandIsRecognisedThroughItsContainerButDoesNotSwingSeparately() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                mapped("Head", JOINT_HEAD, 0),
                unmapped("Hair_L", JOINT_HEAD, 1),
                unmapped("Strand03", JOINT_HEAD, 2),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertEquals(1, chains.size(), "one piece of hair hangs off the head, so one chain swings it");
        YsmPhysicsChains.Chain hair = chains.get(0);
        assertEquals("Hair_L", hair.boneName(), "and the chain is the top of the piece");
        assertTrue(hair.chainRoot(), "Hair_L is the topmost hanging bone, so it is the chain root");
        assertFalse(YsmPhysicsChains.isChainRoot(bones, 3),
                "Strand03 of the same piece is not the top of its chain, so it is held less firmly");
    }

    /**
     * {@code chainRoot} is about position in the chain, not about names, and that
     * separation is what lets the simulation hold a hairdo firm while still swinging it.
     */
    @Test
    void theTopmostHangingBoneIsMarkedAsTheChainRoot() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                mapped("Head", JOINT_HEAD, 0),
                unmapped("Hair_L", JOINT_HEAD, 1),
                unmapped("Strand03", JOINT_HEAD, 2),
                // Named nothing like hair, so it is not a candidate at all.
                unmapped("Lock1", JOINT_HEAD, 1),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertEquals(1, chains.size(), "only Hair_L is a candidate; Strand03 rides along with it");
        YsmPhysicsChains.Chain hair = chains.get(0);
        assertEquals("Hair_L", hair.boneName(), "the piece is swung from its top");

        // The name test decides whether a bone is swung; the root test decides how
        // firmly. Conflating them is how a classifier ends up with no candidates.
        assertTrue(YsmPhysicsChains.isChainRoot(bones, 4),
                "a bone with nothing hanging above it is the top of its chain, whatever it is called");
        assertFalse(YsmPhysicsChains.isChainRoot(bones, 3),
                "and a strand under a hanging bone is not the top of its chain, even though it is swung with the piece");
        assertFalse(hair.aroundLegs(), "hair around the legs? No: only pieces off the torso are held off them");
    }

    /**
     * {@code aroundLegs} is what the simulation uses to hold a piece off the legs while
     * allowing it a larger swing, so it is keyed on hanging from the torso - and hair,
     * which is the case that survives the classifier, must not be flagged.
     */
    @Test
    void aroundLegsIsKeyedOnHangingFromTheTorso() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                unmapped("CapeShoulders", JOINT_HEAD, 0),
        };

        YsmPhysicsChains.Chain cape = YsmPhysicsChains.build(bones).stream().findFirst().orElseThrow();

        assertTrue(cape.aroundLegs(), "a piece hanging off the torso sits in the leg region");
    }

    /**
     * The names real models gave the bones that must never swing, in the shapes they gave
     * them: emissive overlays, locators, solver bones and unnamed exporter output.
     *
     * <p>Every one of these was taken from a cached model on the test instance after the
     * classifier accepted it, which is the only reason they are known - a model cannot be
     * guessed at from the vocabulary of hair.
     */
    @Test
    void namesThatOnlyLookLikeHangingClothAreRejected() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                mapped("Head", JOINT_HEAD, 0),
                unmapped("Hair", JOINT_HEAD, 1),
                // Emissive overlays drawn on top of a glowing piece: a second copy of the
                // hair that would move differently from the hair.
                unmapped("ysmGlow_Water_SillyHair", JOINT_HEAD, 2),
                // Carries no geometry; other mods read its position to attach an item.
                unmapped("RightWaistLocator", JOINT_TORSO, 1),
                // Unnamed joint from the exporter, emitted in groups under a garment.
                unmapped("bone70", JOINT_HEAD, 2),
                // Solver bone: its transform is an input to something else's maths.
                unmapped("Tail_1_RightBone", JOINT_HEAD, 2),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertEquals(1, chains.size(), "only the real hair swings");
        assertEquals("Hair", chains.get(0).boneName());
        assertFalse(YsmPhysicsChains.isVetoed("Strand03"),
                "the vetoes are substrings, so they must not catch an innocent name that merely contains one");
    }

    /**
     * A bone whose parent chain never reaches a mapped bone has no stable frame to
     * swing in, and a table with no mapped ancestor at all must not produce chains
     * rather than producing broken ones.
     */
    @Test
    void aPieceWithNoMappedAncestorIsRejected() {
        YSMRuntimeModel.BoneRt[] bones = {
                unmapped("Hair", JOINT_HEAD, -1),
        };

        assertTrue(YsmPhysicsChains.build(bones).isEmpty(),
                "without a mapped ancestor there is no frame to hang the swing from");
    }

    /** Model data is untrusted: a cyclic parent table must not hang the classifier. */
    @Test
    void aCyclicParentTableDoesNotHangTheClassifier() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                unmapped("HairA", JOINT_HEAD, 2),
                unmapped("HairB", JOINT_HEAD, 1),
        };

        // Must terminate; what it returns is less important than not looping forever.
        YsmPhysicsChains.build(bones);
    }

    @Test
    void nullAndEmptyInputsProduceNoChains() {
        assertTrue(YsmPhysicsChains.build((YSMRuntimeModel.BoneRt[]) null).isEmpty());
        assertTrue(YsmPhysicsChains.build(new YSMRuntimeModel.BoneRt[0]).isEmpty());
    }

    /**
     * A bone that draws nothing is not a chain, however much its name reads like hair.
     *
     * <p>Nothing above it is mapped, so none of the other rules apply and this is the rule on
     * its own: a swing is a rotation about the bone's own pivot, the only thing it can move is
     * the geometry drawn at this bone's part, and a bone with no such geometry has no lever -
     * not a short one, none. The shape with a mapped container in between is asserted through the
     * selection instead ({@code YsmPhysicsSelectionTest}), because that is where the container
     * being Epic Fight's own leaves the hint behind.
     */
    @Test
    void aBoneThatDrawsNothingIsNotAChain() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                unmapped("Hair_L", JOINT_HEAD, 0),      // 1  the piece that is drawn
                unmapped("HairTip", JOINT_HEAD, 1),     // 2  no geometry of its own, none under it
                unmapped("HairTail", JOINT_HEAD, 1),    // 3  the same, named after a different hint
        };
        IntPredicate drawnOnHairOnly = index -> index == 1;

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones, drawnOnHairOnly);

        assertTrue(chains.stream().noneMatch(c -> c.boneName().equals("HairTip")),
                "HairTip draws nothing and holds nothing, so there is nothing for it to swing: "
                        + names(chains));
        assertTrue(chains.stream().noneMatch(c -> c.boneName().equals("HairTail")),
                "a second hint in the name does not conjure geometry: " + names(chains));
        assertEquals(1, chains.size(), "the bone that is drawn is the chain: " + names(chains));
        assertEquals("Hair_L", chains.get(0).boneName());
    }

    /**
     * A container whose geometry is its child's is not swung in the child's place, and the child
     * still is - the half of the rule that keeps a fix from turning the hair rigid.
     *
     * <p>Both halves are asserted together because either one alone is cheap to satisfy: refusing
     * every container-shaped bone leaves the strands immobile, and accepting the container swings
     * a subtree whose geometry is somewhere else.
     */
    @Test
    void aContainerWhoseGeometryIsItsChildsIsNotAChain() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                unmapped("LongHair", JOINT_TORSO, 0),   // 1  the name reads as hanging, the mesh does not
                unmapped("LongHair2", JOINT_TORSO, 1),  // 2  where the vertices are
        };
        IntPredicate drawnOnTheChild = index -> index == 2;

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones, drawnOnTheChild);

        assertTrue(chains.stream().noneMatch(c -> c.boneName().equals("LongHair")),
                "the container draws nothing; swinging it moves a piece whose geometry is one bone"
                        + " down: " + names(chains));
        assertEquals(1, chains.size(), names(chains));
        assertEquals("LongHair2", chains.get(0).boneName(),
                "the bone the vertices are on is the one to swing");
    }

    /**
     * The control: a piece whose geometry is on its own bones is classified exactly as before.
     *
     * <p>Every rejection rule in this class is one predicate away from rejecting the whole
     * feature - that is how it first shipped - so the rule is pinned against a chain that must
     * survive it, in the shape real models use.
     */
    @Test
    void aChainHoldingItsOwnGeometryIsStillAccepted() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                unmapped("BackHair", JOINT_TORSO, 0),
                unmapped("BackHair2", JOINT_TORSO, 1),
                unmapped("BackHair3", JOINT_TORSO, 2),
        };
        IntPredicate everyBoneIsDrawn = index -> index >= 1;

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones, everyBoneIsDrawn);

        assertEquals(1, chains.size(),
                "a three-bone ponytail is one swinging object, and it is still one chain: "
                        + names(chains));
        assertEquals("BackHair", chains.get(0).boneName());
    }

    /**
     * Without a mesh to ask, the classifier keeps its old answer - deliberately, and this pins the
     * asymmetry rather than leaving it to be rediscovered.
     *
     * <p>{@link YsmPhysicsChains#build(YSMRuntimeModel)} is the animator's path and has no
     * {@code carriesGeometry} at classification time, so it cannot tell a bone that draws nothing
     * from one that draws the whole hairdo. The geometry rule applies where the information
     * exists; inventing a default here would silently drop pieces on that path instead.
     */
    @Test
    void withoutAGeometryPredicateTheOldAnswerStands() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                unmapped("Hair_L", JOINT_HEAD, 0),
                unmapped("HairTip", JOINT_HEAD, 1),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertEquals(1, chains.size(), "no mesh is not the same as no geometry: " + names(chains));
        assertEquals("Hair_L", chains.get(0).boneName());
    }

    private static String names(List<YsmPhysicsChains.Chain> chains) {
        StringBuilder builder = new StringBuilder();
        for (YsmPhysicsChains.Chain chain : chains) {
            builder.append(builder.length() == 0 ? "" : ", ").append(chain.boneName());
        }
        return "classified chains were [" + builder + "]";
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

    // ------------------------------------------------------------------
    // The frame path's chain composition
    // ------------------------------------------------------------------

    /** Seconds per frame at the 8-15 ms the production log reported. */
    private static final float FRAME = 0.010F;

    /** The turn scratch the frame loop reads: a body standing still. */
    private static final float[] NO_TURN = new float[2];

    /**
     * A chain's composed swing grows <b>linearly</b> with depth, and every joint's own pivot is
     * used on the way.
     *
     * <h2>The defect this pins</h2>
     *
     * <p>The frame path composed a child under its parent with
     * {@code delta.set(jomlDeltas[parent]).mul(delta)}. JOML's {@code set(m)} copies {@code m} into
     * {@code this} and {@code mul(r)} computes {@code this x r}, so {@code this} and {@code r} were
     * the <b>same object</b>: {@code set} overwrote the child's own rotation with the parent's
     * before {@code mul} read it, and the product came out as the parent multiplied by itself. Every
     * joint of a chain then carried the <i>root's</i> rotation squared again per level - {@code 2^n}
     * times the root angle, wrapped into 180 - and no joint below the root used its own pivot at
     * all. Measured on the shipped wine fox tail this is exactly what the production log printed, to
     * the tenth of a degree:
     *
     * <pre>
     *   n   raw = 2^n * 15.6   folded into [0,180]   logged "whole"
     *   0        15.6                15.6               15.6
     *   1        31.2                31.2               31.2
     *   2        62.4                62.4               62.4
     *   3       124.8               124.8              124.8
     *   4       249.6               110.4              110.4
     *   5       499.2               139.2              139.2
     *   6       998.4                81.6               81.6
     * </pre>
     *
     * <p>Seven of seven, with no free parameter: that is the root angle, the count and the wrap, and
     * nothing else. The last bone reported 81.6 degrees while pointing most of the way round, which
     * is why the in-game report was "the tail folds onto the lower body".
     *
     * <h2>What the assertion is, and why this shape</h2>
     *
     * <p>The test builds a real seven-link chain whose links all swing by the <i>same</i> rotation -
     * they are given an identical rest offset from an identical pivot, so the solver produces one
     * angle and repeats it - and then asserts the composition directly:
     *
     * <pre>
     *   delta(n) == R^n,   so the composed angle is n x the link's own
     * </pre>
     *
     * <p>Written as that identity rather than as a list of literal degrees because the literal list
     * depends on the solver's tuning: the angles are whatever the physics produces, and re-tuning
     * the spring would break a hardcoded table while proving nothing. The identity is what the
     * composition has to satisfy, it is exact, and it is the property the alias bug violates - under
     * the bug {@code delta(n)} is {@code R^(2^n)}, which is not {@code R^n} for any {@code n >= 1}.
     * The numbers above are pinned as the <i>observable</i> in the comment and reproduced end to end
     * by {@code T8_PanelGapProbeTest} and {@code tmp_verify/T8_aliasing.md}.
     */
    @Test
    void aChainsCompositionGrowsLinearlyWithDepthAndKeepsEveryJointsPivot() {
        int links = 7;
        float lever = 0.2F;
        // Every link the same shape, which is what makes "the same rotation" a fact rather than an
        // assumption: the same lever, the same rest direction, the same spring. The rest direction
        // is off the vertical so the airflow has something to turn, and the link's own pivot is on
        // the chain's own line so the composed pivot is the joint's, not the root's.
        Vector3f rest = new Vector3f(0.26F, -0.97F, 0.0F).normalize().mul(lever);
        YsmPhysicsParts.Segment[] segments = new YsmPhysicsParts.Segment[links];
        for (int i = 0; i < links; i++) {
            Vector3f pivot = new Vector3f(0.0F, 1.0F - 0.2F * i, 0.0F);
            segments[i] = new YsmPhysicsParts.Segment(0, "Tail" + (i + 1), 7, pivot, rest, lever,
                    0.03F, 1.0F, 2.36F, 0.5F, 1.047F, i - 1, new int[]{0}, true, new int[0]);
        }
        YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.AUTHORED, 0);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(model, null, 1.047F);
        YsmMeshSecondaryMotion.PoseSource pose = identityPose();
        // The body walking along -Z, which is the airflow that turns the links; the pivot is held
        // still, so there is no pivot acceleration and the only force is the drag.
        Vector3f bodyFlow = new Vector3f(0.0F, 0.0F, -3.0F);
        for (int frame = 0; frame <= 120; frame++) {
            YsmMeshSecondaryMotion.simulate(state, pose, FRAME, bodyFlow, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
        }

        float base = state.chainAngle[0];
        assertTrue(base > 0.02F,
                "the links have to swing for this test to measure anything; the root settled at "
                        + Math.toDegrees(base) + " degrees");
        // "All the links swing equally" is asserted rather than assumed: the law below composes one
        // rotation with itself, and that is only the right thing to do if there is one.
        for (int n = 1; n < links; n++) {
            assertEquals(Math.toDegrees(state.lastDegrees[0]), Math.toDegrees(state.lastDegrees[n]),
                    0.5F, "link " + n + " must swing by the same angle as the root for the"
                            + " composition law to be about one rotation; it swung by "
                            + state.lastDegrees[n] + " against " + state.lastDegrees[0]);
        }

        // The observable, computed independently of the frame loop: the composed delta of link n
        // must be this link's own rotation applied n+1 times, so its angle is that of the product
        // of n+1 copies of one rotation. Built from the root's own swing axis and angle and
        // composed with JOML's quaternion product, so it shares no arithmetic with the matrix
        // chain under test.
        //
        // For the angles involved this is very nearly (n+1) x the link's own swing - 17.3, 34.6,
        // 51.9 degrees where the exact product gives 17.29, 34.57, 51.86 - and the linear form is
        // asserted beside it, because "a chain of n joints bends by n times as much" is the
        // statement a reader is checking the code for. The two part company only as the chain
        // approaches half a turn, which is why the exact product is the oracle and the linear form
        // carries a tolerance.
        org.joml.Quaternionf linkRotation = new org.joml.Quaternionf();
        YsmMeshSecondaryMotion.rotationOf(state.deltas[0], linkRotation);
        org.joml.Quaternionf reference = new org.joml.Quaternionf();
        StringBuilder trace = new StringBuilder("T8 chain composition trace\n");
        for (int n = 0; n < links; n++) {
            reference.set(linkRotation);
            for (int copy = 0; copy < n; copy++) {
                reference.mul(linkRotation);
            }
            reference.normalize();
            trace.append(String.format(Locale.ROOT,
                    "link %d own=%.3f chain=%.3f n*link=%.3f used=%.3f budget=%.3f left=%d%n",
                    n, state.lastDegrees[n], Math.toDegrees(state.chainAngle[n]),
                    Math.toDegrees((n + 1) * base), Math.toDegrees(state.chainUsed[n]),
                    Math.toDegrees(state.chainBudget[n]), state.jointsLeft[n]));
        }
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("E:/program/JAVA/epic mod suitable/tmp_verify/T8_chain_trace.txt"),
                    trace.toString());
        } catch (java.io.IOException ignored) {
            // A probe write; the assertions below are the test.
        }
        for (int n = 0; n < links; n++) {
            reference.set(linkRotation);
            for (int copy = 0; copy < n; copy++) {
                reference.mul(linkRotation);
            }
            reference.normalize();
            float expected = angleOf(reference);
            assertEquals(expected, state.chainAngle[n], 2.0E-2F,
                    "link " + n + " must report the composed angle of " + (n + 1) + " copies of this"
                            + " link's own rotation, which is " + Math.toDegrees(expected)
                            + " degrees, and reported " + Math.toDegrees(state.chainAngle[n])
                            + ". A doubling sequence is the alias bug:"
                            + " `delta.set(parent).mul(delta)` overwrites this link's own rotation"
                            + " with the parent's before the product is taken, so the product is the"
                            + " parent squared.");
            assertEquals((n + 1) * base, state.chainAngle[n],
                    0.05F + 0.02F * (n + 1) * base,
                    "link " + n + " must read as roughly " + (n + 1) + " times the link's own swing ("
                            + Math.toDegrees((n + 1) * base) + " degrees), which is what a chain's"
                            + " composed angle does to first order; the exact value is "
                            + Math.toDegrees(expected) + " degrees.");
        }
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("E:/program/JAVA/epic mod suitable/tmp_verify/T8_chain_trace.txt"),
                    trace.toString());
        } catch (java.io.IOException ignored) {
            // A probe write; the assertions below are the test.
        }

        // The literal numbers the in-game log printed on the real tail, so the shape of the defect
        // is in the test and not only in prose: 15.6, 31.2, 62.4, 124.8, 110.4, 139.2, 81.6 is the
        // doubling sequence folded into [0, 180], and the fix's own law gives a fixed step.
        assertEquals(124.8F, Math.toDegrees(foldIntoHalfTurn((float) (8.0 * Math.toRadians(15.6)))),
                0.1F, "the alias bug's fourth link folds to 124.8 degrees, which is what the log"
                        + " printed: 249.6 wrapped into [0, 180]");
        assertEquals(81.6F, Math.toDegrees(foldIntoHalfTurn((float) (64.0 * Math.toRadians(15.6)))),
                0.1F, "and its seventh folds to 81.6, the log's last number");
        assertEquals(109.2F, Math.toDegrees(foldIntoHalfTurn((float) (7.0 * Math.toRadians(15.6)))),
                0.1F, "while a chain that composes its links once each reads 109.2 degrees at the"
                        + " seventh - the same seven links, most of the way round instead of folded");
    }

    /** The rotation angle of a unit quaternion, radians, in {@code [0, pi]}. */
    private static float angleOf(org.joml.Quaternionf q) {
        float w = Math.min(1.0F, Math.abs(q.w()));
        return 2.0F * (float) Math.acos(w);
    }

    /**
     * Each level of the chain composes <b>exactly one</b> link rotation, and it is that link's own -
     * not another copy of its parent.
     *
     * <h2>What this pins that the composed-angle test cannot</h2>
     *
     * <p>{@link #aChainsCompositionGrowsLinearlyWithDepthAndKeepsEveryJointsPivot} reads the angle
     * each link's matrix ends up at. That is the observable, but an angle is one number and it can be
     * right for the wrong matrix. This test rebuilds the composition from the <i>solver's own</i>
     * output instead - the axis and angle each link reports, applied about that link's own bind pivot
     * - and asserts the matrix identity the frame path is supposed to satisfy:
     *
     * <pre>
     *   delta(n) == delta(n-1) x own(n),      own(n) = T(P_n) R(axis_n, angle_n) T(-P_n)
     * </pre>
     *
     * <p>The failure this catches is not an arithmetic slip in one place, and the shape it took is
     * worth keeping in the test: the child's own delta was copied out of its slot <i>as the argument
     * of the multiplication</i> rather than in a statement of its own -
     * {@code delta.set(parent).mul(scratch.set(slot))} - and Java evaluates the receiver chain before
     * the argument, so by the time the copy ran the slot already held the parent. The product was the
     * parent squared, every level doubled the composed angle (8.643, 17.287, 34.574, 69.148 degrees
     * where one composition per link gives 8.643, 17.287, 25.930, 34.574), and no link below the root
     * used its own pivot. Both assertions below are red under that form.
     *
     * <p>Written against the solver's reported axis and angle because they are an independent source
     * of the same rotation: the reconstruction shares no arithmetic with the matrix chain under test,
     * so agreement between them is evidence rather than a tautology. The links are given no
     * neighbours (a synthetic segment list, so {@code wireNeighbours} never ran), which is what makes
     * the reported angle the applied one - a relaxed link's direction can differ from the swing its
     * matrix was built from.
     */
    @Test
    void everyCompositionLevelAddsExactlyOneLinkRotation() {
        int links = 4;
        float lever = 0.2F;
        Vector3f rest = new Vector3f(0.26F, -0.97F, 0.0F).normalize().mul(lever);
        YsmPhysicsParts.Segment[] segments = new YsmPhysicsParts.Segment[links];
        for (int i = 0; i < links; i++) {
            segments[i] = new YsmPhysicsParts.Segment(0, "Tail" + (i + 1), 7,
                    new Vector3f(0.0F, 1.0F - 0.2F * i, 0.0F), rest, lever,
                    0.03F, 1.0F, 2.36F, 0.5F, 1.047F, i - 1, new int[]{0}, true, new int[0]);
        }
        YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.AUTHORED, 0);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(model, null, 1.047F);
        YsmMeshSecondaryMotion.PoseSource pose = identityPose();
        Vector3f flow = new Vector3f(0.0F, 0.0F, -3.0F);
        for (int frame = 0; frame <= 120; frame++) {
            YsmMeshSecondaryMotion.simulate(state, pose, FRAME, flow, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
        }

        assertTrue(Math.toDegrees(state.lastDegrees[0]) > 1.0F,
                "the links have to swing for this test to measure anything; the root settled at "
                        + state.lastDegrees[0] + " degrees");

        // The reconstruction: link n's own rotation, from the solver's axis and the angle the frame
        // path allowed it, about the link's own bind pivot. Under the identity pose the deformation
        // is the identity, so the model-space axis is the bind-space axis and no conjugation is
        // needed.
        Matrix4f expected = new Matrix4f();
        StringBuilder trace = new StringBuilder("T7: one composition per level\n");
        for (int n = 0; n < links; n++) {
            Vector3f pivot = segments[n].bindPivot();
            float angle = (float) Math.toRadians(state.lastDegrees[n]);
            Vector3f axis = state.lastAxis[n];
            Matrix4f own = new Matrix4f()
                    .translate(pivot.x, pivot.y, pivot.z)
                    .rotate(angle, axis.x, axis.y, axis.z)
                    .translate(-pivot.x, -pivot.y, -pivot.z);
            if (n == 0) {
                expected.set(own);
            } else {
                expected.mul(own);
            }
            trace.append(String.format(Locale.ROOT,
                    "link %d: own=%.3f deg, expected composed=%.3f, chainAngle=%.3f, delta=%.3f%n",
                    n, state.lastDegrees[n], degreesOf(expected), Math.toDegrees(state.chainAngle[n]),
                    degreesOf(state.jomlDeltas[n])));
            assertMatrixEquals(expected, state.jomlDeltas[n], 2.0E-4F,
                    "link " + n + " must be its parent's delta composed with its OWN rotation about"
                            + " its own pivot (expected a composed angle of " + degreesOf(expected)
                            + " degrees, got " + degreesOf(state.jomlDeltas[n]) + "). A composed angle"
                            + " that doubles per level is the parent composed with itself instead -"
                            + " the copy of the child's own delta taken after the slot was overwritten.");
        }

        // And the doubling signature itself, stated so a reader can see what "the parent squared"
        // means as a matrix: under it, link n would equal link n-1 multiplied by itself.
        for (int n = 1; n < links; n++) {
            Matrix4f parent = state.jomlDeltas[n - 1];
            Matrix4f parentSquared = new Matrix4f(parent).mul(parent);
            assertFalse(near(parentSquared, state.jomlDeltas[n], 2.0E-4F),
                    "link " + n + " is its parent squared (" + degreesOf(state.jomlDeltas[n])
                            + " degrees against " + degreesOf(parentSquared) + ", where composing this"
                            + " link's own " + state.lastDegrees[n] + " degrees once more gives the"
                            + " chain angle " + Math.toDegrees(state.chainAngle[n]) + " degrees)."
                            + " Each level composes one link rotation, not another copy of the parent.");
        }
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("E:/program/JAVA/epic mod suitable/tmp_verify/T7_compose_per_level.txt"),
                    trace.toString());
        } catch (java.io.IOException ignored) {
            // A probe write; the assertions above are the test.
        }
    }

    /** The rotation angle of a rigid matrix in degrees, for a failure message. */
    private static float degreesOf(Matrix4f m) {
        float trace = m.m00() + m.m11() + m.m22();
        float cosine = Math.max(-1.0F, Math.min(1.0F, (trace - 1.0F) * 0.5F));
        return (float) Math.toDegrees(Math.acos(cosine));
    }

    /** Whether two rigid matrices agree to within {@code tolerance} in every element. */
    private static boolean near(Matrix4f a, Matrix4f b, float tolerance) {
        float[] x = {a.m00(), a.m01(), a.m02(), a.m10(), a.m11(), a.m12(), a.m20(), a.m21(), a.m22(),
                a.m30(), a.m31(), a.m32()};
        float[] y = {b.m00(), b.m01(), b.m02(), b.m10(), b.m11(), b.m12(), b.m20(), b.m21(), b.m22(),
                b.m30(), b.m31(), b.m32()};
        for (int i = 0; i < x.length; i++) {
            if (Math.abs(x[i] - y[i]) > tolerance) {
                return false;
            }
        }
        return true;
    }

    private static void assertMatrixEquals(Matrix4f a, Matrix4f b, float tolerance, String message) {
        assertTrue(near(a, b, tolerance), message + " first difference at element "
                + firstDifference(a, b));
    }

    private static String firstDifference(Matrix4f a, Matrix4f b) {
        float[] x = {a.m00(), a.m01(), a.m02(), a.m10(), a.m11(), a.m12(), a.m20(), a.m21(), a.m22(),
                a.m30(), a.m31(), a.m32()};
        float[] y = {b.m00(), b.m01(), b.m02(), b.m10(), b.m11(), b.m12(), b.m20(), b.m21(), b.m22(),
                b.m30(), b.m31(), b.m32()};
        String[] names = {"m00", "m01", "m02", "m10", "m11", "m12", "m20", "m21", "m22",
                "m30", "m31", "m32"};
        for (int i = 0; i < x.length; i++) {
            if (Math.abs(x[i] - y[i]) > 1.0E-6F) {
                return names[i] + " expected " + x[i] + " but was " + y[i];
            }
        }
        return "none";
    }

    /** An angle folded into {@code [0, pi]}, the way a rigid rotation's angle reads. */
    private static float foldIntoHalfTurn(float radians) {
        double angle = Math.abs(radians) % (2.0 * Math.PI);
        if (angle > Math.PI) {
            angle = 2.0 * Math.PI - angle;
        }
        return (float) angle;
    }

    /**
     * A probe: what the frame loop's own deltas actually compose to, per link.
     *
     * <p>Written because the angle a link reports and the angle its matrix carries are two
     * different numbers (the reported one is clamped to the piece's budget), so a test that reads
     * only the reported angle cannot tell "the composition is wrong" from "the clamp is doing its
     * job". This one reads the matrices.
     */
    @Test
    void probeTheComposedDeltasOfAChain() throws java.io.IOException {
        int links = 4;
        float lever = 0.2F;
        Vector3f rest = new Vector3f(0.26F, -0.97F, 0.0F).normalize().mul(lever);
        YsmPhysicsParts.Segment[] segments = new YsmPhysicsParts.Segment[links];
        for (int i = 0; i < links; i++) {
            segments[i] = new YsmPhysicsParts.Segment(0, "Tail" + (i + 1), 7,
                    new Vector3f(0.0F, 1.0F - 0.2F * i, 0.0F), rest, lever,
                    0.03F, 1.0F, 2.36F, 0.5F, 1.047F, i - 1, new int[]{0}, true, new int[0]);
        }
        YsmPhysicsParts.Model model = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.AUTHORED, 0);
        YsmMeshSecondaryMotion.State state = new YsmMeshSecondaryMotion.State(model, null, 1.047F);
        YsmMeshSecondaryMotion.PoseSource pose = identityPose();
        Vector3f flow = new Vector3f(0.0F, 0.0F, -3.0F);
        for (int frame = 0; frame <= 120; frame++) {
            YsmMeshSecondaryMotion.simulate(state, pose, FRAME, flow, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
        }
        org.joml.Quaternionf q = new org.joml.Quaternionf();
        StringBuilder sb = new StringBuilder("probe: composed deltas, " + links + " links\n");
        // Frame by frame, because a composition that is right on frame 1 and doubles by frame 2 is a
        // different defect from one that doubles immediately.
        YsmPhysicsParts.Model probeModel = new YsmPhysicsParts.Model(
                segments, YsmPhysicsParts.Source.AUTHORED, 0);
        YsmMeshSecondaryMotion.State fresh = new YsmMeshSecondaryMotion.State(probeModel, null, 1.047F);
        for (int frame = 0; frame <= 3; frame++) {
            YsmMeshSecondaryMotion.simulate(fresh, pose, FRAME, flow, NO_TURN,
                    YsmDynamicBoneSolver.NO_COLLIDERS);
            sb.append("frame ").append(frame).append(':');
            for (int i = 0; i < links; i++) {
                YsmMeshSecondaryMotion.rotationOf(fresh.deltas[i], q);
                sb.append(String.format(Locale.ROOT, " l%d=%.3f/own%.3f",
                        i, Math.toDegrees(angleOf(q)), fresh.lastDegrees[i]));
            }
            sb.append('\n');
        }
        sb.append("final state, after 120 frames:\n");
        for (int i = 0; i < links; i++) {
            YsmMeshSecondaryMotion.rotationOf(state.deltas[i], q);
            Vector3f piv = segments[i].bindPivot();
            Vector3f held = YsmMeshSecondaryMotion.transformPoint(state.deltas[i], piv, new Vector3f());
            sb.append(String.format(Locale.ROOT,
                    "link %d: delta angle=%.3f deg, own=%.3f deg, own(raw solver)=%.3f deg,"
                            + " pivot moved %.6f, parent=%d%n",
                    i, Math.toDegrees(angleOf(q)), state.lastDegrees[i],
                    Math.toDegrees(state.states[i].lastAngle), held.distance(piv),
                    segments[i].parent()));
        }
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("E:/program/JAVA/epic mod suitable/tmp_verify/T8_delta_probe.txt"),
                sb.toString());
    }

    /** The pose as drawn, with no animation in it: every joint at the identity. */
    private static YsmMeshSecondaryMotion.PoseSource identityPose() {
        return new YsmMeshSecondaryMotion.PoseSource() {
            @Override
            public yesman.epicfight.api.utils.math.OpenMatrix4f toOriginOf(int joint) {
                return new yesman.epicfight.api.utils.math.OpenMatrix4f();
            }

            @Override
            public yesman.epicfight.api.utils.math.OpenMatrix4f poseOf(int joint) {
                return new yesman.epicfight.api.utils.math.OpenMatrix4f();
            }
        };
    }
}
