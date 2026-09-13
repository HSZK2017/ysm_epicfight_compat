package com.ysmef.compat.model.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picks the bones of a YSM model that should swing freely - hair, tails, skirts,
 * capes, ribbons - and describes each as a chain the secondary-motion simulation can
 * drive.
 *
 * <p>Ported from EpicYSM's {@code PhysicsChains} (MIT), adapted to this mod's
 * runtime model: the bone table here already carries each YSM bone's resolved Epic
 * Fight joint and whether that mapping is direct, so the classification needs no
 * re-parse of the model package.
 *
 * <h2>What makes a bone a candidate</h2>
 *
 * <p>Two conditions, both required:
 *
 * <ol>
 *   <li><b>It is not directly mapped to an Epic Fight joint.</b> A mapped bone's
 *       gross pose belongs to Epic Fight's combat animation, and swinging it would
 *       fight the animation - a "hair" bone that happens to be the model's actual
 *       head must not move on its own.</li>
 *   <li><b>Its name - or an ancestor's - reads like something that hangs.</b> The
 *       hint list is deliberately the vocabulary model authors actually use, kept in
 *       one place so it can be extended from evidence rather than scattered.</li>
 * </ol>
 *
 * <h2>Why the wrapper test matters more here than upstream</h2>
 *
 * <p>Animations often drive a container bone ("AllHead", "UpperBody") rather than the
 * individual piece, and a container that would swing a whole limb or the head is a
 * mis-detection, not a lock of hair. Upstream rejects such a bone by counting how
 * many of the model's cubes hang under it. This mod has no cube counts at runtime, so
 * the same intent is expressed structurally instead: a candidate whose subtree
 * contains a bone that is <i>directly mapped</i> to a meaningful joint is a wrapper
 * and is rejected. That is strictly the property that made the cube-count heuristic
 * work - a wrapper is a bone that contains something Epic Fight already poses - and it
 * costs one pass over the bone table.
 *
 * <p>Rejecting wrappers is what keeps the simulation from moving a body part: if a
 * "skirt" bone resolved to the torso joint were accepted, swinging it would drag the
 * hips (and everything parented under them) with it.
 */
public final class YsmPhysicsChains {

    /**
     * Bone-name fragments that read as something which hangs and swings. Lower case;
     * matched as substrings of the normalized bone name, so "hair_L", "HairFront"
     * and "longhair" all match "hair".
     */
    private static final String[] NAME_HINTS = {
            "hair", "tail", "ponytail", "twintail", "twin",
            "qun", "skirt", "dress", "cloth", "hem",
            "cape", "cloak", "mantle", "scarf",
            "ribbon", "sash", "tassel", "braid", "belt", "pendant"
    };

    /**
     * How many bones one model may turn into chains. A model with more hanging pieces
     * than this is either mis-named or pathological, and the cap keeps the per-frame
     * simulation bounded on a phone.
     */
    private static final int MAX_CHAINS = 40;

    /**
     * One chain: the bone to swing, the chain it hangs from, and how far the piece
     * reaches from its pivot so the simulation has a lever to rotate.
     *
     * @param boneIndex  index into {@link YSMRuntimeModel#bones}
     * @param boneName   the bone's name, for the log and for user overrides
     * @param jointId    the Epic Fight joint this bone contributes to, or -1
     * @param parentJointId the joint of the nearest ancestor that is directly mapped,
     *                      i.e. what the swing hangs off; -1 when the bone has no
     *                      mapped ancestor at all
     * @param chainRoot  true for the topmost bone of a hanging piece - roots hold the
     *                   whole hairdo or skirt, so the simulation keeps them firm
     * @param aroundLegs true when the piece hangs from the hips or waist, where it
     *                   must be kept off the legs
     */
    public record Chain(int boneIndex, String boneName, int jointId, int parentJointId,
                        boolean chainRoot, boolean aroundLegs) {}

    private YsmPhysicsChains() {}

    /** The joint ids of the legs and hips, the region a skirt has to stay clear of. */
    private static final int JOINT_TORSO = 7;
    private static final int JOINT_CHEST = 8;
    private static final int JOINT_THIGH_R = 1;
    private static final int JOINT_THIGH_L = 4;

    /**
     * The chains to simulate for this model, in bone order. Empty when nothing
     * qualifies - which is the common case for a model with no hanging pieces.
     */
    public static List<Chain> build(YSMRuntimeModel model) {
        return model == null ? List.of() : build(model.bones);
    }

    /**
     * The classification itself, over a bone table alone.
     *
     * <p>Separated from {@link #build(YSMRuntimeModel)} so the rules below can be
     * tested directly: the decision is what goes wrong here (a mis-detected wrapper
     * swings a body part), and it depends on nothing but the table.
     */
    static List<Chain> build(YSMRuntimeModel.BoneRt[] bones) {
        List<Chain> chains = new ArrayList<>();
        if (bones == null) {
            return chains;
        }

        for (int i = 0; i < bones.length && chains.size() < MAX_CHAINS; i++) {
            YSMRuntimeModel.BoneRt bone = bones[i];
            if (bone == null || bone.name == null || bone.name.isEmpty()) {
                continue;
            }
            // Epic Fight owns the gross pose of a directly mapped bone.
            if (bone.mapped) {
                continue;
            }
            if (!hangs(bones, i)) {
                continue;
            }
            if (isWrapper(bones, i)) {
                continue;
            }
            // Only pieces that actually reach a mapped ancestor can be swung: a bone
            // whose chain never reaches Epic Fight's skeleton has no stable frame, and
            // one that is its own root would swing the whole model.
            int parentJoint = nearestMappedJoint(bones, i);
            if (parentJoint < 0) {
                continue;
            }
            boolean root = isChainRoot(bones, i);
            chains.add(new Chain(i, bone.name, bone.joint, parentJoint, root,
                    hangsAroundLegs(parentJoint)));
        }
        return chains;
    }

    /**
     * Whether this bone or one of its ancestors reads like something that hangs.
     * Walking the ancestors is what lets an individual strand ("Hair_03") be a
     * candidate when only the container is named ("Hair_L").
     */
    private static boolean hangs(YSMRuntimeModel.BoneRt[] bones, int index) {
        for (int i = index; i >= 0; i = bones[i].parent) {
            String name = bones[i].name;
            if (name == null) {
                break;
            }
            if (matchesHint(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesHint(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String hint : NAME_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this candidate is a container for geometry Epic Fight already poses.
     *
     * <p>See the class comment: this replaces upstream's cube-count test with the
     * structural property behind it.
     */
    private static boolean isWrapper(YSMRuntimeModel.BoneRt[] bones, int index) {
        for (int i = 0; i < bones.length; i++) {
            if (i == index || bones[i] == null) {
                continue;
            }
            if (!isDescendantOf(bones, i, index)) {
                continue;
            }
            if (bones[i].mapped && isBodyJoint(bones[i].joint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code candidate} sits under {@code ancestor} in the bone tree. The
     * parent chain is walked with a depth guard: model data is untrusted input and a
     * malformed table can contain a cycle.
     */
    private static boolean isDescendantOf(YSMRuntimeModel.BoneRt[] bones, int candidate, int ancestor) {
        int guard = 0;
        for (int i = bones[candidate].parent; i >= 0 && guard++ <= bones.length; i = bones[i].parent) {
            if (i == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * The body joints a wrapper would be hiding. Head, chest and torso are body parts
     * rather than accessories, and the arms and legs are limbs; a "hanging" bone whose
     * subtree reaches any of them is a container, not a piece of cloth.
     */
    private static boolean isBodyJoint(int joint) {
        return joint == JOINT_TORSO
                || joint == JOINT_CHEST
                || joint == JOINT_THIGH_R
                || joint == JOINT_THIGH_L
                || joint == 0                      // Root
                || (joint >= 9 && joint <= 19);     // Head, shoulders, arms, hands, tools, elbows
    }

    /** The joint of the nearest ancestor that Epic Fight poses directly, or -1. */
    private static int nearestMappedJoint(YSMRuntimeModel.BoneRt[] bones, int index) {
        int guard = 0;
        for (int i = bones[index].parent; i >= 0 && guard++ <= bones.length; i = bones[i].parent) {
            if (bones[i].mapped) {
                return bones[i].joint;
            }
        }
        return -1;
    }

    /** True when the bone above this one is not itself a chain, i.e. this is a root. */
    private static boolean isChainRoot(YSMRuntimeModel.BoneRt[] bones, int index) {
        int parent = bones[index].parent;
        if (parent < 0) {
            return true;
        }
        YSMRuntimeModel.BoneRt above = bones[parent];
        return above == null || above.mapped || !matchesHint(above.name == null ? "" : above.name);
    }

    /**
     * Whether the chain hangs from the hips or the waist - the region where cloth has
     * to be kept off the legs, and where the simulation allows a larger swing (a skirt
     * panel pushed by a knee may come right up).
     */
    private static boolean hangsAroundLegs(int parentJoint) {
        return parentJoint == JOINT_TORSO;
    }
}
