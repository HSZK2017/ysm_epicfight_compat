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
 * <p>and then two ways to be disqualified: carrying a mapped body joint underneath
 * ({@link #isWrapper}), and holding no geometry of its own. The second one is the rule the
 * fallback classifier cannot state anywhere else, and it has two shapes with one consequence.
 *
 * <h2>Why a chain must hold geometry of its own</h2>
 *
 * <p>A swing is a rotation about the bone's <i>own</i> pivot, and the only thing it can move is
 * the geometry drawn at that bone's part ({@code y/<bone>} in the converted mesh). So the only
 * lever the swing can have is measured from that bone's own geometry, and a bone that draws
 * nothing has none - not a short one, none. {@code YsmPhysicsParts.buildSegment} drops such a
 * bone for exactly that reason, but by then the selection has already happened: the bone is in
 * {@code selectBones}' list, it is counted, and on a model at the cap it displaces a piece that
 * would have moved. Nothing in the log says so - a bone that produced no segment does not appear
 * in the segment list, and the count it spent is not printed at all. The two shapes are:
 *
 * <ul>
 *   <li><b>The container.</b> No geometry of its own, geometry under it ({@code LongHair} over
 *       the strands that are drawn). Swinging it would rotate a subtree whose geometry lives
 *       somewhere else, and the strands - the only thing anybody sees - would then have no lever
 *       of their own; so it is skipped and the geometric child becomes the chain instead. This
 *       was the first reason the rule existed.</li>
 *   <li><b>The empty bone.</b> No geometry of its own and none under it: nothing to swing, in any
 *       sense. The shape is the one {@code HangingPieceSelectionAcceptanceTest} pins under the
 *       name {@code HairTip} - a bone under a container that is <i>mapped</i>, so the container is
 *       skipped for being Epic Fight's and the bone underneath inherits the hint from an ancestor
 *       that was never a chain itself. On the shipped maid all 59 selected bones carry their own
 *       geometry, so this half of the rule changes nothing there; it is other models' shape.</li>
 * </ul>
 *
 * <p>Both shapes are <i>disqualifying</i> rather than merely unhelpful, so the test is the same
 * for both and is stated once: <b>a chain holds geometry of its own.</b> Note what this rule is
 * <i>not</i>: it is not the lever test. The lever is computed in
 * {@code YsmPhysicsParts.buildSegment} from vertex positions, which this classifier never sees,
 * and the shapes the lever would reject are therefore <b>not</b> reachable from here. On the
 * shipped maid the four tail bones that never move ({@code Tail4}..{@code Tail7}, logged with
 * {@code own=0.0deg} and levers of 0.021 to 0.098 blocks) each draw a cube of their own that
 * sits on their own pivot, so they pass this rule legitimately; see
 * {@code tmp_verify/T4_selection_report.md} for the measurement and for where the lever test
 * has to live.
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
 * <p>Rejecting wrappers is what keeps the simulation from moving a body part: the swing
 * goes into the bone's <i>local</i> transform, so it rotates that bone's whole subtree,
 * and a "skirt" bone with the legs under it would drag them along. The test is on the
 * subtree rather than on the candidate's own joint, and the reason is worth reading
 * before changing it - see {@link #isWrapper}.
 *
 * <p>Everything here is a question about what lies <i>under</i> a candidate. That
 * direction is the whole design: see {@link #isWrapper} for what happens when it is
 * reversed.
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
     * Bone-name fragments that rule a bone out however it is named otherwise.
     *
     * <p>Taken from the names real models actually use, because each family is a
     * different kind of mistake:
     *
     * <ul>
     *   <li><b>Emissive overlays.</b> {@code ysmGlow_*} names mark the extra pass YSM
     *       draws for a glowing part; those bones usually sit under a hair bone, so they
     *       inherit the hint and would swing as a second copy of hair that is drawn at
     *       the same time as the real one. Two copies moving differently is visible as
     *       flicker along the whole glowing piece.</li>
     *   <li><b>Locators.</b> {@code *Locator} bones carry no geometry: other mods read
     *       their position to attach an item. Swinging one moves whatever is attached,
     *       for a reason nobody can see on the model.</li>
     *   <li><b>Physics and IK helpers.</b> {@code *_physics}, {@code *bone}, {@code *tail}
     *       of a solver: their transforms are an input to something else's maths, so a
     *       swing here is not decoration, it is a corrupted input.</li>
     *   <li><b>Invisible, empty and clipped parts.</b> Nothing to swing, and rotating
     *       them can only move their children - which is exactly the accident the
     *       wrapper rule exists to prevent, reached through a name instead.</li>
     * </ul>
     */
    private static final String[] NAME_VETOES = {
            "glow", "locator", "physics", "invisible", "hidden",
            "empty", "clip", "hitbox", "collision", "helper", "dummy", "anchor",
            // Exporters emit "bone<N>" for a joint whose name was never set. It says
            // nothing about what the bone carries - the probe over real models found them
            // accepted in groups of six to eight under a single garment, which is a
            // segment mesh, not something that hangs.
            "bone"
    };

    /**
     * How many bones one model may turn into chains before the config says otherwise.
     * Bounded for the per-frame cost on a phone: the classifier keeps only the top of each
     * hanging piece (see {@link #build(YSMRuntimeModel.BoneRt[])}), so a real model lands
     * at 4-24, and this is the backstop for a model whose bones are named pathologically.
     *
     * <p>Overridable at runtime through {@code secondaryMotionMaxChains}, including
     * downward to zero, which is a cleaner way to measure the feature's cost than turning
     * it off and comparing two different runs.
     */
    public static final int DEFAULT_MAX_CHAINS = 24;

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
     *
     * <p><b>Only the top of each hanging piece becomes a chain.</b> A ponytail modelled
     * as ten segments is one swinging object, not ten, and its segments are already
     * parented to each other - rotating the top of the piece carries all of them, which
     * is what secondary motion is supposed to look like. Chaining every segment would
     * also multiply each segment's swing by the one above it, and it is how a real model
     * reached a hundred candidates and hit the cap. So a candidate whose ancestor is
     * already a chain is skipped.
     *
     * <p>Note the ancestor search stops at a wrapper rather than inheriting its verdict:
     * a model whose hair hangs under an "AllHead" container has that container rejected,
     * and its hair must still swing.
     */
    static List<Chain> build(YSMRuntimeModel.BoneRt[] bones) {
        return build(bones, null);
    }

    /**
     * The classification, restricted to the bones that can actually move something.
     *
     * <p>The {@code carriesGeometry} preference exists because the classification cannot
     * see the mesh, and on a real model the bones that read as hanging are often not the
     * ones holding the hair. A model that splits a braid into a container bone and a fan
     * of leaf strands puts the hint on the container and the geometry on the leaves, and
     * two things then go wrong at once: the leaves have no descendants, so the simulation
     * has no lever and produces no swing at all, while the container swings a piece whose
     * geometry is somewhere else entirely. Seven of the twenty chains classified on the
     * test model were leaves like this. Preferring bones with geometry swings the piece
     * where it is drawn.
     *
     * <p>The predicate is therefore not a preference but a <b>requirement</b>: a bone that
     * draws nothing of its own is not a chain, whether the geometry is under it (the child
     * swings instead) or absent altogether (nothing swings, and the bone would otherwise
     * spend a slot of the model's bone budget on a segment that is dropped again a few
     * lines later). See the class comment for both shapes and for why the lever itself -
     * the other half of "this piece cannot move" - is out of this method's reach.
     *
     * @param bones            the bone table
     * @param carriesGeometry  which bones carry mesh, or null to accept every bone - the
     *                         animator's path has no mesh at classification time and
     *                         keeps the original behaviour
     */
    static List<Chain> build(YSMRuntimeModel.BoneRt[] bones, java.util.function.IntPredicate carriesGeometry) {
        List<Chain> chains = new ArrayList<>();
        if (bones == null) {
            return chains;
        }
        // The list is not truncated here. It used to be cut at the same number that caps the
        // simulation, which quietly turned "the first N candidates in bone order" into the set of
        // pieces that exist: on a model with two dozen hanging bones the cut falls inside the
        // garment, so some panels swing and the rest are bolted down - and which ones is decided by
        // bone order, which no author chose and no reader of the log can see. What bounds the work
        // is the cap on simulated bones, applied to whole pieces in YsmPhysicsParts#selectBones and
        // reported when it bites. The backstop here is only against a pathological table.
        int limit = YsmPhysicsTuning.maxChains() == YsmPhysicsTuning.AUTO
                ? Integer.MAX_VALUE
                : Math.max(64, YsmPhysicsTuning.maxChains() * 4);
        if (YsmPhysicsTuning.maxChains() <= 0) {
            return chains;
        }

        for (int i = 0; i < bones.length && chains.size() < limit; i++) {
            YSMRuntimeModel.BoneRt bone = bones[i];
            if (bone == null || bone.name == null || bone.name.isEmpty()) {
                continue;
            }
            // Epic Fight owns the gross pose of a directly mapped bone.
            if (bone.mapped) {
                continue;
            }
            if (isVetoed(bone.name)) {
                continue;
            }
            if (!hangs(bones, i)) {
                continue;
            }
            if (carriesGeometry != null && !carriesGeometry.test(i)) {
                // Nothing of this bone's own is drawn, so there is no geometry for the swing to
                // carry and no lever to measure. Whether the geometry is under it (the child
                // becomes the chain) or nowhere at all (nothing does), this bone is not a chain.
                // See "Why a chain must hold geometry of its own" in the class comment.
                continue;
            }
            if (continuesAnAcceptedChain(chains, bones, i)) {
                continue;
            }
            if (isWrapper(bones, i)) {
                continue;
            }
            // Only pieces that actually reach a mapped ancestor can be swung: a bone
            // whose chain never reaches Epic Fight's skeleton has no stable frame to
            // hang the swing from.
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
     * Whether an ancestor of this bone is already a chain, i.e. this bone is a segment of
     * a piece that is already swinging. See the note on
     * {@link #build(YSMRuntimeModel.BoneRt[])}.
     *
     * <p>Stops at a wrapper: an ancestor that was rejected as a container is not carrying
     * a swing, so the bones under it are still free to be the top of their own piece.
     */
    private static boolean continuesAnAcceptedChain(List<Chain> accepted,
                                                    YSMRuntimeModel.BoneRt[] bones,
                                                    int index) {
        int guard = 0;
        for (int i = bones[index].parent; i >= 0 && guard++ <= bones.length; i = bones[i].parent) {
            for (Chain chain : accepted) {
                if (chain.boneIndex() == i) {
                    return true;
                }
            }
            if (isWrapper(bones, i)) {
                return false;
            }
        }
        return false;
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
     * Whether the bone's own name rules it out regardless of anything else. See
     * {@link #NAME_VETOES} for which families this covers and what each one breaks.
     *
     * <p>Only the bone's own name is consulted, deliberately: a veto on an ancestor would
     * disqualify the real cloth hanging under it, which is the same mistake as testing
     * ancestors for the wrapper rule.
     *
     * <p>Package-private so the vocabulary can be tested directly: the lists are matched
     * as substrings, so a wrong entry silently swallows real cloth, and that is worth a
     * test that does not have to construct a whole bone table to reach.
     */
    static boolean isVetoed(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String veto : NAME_VETOES) {
            if (lower.contains(veto)) {
                return true;
            }
        }
        return isPlaceholderName(lower);
    }

    /**
     * Whether a bone's name is a placeholder rather than a name - {@code bone5}, {@code bone_12}.
     *
     * <p>These matter because a candidate is accepted when it - <i>or an ancestor</i> - reads as
     * cloth, so a bone with no meaning of its own still gets swept in: the shipped maid has a
     * {@code bone5} hanging under a garment container, and the log lists it among the skirt's
     * pieces. Its lever is a full block, the longest in the model, so it is not a small mistake -
     * swinging it moves whatever it carries the way a one-block arm would.
     *
     * <p>The test is narrow on purpose: a name is a placeholder only when it is <i>entirely</i> a
     * generic stem plus a number. Real bones are named after what they are ({@code LM2},
     * {@code FFM3_1}, {@code BackHairLeftD3}) and every one of those has to survive.
     */
    private static boolean isPlaceholderName(String lowerCaseName) {
        for (String stem : PLACEHOLDER_STEMS) {
            int start = lowerCaseName.indexOf(stem);
            if (start < 0 || (start > 0 && Character.isLetterOrDigit(lowerCaseName.charAt(start - 1)))) {
                continue;
            }
            int index = start + stem.length();
            if (index < lowerCaseName.length() && lowerCaseName.charAt(index) == '_') {
                index++;
            }
            if (index >= lowerCaseName.length()) {
                continue;
            }
            boolean digitsOnly = true;
            for (int i = index; i < lowerCaseName.length(); i++) {
                if (!Character.isDigit(lowerCaseName.charAt(i))) {
                    digitsOnly = false;
                    break;
                }
            }
            if (digitsOnly) {
                return true;
            }
        }
        return false;
    }

    /** Stems that name "some bone" rather than a thing. */
    private static final String[] PLACEHOLDER_STEMS = {"bone", "cube", "box"};

    /**
     * Whether this candidate is a body part rather than a piece hanging off one.
     *
     * <p>One test: a bone <b>below</b> it is directly mapped to a body joint. The swing
     * is folded into the bone's local transform, so it rotates that bone's whole subtree
     * - which means what the bone carries is exactly what hangs under it. A bone called
     * "AllHead" or "HairGroup" with the model's head underneath carries the head, and
     * swinging it moves the head. This is the structural stand-in for upstream's
     * cube-count test, which asks the same question - how much of the model is under this
     * bone - but needs cube data this mod's runtime table does not carry.
     *
     * <p><b>Why the bone's own {@code joint} is not the test.</b> The two fields come
     * from different questions: {@code mapped} is the bone's own name appearing in the
     * mapping table, while {@code joint} is resolved by walking <i>up</i> to the nearest
     * named ancestor. A real model therefore contains bones that are unmapped and still
     * carry a body joint - the wine fox's {@code Skirt} is unmapped, yet resolves to the
     * torso through its parent {@code qunzi}. Reading that joint as "this bone drives the
     * torso" rejects the skirt, and rejects nearly every lock of hair the same way: hair
     * resolves to the head or to the root through an unmapped parent chain. Measured over
     * eighteen real cached models, that rule left three of them with a single swingable
     * bone and most of the rest with only the head hair.
     *
     * <p>The joint a bone resolves to describes where it sits, which is why it is still
     * the right thing for {@code aroundLegs} and for {@code parentJointId}; it is the
     * wrong thing for deciding what the bone carries, and the subtree answers that.
     *
     * <p>What is deliberately <b>not</b> a rejection rule either is a mapped bone
     * <i>above</i> the candidate. Hair hangs off a head and a skirt hangs off a torso in
     * every real model, so a rule phrased that way rejects every candidate there is -
     * which is how this classifier first shipped, and why the feature moved nothing at
     * all.
     */
    private static boolean isWrapper(YSMRuntimeModel.BoneRt[] bones, int index) {
        for (int i = 0; i < bones.length; i++) {
            if (i == index || bones[i] == null) {
                continue;
            }
            if (bones[i].mapped && isBodyJoint(bones[i].joint) && descendsFrom(bones, i, index)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The joints whose subtree is most of the model: the trunk, the hips and the legs.
     *
     * <p>Deliberately narrow, because rejecting too much is a failure too - the first
     * version of this rule rejected every candidate in the model and the feature did
     * nothing at all. The head is excluded: hair hangs off it, that is the normal and
     * wanted case. So are the arms, where a sleeve or a ribbon hangs off a hand.
     *
     * <p>The root is excluded as well, which looks alarming and is not: nothing that
     * matters can be under a root-mapped bone. Only the bone actually named for the root
     * is directly mapped to joint 0, and a bone that merely resolves up to the root is
     * unmapped, so a candidate's subtree can contain joint 0 only when the root itself
     * hangs under it - and a bone with the model's root under it carries everything, so
     * it is caught by the mapped trunk or hips it also contains.
     */
    private static boolean isBodyJoint(int joint) {
        return joint == JOINT_TORSO
                || joint == JOINT_CHEST
                || joint == JOINT_THIGH_R
                || joint == JOINT_THIGH_L
                || joint == 3                   // Knee_R
                || joint == 6;                  // Knee_L
    }

    /**
     * Whether {@code bone} hangs anywhere under {@code ancestor}.
     *
     * <p>Bounded by the table length so the cyclic parent tables that model data can
     * contain terminate here instead of spinning.
     */
    private static boolean descendsFrom(YSMRuntimeModel.BoneRt[] bones, int bone, int ancestor) {
        int guard = 0;
        for (int i = bones[bone].parent; i >= 0 && guard++ <= bones.length; i = bones[i].parent) {
            if (i == ancestor) {
                return true;
            }
        }
        return false;
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

    /**
     * True when the bone above this one is not itself a chain, i.e. this is a root.
     *
     * <p>Position in the chain, not a name match: this is asked of bones that never
     * become chains, because a bone's root-ness decides how firmly it is held, while its
     * name decides whether it is swung at all. Folding the two together is how a
     * classifier ends up with nothing to swing.
     */
    static boolean isChainRoot(YSMRuntimeModel.BoneRt[] bones, int index) {
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

    // ------------------------------------------------------------------
    // Which pieces are sewn to which
    // ------------------------------------------------------------------

    /**
     * How near two panels' pivots must be, in bind space blocks, to count as sewn together.
     *
     * <p>A few centimetres: the panels of a skirt are modelled side by side around the waist, so
     * their tops are close together while their hems spread apart. Coupling on the pivot rather
     * than on the geometry is deliberate - what has to move together is where the fabric is
     * <i>attached</i>, not where it happens to hang.
     */
    static final float KNIT_RADIUS = 0.18F;

    /**
     * How alike two pieces' rest directions must be to count as sewn together.
     *
     * <p>Roughly forty degrees. A skirt's panels fan out around the waist, so neighbours differ by
     * a modest angle; the front and back panels differ by much more and should not be coupled, or
     * the whole skirt would average itself into a single rigid cone.
     */
    static final float KNIT_MAX_ANGLE = 0.7F;

    /** At most this many knit partners each, so the relaxation stays a short fixed loop. */
    static final int KNIT_COUNT = 4;

    /**
     * Whether two segments are sewn to each other, and so must be pulled toward each other.
     *
     * <h2>Why the parent/child pairs belong here, which is the correction</h2>
     *
     * <p>{@code YsmPhysicsParts#wireNeighbours} left the parent/child pairs out on the argument that
     * a chain's composition already ties them: the child's delta is composed under the parent's, so
     * the child is carried rigidly and the two cannot come apart. That is true of the
     * <i>attachment</i> and false of the <i>cloth</i>, and the difference is exactly the reported
     * defect. Composition holds the child's vertices that sit on its own pivot - the top of the
     * panel, at the waistband - and does nothing at all for the hem, which is where a panel is
     * seen. The hem's world angle is the parent's swing <b>plus the child's own</b>, and the child's
     * own is measured against the animation with no reference to its parent. So the pair that is
     * physically the same piece of cloth is the one pair with no coupling at all, and the front
     * panel of the shipped maid skirt logs 14.6 degrees beside the left one's 5.0: the pieces did
     * not merely come apart, they were never held together in the first place.
     *
     * <p>The skirt's panels are a {@code FR -> FR1 -> FR2} chain, so on the maid <i>every</i>
     * same-panel pair is a parent/child pair - which is why excluding them left the garment with
     * no same-piece coupling whatsoever and only the waistband's neighbours to average against.
     *
     * <h2>The two thresholds</h2>
     *
     * <p>Pieces sewn at different places are coupled by geometry: pivots within
     * {@link #KNIT_RADIUS} of each other and rest directions within {@link #KNIT_MAX_ANGLE}. The
     * two thresholds are not applied to a parent and child, and that is deliberate rather than
     * convenient: the relation is already a fact of the model, not an inference from geometry, so
     * there is nothing to infer. It also covers the shape the radius would reject - a long panel
     * hanging well below a short one and swinging as its child - which is a normal rig, and on
     * which a distance test would silently drop the coupling it most needs.
     */
    static boolean sewnTogether(YsmPhysicsParts.Segment a, YsmPhysicsParts.Segment b,
                                int parentOfA, int parentOfB) {
        if (a == null || b == null) {
            return false;
        }
        if (parentOfA == parentOfB) {
            return true;
        }
        if (a.bindPivot() == null || b.bindPivot() == null
                || a.bindRest() == null || b.bindRest() == null) {
            return false;
        }
        if (a.bindPivot().distance(b.bindPivot()) > KNIT_RADIUS) {
            return false;
        }
        return YsmDynamicBoneSolver.angleBetween(a.bindRest(), b.bindRest()) <= KNIT_MAX_ANGLE;
    }
}
