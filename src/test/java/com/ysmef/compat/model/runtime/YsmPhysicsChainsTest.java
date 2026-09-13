package com.ysmef.compat.model.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

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
