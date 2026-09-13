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
 *   <li>a bone whose subtree contains a directly mapped body joint is a container,
 *       not a piece of cloth, and is rejected - this is the structural equivalent of
 *       the cube-count test upstream uses.</li>
 * </ul>
 */
class YsmPhysicsChainsTest {

    private static final int JOINT_ROOT = 0;
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
                unmapped("SkirtFront", JOINT_TORSO, 0),
                unmapped("CapeL", JOINT_CHEST, 0),
                unmapped("RightHand", JOINT_ARM_L, 0),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertEquals(3, chains.size(), "hair, skirt and cape should qualify; the hand should not");
        assertTrue(chains.stream().anyMatch(c -> c.boneName().equals("Hair_L")));
        assertTrue(chains.stream().anyMatch(c -> c.boneName().equals("SkirtFront")));
        assertTrue(chains.stream().anyMatch(c -> c.boneName().equals("CapeL")));
        assertFalse(chains.stream().anyMatch(c -> c.boneName().equals("RightHand")),
                "a bone that does not read as hanging must be left alone");
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
     * The wrapper case, and the reason it is worth a test of its own: accepting this
     * bone would swing the head, not the hair, because the swing is written to the
     * joint the bone contributes to.
     */
    @Test
    void aContainerForMappedBodyJointsIsRejected() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                // Named like cloth, but the model's actual head hangs under it.
                unmapped("HairGroup", JOINT_TORSO, 0),
                mapped("Head", JOINT_HEAD, 1),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertTrue(chains.stream().noneMatch(c -> c.boneName().equals("HairGroup")),
                "a bone containing a mapped body joint is a container; swinging it would move that body part");
    }

    /** A strand may be named individually while only its container reads as hanging. */
    @Test
    void anUnnamedStrandIsAcceptedThroughItsContainerName() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                mapped("Head", JOINT_HEAD, 0),
                unmapped("Hair_L", JOINT_HEAD, 1),
                unmapped("Strand03", JOINT_HEAD, 2),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        assertTrue(chains.stream().anyMatch(c -> c.boneName().equals("Strand03")),
                "a strand whose ancestor is named like hair must qualify even though its own name says nothing");

        // Neither is a wrapper: a wrapper is a bone containing something Epic Fight
        // poses, and Strand03 is unmapped like its parent.
        YsmPhysicsChains.Chain hair = chains.stream()
                .filter(c -> c.boneName().equals("Hair_L")).findFirst().orElseThrow();
        YsmPhysicsChains.Chain strand = chains.stream()
                .filter(c -> c.boneName().equals("Strand03")).findFirst().orElseThrow();
        assertTrue(hair.chainRoot(), "Hair_L is the topmost hanging bone, so it is the chain root");
        assertFalse(strand.chainRoot(), "Strand03 hangs under Hair_L, so it is not a root");
    }

    /** A chain root is the topmost hanging bone; the simulation keeps it firm. */
    @Test
    void theTopmostHangingBoneIsMarkedAsTheChainRoot() {
        YSMRuntimeModel.BoneRt[] bones = {
                mapped("Torso", JOINT_TORSO, -1),
                unmapped("Skirt", JOINT_TORSO, 0),
                unmapped("SkirtPanel1", JOINT_TORSO, 1),
        };

        List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(bones);

        YsmPhysicsChains.Chain root = chains.stream()
                .filter(c -> c.boneName().equals("Skirt")).findFirst().orElseThrow();
        YsmPhysicsChains.Chain panel = chains.stream()
                .filter(c -> c.boneName().equals("SkirtPanel1")).findFirst().orElseThrow();

        assertTrue(root.chainRoot(), "the topmost hanging bone is a chain root");
        assertFalse(panel.chainRoot(), "a piece under another hanging bone is not a root");
        assertTrue(root.aroundLegs(), "a piece hanging from the torso must be kept off the legs");
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
