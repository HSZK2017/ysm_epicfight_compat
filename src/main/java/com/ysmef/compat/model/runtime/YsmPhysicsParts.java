package com.ysmef.compat.model.runtime;

import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMJointMapper;
import com.ysmef.compat.model.YSMMesh;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.client.model.VertexBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Turns a model plus its converted mesh into the list of <b>segments</b> the dynamics run
 * on: one per physics-driven bone, each with its own pivot, moment arm, mass and
 * collision radius.
 *
 * <h2>Where the segment list comes from</h2>
 *
 * <p>Two sources, in this order, and the order is the point:
 *
 * <ol>
 *   <li><b>The author's own declaration</b> ({@link YsmPhysicsBinding}): the bones named
 *       by the animation the model's controller plays as its physics animation
 *       ({@code Hair_Physics}, or whatever the model calls it). This is exact - it is the
 *       same list YSM itself would simulate - and it is what makes the feature work on
 *       models whose bone names say nothing ("MaWei_LeftA2", "BackHairRightC2").</li>
 *   <li><b>The bone-name classifier</b> ({@link YsmPhysicsChains}) as a fallback, for
 *       models that predate the convention or were exported without controllers.</li>
 * </ol>
 *
 * <h2>Every bone is its own segment</h2>
 *
 * <p>The previous classifier kept only the <i>top</i> of each hanging piece, on the
 * grounds that a ponytail is one swinging object - which was true of a simulation that
 * could only apply one rotation per piece. It is not true of a pendulum chain, and it is
 * exactly what made a ten-bone ponytail swing like a plank: the whole piece rotated about
 * one pivot. Here every bone the author listed (and, on the fallback path, every bone of
 * the piece that carries geometry) gets its own pivot and its own dynamics, so the piece
 * curls. Tearing is prevented by composing each segment's delta under its nearest
 * simulated ancestor's - see {@link Segment#parent()}.
 *
 * <h2>What may not be a segment</h2>
 *
 * <p>A segment moves the geometry drawn by <i>its own mesh part</i>, so its lever, rest
 * direction and collision radius have to be measured from that geometry and from nothing
 * else. {@link #ownsItsGeometry} is that rule, and {@link #poseBelongsToEpicFight} is its
 * other half: a bone whose name is a body part is animated by Epic Fight, and a second
 * rotation on top of that pose turns a limb rather than the cloth on it. Both are checked
 * in {@code buildSegment}, so a part list that names such a bone - an author's physics
 * animation naming a mapped bone, or a bone that carries no quads at all - yields no
 * segment for it rather than a segment with a lever pointing at someone else's geometry.
 *
 * <h2>The moment arm</h2>
 *
 * <p>{@code bindPivot} is the bone's pivot expressed in the mesh's own bind space. It is
 * the point the part transform must rotate about, and getting it wrong is what produced
 * the "flung away and snapped home" behaviour: a rotation applied about the model origin
 * instead of about the bone's pivot gives every piece a lever the length of the model.
 * The pivot is {@code bindWorld x (pivot x scale)}: {@code bindWorld} is the accumulated
 * bind deformation the mesh was written with, so this is the same point the geometry was
 * placed at.
 *
 * <p>Non-uniform model scales ({@code width_scale != height_scale}) make that product an
 * approximation, because an accumulated rotation about each pivot does not commute with an
 * anisotropic scale. Every model seen on the test instance uses a uniform scale, and the
 * alternative - deriving the pivot from geometry - collapses for the outright common case
 * of a strand bone with no geometry of its own.
 */
public final class YsmPhysicsParts {

    /**
     * Segment collision spheres are this fraction of the piece's own half-thickness.
     *
     * <p>Deliberately smaller than the geometry, so cloth comes to rest on a surface rather than
     * being flung off it by a sphere that reaches past the thing it is meant to touch. What the
     * fraction multiplies is the <i>thickness</i>, not the distance to the centre of mass: see
     * {@link #radiusFor}.
     */
    private static final float COLLISION_RADIUS_FRACTION = 0.65F;

    private static final float MIN_RADIUS = 0.02F;
    private static final float MAX_RADIUS = 0.22F;

    /** Fewer vertices than this and the geometry cannot size a lever or a sphere. */
    private static final int MIN_VERTICES = 4;

    /** Relative mass proxy: vertex count over this, clamped. See {@link Segment#mass()}. */
    private static final float VERTICES_PER_MASS_UNIT = 64.0F;

    /** A lever shorter than this is a bone sitting on its own pivot; it cannot swing. */
    private static final float MIN_LEVER = 0.01F;

    /** The torso joint id, whose pieces hang where the legs are. */
    private static final int JOINT_TORSO = 7;

    /** Where the segment list came from, for the log. */
    public enum Source {
        /**
         * The model's controller plays a physics animation, and it named these bones.
         *
         * <p>What that buys is the <b>list</b> and the author's <b>spring parameters</b>
         * ({@link Segment#frequency()}, {@link Segment#coefficient()}) - not the motion. Every
         * segment of every model is integrated by the pendulum solver; this source never means
         * "write the author's keyframe angles onto the parts". See
         * {@code YsmMeshSecondaryMotion#apply}.
         */
        AUTHORED,
        /** No such animation: the bones were classified by name. */
        BONE_NAMES,
        /** Nothing to simulate. */
        NONE
    }

    /**
     * One simulated bone.
     *
     * @param boneIndex   index into {@link YSMRuntimeModel#bones}
     * @param boneName    the bone's name, for the log and for user overrides
     * @param joint       the Epic Fight joint whose pose carries this part
     * @param bindPivot   the pivot in model bind space; the point the delta rotates about
     * @param bindRest    pivot to centre of mass, model bind space; its length is the lever
     * @param lever       {@code |bindRest|}, blocks. The moment arm.
     * @param radius      collision radius, blocks
     * @param mass        relative mass. A proxy from the geometry's vertex count (the model
     *                    package carries no density), used only by the drag term, where it
     *                    decides how readily a piece is blown about - which is the one
     *                    place the difference between a thin braid and a broad skirt panel
     *                    has to show up.
     * @param frequency   spring frequency, Hz: the author's {@code ysm.second_order}
     *                    argument when there is one, the config's stiffness otherwise
     * @param coefficient damping ratio 0..1, likewise the author's or the config's
     * @param maxAngle    how far this segment may bend from its rest direction, radians
     * @param parent      the nearest simulated ancestor bone's segment index, or -1; the
     *                    segment's delta is composed under its parent's so a piece cannot
     *                    tear apart at a joint
     * @param parts       the mesh part ordinals carrying this bone's geometry
     * @param authored    true when the model itself declared this bone as physics-driven
     * @param neighbours  the segments sewn to this one - pivots near each other, hanging the same
     *                    way - which the simulation pulls this one toward so a garment moves as
     *                    one garment rather than as its separate panels. See
     *                    {@code YsmMeshSecondaryMotion#relaxTowardsNeighbours}.
     */
    public record Segment(int boneIndex, String boneName, int joint, Vector3f bindPivot,
                          Vector3f bindRest, float lever, float radius, float mass,
                          float frequency, float coefficient, float maxAngle, int parent,
                          int[] parts, boolean authored, int[] neighbours) {

        /**
         * How much this piece's spring follows the world's downward direction rather than the posed
         * rest direction, 0..1 - the solver's {@code verticalFollow}. Read live, so the config's own
         * scaling applies without rebuilding the classification.
         *
         * <p>See {@link #categoryOf} for the numbers and for why a piece gets one.
         */
        public float verticalFollow() {
            return categoryOf(boneName).weight * (float) YsmPhysicsTuning.gravityFollowScale();
        }
    }

    /**
     * How far a piece's spring leaves the pose for the world's vertical, per kind of piece.
     *
     * <p>These are the whole of the classification's answer to "hair should droop but a skirt
     * should hang". The mechanism is one: the spring's target direction is a blend of the posed rest
     * direction and the world's downward direction (see {@code YsmDynamicBoneSolver#update}), and the
     * weight is how much of the second it contains. What the weight buys is stated exactly by the
     * solver's balance: a piece settles at the target as the spring gets stiff, and the target is at
     * {@code atan2(b sin a, (1-b) + b cos a)} from the rest direction for a body leaning {@code a},
     * so the angle the piece keeps from the world's vertical settles to roughly {@code (1 - b) a} -
     * the fraction of the body's lean the piece declines to follow.
     *
     * <ul>
     *   <li><b>{@link Category#CLOTH} (0.92).</b> Cloth is expected to hang toward the ground: at
     *       sixty degrees of lean the piece then sits 8 per cent of that - 4.8 degrees - off
     *       vertical, inside the ten degrees the report asks for, with margin for the per-lever
     *       gravity share (the measurement is in {@code tmp_verify/T9_findings.md}). Raising it to 1
     *       would be 0 degrees with no margin at all and would make the pieces indifferent to the
     *       pose, so the last 8 per cent is kept deliberately: a garment that answers the animation
     *       slightly is what makes it read as worn rather than as a second, independent object.</li>
     *   <li><b>{@link Category#HAIR} (0.60).</b> A lock of hair grows out of a skull and has its own
     *       volume, so every strand pointing at the world's vertical is <i>wrong</i> - that is what
     *       wet hair looks like, not hair. At 0.60 a piece keeps about 40 per cent of the body's
     *       lean: on a sixty degree sprint the strands sit some 24 degrees off vertical, which is
     *       what a ponytail actually does behind a runner, while a standing pose is untouched
     *       because a blend of two directions that already agree is the same direction.</li>
     *   <li><b>{@link Category#TAIL} (0.80).</b> A tail is neither: it is a heavy appendage with a
     *       shape of its own, hung from the base of the spine. It reads as gravity-driven - more
     *       than hair, whose root has to follow the head, and less than a skirt panel, which is
     *       attached along a whole waistband and has no shape to keep. At 0.80 it holds about a
     *       fifth of the lean.</li>
     * </ul>
     *
     * <p><b>Hair is matched before cloth and cloth before tail</b>, because the families' names
     * overlap: "ponytail" and "twintail" contain "tail", and a "hairband" contains no hint that
     * would not also catch the hair around it. Ordering is what keeps a ponytail from being treated
     * as a tail.
     */
    private enum Category {
        /** Skirts, dresses, capes and anything else worn: hangs toward the ground. */
        CLOTH(0.92F),
        /** Tails, braids and tufts that are part of the body. */
        TAIL(0.80F),
        /** Hair, which grows out of the skull and keeps its volume. */
        HAIR(0.60F),
        /** Nothing recognisable: fall back to the piece following its own pose. */
        UNKNOWN(0.0F);

        final float weight;

        Category(float weight) {
            this.weight = weight;
        }
    }

    /**
     * The name fragments of each family, matched against the same normalized name the rest of the
     * classifier uses, in the order {@link Category}'s own comment explains.
     */
    private static final String[] HAIR_HINTS = {
            "hair", "bangs", "fringe", "ahoge", "mop"
    };

    private static final String[] CLOTH_HINTS = {
            "skirt", "dress", "qun", "cloth", "hem", "coat", "robe", "kimono",
            "cape", "cloak", "mantle", "scarf", "sash", "belt", "apron", "sleeve", "ribbon"
    };

    private static final String[] TAIL_HINTS = {
            "tail", "braid", "tassel", "pendant", "plume", "feather"
    };

    /**
     * Which family a bone's name reads as, and therefore what it is pulled toward.
     *
     * <p>Name-driven, with everything that implies: a model whose bones are named in a language the
     * hints do not cover falls to {@link Category#UNKNOWN} and behaves exactly as it did before the
     * weight existed - the pose is its whole target. That is the deliberate failure direction. A
     * wrong guess the other way, "this is cloth" for something that is not, would hand a body part
     * to gravity, and the accepted-cost comparison is between a piece that does not droop and a
     * piece that leaves the body.
     */
    static Category categoryOf(String boneName) {
        if (boneName == null || boneName.isEmpty()) {
            return Category.UNKNOWN;
        }
        String normalized = YSMJointMapper.normalize(boneName);
        if (normalized.isEmpty()) {
            return Category.UNKNOWN;
        }
        if (containsAny(normalized, HAIR_HINTS)) {
            return Category.HAIR;
        }
        if (containsAny(normalized, CLOTH_HINTS)) {
            return Category.CLOTH;
        }
        if (containsAny(normalized, TAIL_HINTS)) {
            return Category.TAIL;
        }
        return Category.UNKNOWN;
    }

    private static boolean containsAny(String normalized, String[] hints) {
        for (String hint : hints) {
            if (normalized.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** The segment list of one model. */
    public record Model(Segment[] segments, Source source, int droppedPieces) {

        public boolean isEmpty() {
            return segments.length == 0;
        }

        /** The names, for the log. */
        public String names() {
            StringBuilder builder = new StringBuilder();
            for (Segment segment : segments) {
                builder.append(builder.length() == 0 ? "" : ", ").append(segment.boneName());
            }
            return builder.toString();
        }
    }

    /** A segment before its parent link is known in segment indices. */
    private record Draft(int boneIndex, String boneName, int joint, Vector3f bindPivot,
                         Vector3f bindRest, float lever, float radius, float mass,
                         float frequency, float coefficient, float maxAngle, int parentBone,
                         int[] parts, boolean authored) {}

    private static final Model EMPTY = new Model(new Segment[0], Source.NONE, 0);

    private YsmPhysicsParts() {}

    /**
     * Build the segment list.
     *
     * @param model             the runtime model (bone table + the author's physics parts)
     * @param mesh              the converted mesh being drawn, used for levers and radii
     * @param fallbackFrequency spring frequency when the model authored none
     * @param fallbackDamping   damping ratio when the model authored none
     * @param maxAngle          swing limit for a strand, radians
     * @param maxAngleRoot      swing limit for the top of a piece, radians
     */
    public static Model build(YSMRuntimeModel model, YSMMesh mesh,
                              float fallbackFrequency, float fallbackDamping,
                              float maxAngle, float maxAngleRoot) {
        if (model == null || model.bones == null || model.bones.length == 0) {
            return EMPTY;
        }
        Map<Integer, List<Vector3f>> vertices = verticesByBone(mesh, model);
        Map<Integer, float[]> geometry = geometryByBone(vertices);
        Map<Integer, int[]> partOrdinals = partOrdinalsByBone(mesh, model);
        // What a segment may be measured from is its own geometry, and nothing else - see
        // ownsItsGeometry. The same test is handed to the classifier so that "is this bone a piece
        // of cloth or the container above one" is answered by one rule everywhere.
        IntPredicate ownsGeometry = bone -> ownsItsGeometry(bone, geometry, partOrdinals);

        List<Integer> selected = new ArrayList<>();
        boolean authored = false;
        if (model.physicsParts != null && !model.physicsParts.isEmpty()) {
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (YsmPhysicsBinding.Part part : model.physicsParts) {
                Integer index = model.boneIndex.get(part.bone());
                if (index != null && index >= 0 && index < model.bones.length && seen.add(index)
                        && ownsGeometry.test(index) && !poseBelongsToEpicFight(model.bones[index])) {
                    selected.add(index);
                }
            }
            // The flag follows the bones that survived the two ownership tests, not the declaration:
            // an author's list of which no bone owns its own geometry says nothing usable about the
            // model, and the honest answer then is the classifier's - which prefers the bone that
            // actually carries the mesh - rather than an empty simulation and a source label
            // claiming the author's own rig. Reported as BONE_NAMES, because that is what it is.
            authored = !selected.isEmpty();
        }
        // The cap is a per-model backstop for a model that declares hundreds of bones, and it is a
        // cap on bones because every bone swings on its own pivot now - counting pieces would bound
        // nothing. It is applied a piece at a time, though, and never inside one: cutting the list
        // at a bone count splits whatever piece straddles the cut, leaving half a panel simulated
        // and half of it rigid. Those two halves answer differently to the same body motion, which
        // is a panel tearing apart down the middle - and it is silent, because the count that was
        // capped is not a number anyone sees. Whole pieces are dropped, and the number is reported.
        //
        // Both sources are capped the same way, and that is a correction: an authored part list is
        // not structureless just because it is flat. On the shipped maid it is twenty-four panels
        // and on the wine fox a tail of seven bones, and a flat cut at the cap puts the cut inside a
        // piece exactly as often as it does on the classified path - which is what "some panels move
        // and the ones beside them do not" looks like. Whole pieces, both ways, one counter.
        int cap = YsmPhysicsTuning.maxChains();
        if (cap <= 0) {
            return new Model(new Segment[0], Source.NONE, 0);
        }
        int[] dropped = {0};
        if (selected.isEmpty()) {
            selected = selectBones(model.bones, ownsGeometry, cap, dropped);
        } else {
            selected = capPieces(model.bones, selected, cap, dropped);
        }
        if (selected.isEmpty()) {
            return new Model(new Segment[0], Source.NONE, 0);
        }

        Map<String, YsmPhysicsBinding.Part> bindings = YsmPhysicsBinding.byName(
                model.physicsParts == null ? List.of() : model.physicsParts);

        List<Draft> drafts = new ArrayList<>(selected.size());
        for (int boneIndex : selected) {
            Draft draft = buildSegment(model, boneIndex, vertices, geometry, partOrdinals, bindings,
                    ownsGeometry, fallbackFrequency, fallbackDamping);
            if (draft != null) {
                drafts.add(draft);
            }
        }
        if (drafts.isEmpty()) {
            return new Model(new Segment[0], Source.NONE, dropped[0]);
        }

        // Parent links are resolved here, once every draft exists: a bone that produced no
        // segment must not be referenced, and the answer differs depending on which of its
        // ancestors survived.
        Map<Integer, Integer> segmentOfBone = new HashMap<>();
        for (int i = 0; i < drafts.size(); i++) {
            segmentOfBone.put(drafts.get(i).boneIndex(), i);
        }
        Segment[] segments = new Segment[drafts.size()];
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            int parent = resolveParent(draft, drafts, segmentOfBone);
            // The swing limit is decided HERE, from the parent link that actually survived, and
            // that timing is the whole point. It used to be decided while the draft was built, from
            // the nearest ancestor that *could* be a segment - which is a different bone whenever
            // the parent is later dropped for having no lever of its own. Every panel top of a
            // panel skirt is exactly that case: it hangs off a container bone ("FM", "FrontClothe")
            // that carries no usable geometry, so the draft was given the loose segment limit and
            // then reported itself as a root. The shipped log says so in as many words -
            // "FM1(name,root,... 41.2deg/60.0chain)" - a root running at the segment limit, which
            // is three times the firm limit a base is supposed to have.
            float limit = swingLimit(parent >= 0, draft.joint() == JOINT_TORSO, maxAngle, maxAngleRoot);
            segments[i] = new Segment(draft.boneIndex(), draft.boneName(), draft.joint(),
                    draft.bindPivot(), draft.bindRest(), draft.lever(), draft.radius(), draft.mass(),
                    draft.frequency(), draft.coefficient(), limit, parent,
                    draft.parts(), draft.authored(), NO_NEIGHBOURS);
        }
        wireNeighbours(segments);
        return new Model(segments, authored ? Source.AUTHORED : Source.BONE_NAMES, dropped[0]);
    }

    /** No neighbours, for a segment whose piece is the only thing it hangs with. */
    private static final int[] NO_NEIGHBOURS = new int[0];

    /**
     * Apply the cap to a declared part list, a whole piece at a time.
     *
     * <p>An authored part list is flat - a list of bone names - but the bones in it are not
     * structureless: they hang off one another in the skeleton, so the same subtree rule that
     * {@link #selectBones} applies can be applied here. What it buys is that a piece is never cut:
     * the alternative, taking the first {@code cap} names, puts the cut inside whatever piece
     * straddles it, and the bone below the cut then hangs off a bone that is not simulated any more -
     * half a panel swinging, half of it rigid, with nothing in the log to say which half or why.
     *
     * <p>The first piece is always kept, even when it alone exceeds the cap. That is the same rule
     * {@link #selectBones} already had, and it is there so a small cap on a model with one long
     * piece simulates something rather than nothing - an empty selection looks exactly like a model
     * with no physics at all.
     *
     * @param bones      the model's bone table, for the parent links
     * @param declared   the bones the author declared, in the model's own order
     * @param cap        the most bones that may be simulated
     * @param droppedOut receives the number of whole pieces left out, for the log
     */
    static List<Integer> capPieces(YSMRuntimeModel.BoneRt[] bones, List<Integer> declared,
                                   int cap, int[] droppedOut) {
        List<Integer> kept = new ArrayList<>(declared.size());
        boolean first = true;
        for (List<Integer> piece : piecesOf(bones, declared)) {
            if (first || kept.size() + piece.size() <= cap) {
                kept.addAll(piece);
                first = false;
            } else if (droppedOut != null && droppedOut.length > 0) {
                // Counted as pieces, not bones: "the cap left out four panels" is a sentence about
                // a garment, and "the cap left out twelve bones" is a number nobody can act on.
                droppedOut[0]++;
            }
        }
        return kept;
    }

    /**
     * Group a declared bone list into the pieces it is made of: the connected groups of declared
     * bones under the skeleton, in the order the list names them.
     *
     * <p>Connectivity is by ancestry within the list: walking up from a bone to the first declared
     * ancestor it has. Bones that share such an ancestor are one piece, which is what a strand in
     * three bones ({@code RB -> RB2 -> RB3}) is, and what a fan of panels under one bracket is not -
     * each panel reaches the bracket only through a bone the author did not declare, so each panel
     * is its own group and the cap can drop one without touching its neighbour.
     *
     * <p>The walk is bounded, and it stops at a parent the table does not contain, because a damaged
     * or truncated table can name one and this runs on the render thread's path to a model's first
     * frame.
     */
    static List<List<Integer>> piecesOf(YSMRuntimeModel.BoneRt[] bones, List<Integer> declared) {
        List<List<Integer>> pieces = new ArrayList<>();
        if (bones == null || declared == null || declared.isEmpty()) {
            return pieces;
        }
        Map<Integer, Integer> slotOfBone = new HashMap<>();
        for (int slot = 0; slot < declared.size(); slot++) {
            slotOfBone.putIfAbsent(declared.get(slot), slot);
        }
        int[] group = new int[declared.size()];
        for (int slot = 0; slot < group.length; slot++) {
            group[slot] = slot;
        }
        for (int slot = 0; slot < declared.size(); slot++) {
            int bone = declared.get(slot);
            if (bone < 0 || bone >= bones.length || bones[bone] == null) {
                continue;
            }
            int guard = 0;
            for (int parent = bones[bone].parent;
                 parent >= 0 && parent < bones.length && bones[parent] != null
                         && guard++ <= bones.length;
                 parent = bones[parent].parent) {
                Integer parentSlot = slotOfBone.get(parent);
                if (parentSlot != null) {
                    join(group, slot, parentSlot);
                    break;
                }
            }
        }
        Map<Integer, List<Integer>> byRoot = new java.util.LinkedHashMap<>();
        for (int slot = 0; slot < group.length; slot++) {
            byRoot.computeIfAbsent(find(group, slot), key -> new ArrayList<>()).add(declared.get(slot));
        }
        pieces.addAll(byRoot.values());
        return pieces;
    }

    /** Union by root: {@code find} chases to the representative, {@code join} points one at the other. */
    private static void join(int[] group, int a, int b) {
        int rootA = find(group, a);
        int rootB = find(group, b);
        if (rootA != rootB) {
            group[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
        }
    }

    private static int find(int[] group, int slot) {
        int current = slot;
        int guard = 0;
        while (group[current] != current && guard++ <= group.length) {
            current = group[current];
        }
        return current;
    }

    /**
     * Whether the geometry a segment would be measured from is the bone's <b>own</b>.
     *
     * <p>Two answers have to agree, and they come from two walks of the mesh:
     *
     * <ul>
     *   <li>The mesh carries a part named after this bone ({@code y/<name>}), so the geometry on
     *       screen is drawn by this bone's own transform and by no other. A bone with no part of its
     *       own has nothing for a segment to write, and the mesh writer emits parts only for bones
     *       that carry quads - so a bone can be named by the author's physics animation and still
     *       have no geometry at all.</li>
     *   <li>That part holds vertices that survive the default-form visibility pass, so the bone's
     *       centre of mass and thickness are measured on the geometry that is actually drawn rather
     *       than on a variant body or a hidden accessory.</li>
     * </ul>
     *
     * <p>A bone that fails either test is not a piece of anything: the lever, the rest direction and
     * the collision radius have to be measured from the piece the segment moves. A bone given a
     * <i>descendant's</i> centroid - which is what this used to do - swings on a lever that points
     * at someone else's geometry, writes its delta to no part at all, and still spends the piece's
     * chain allowance, so the strands below it are curtailed by a bone that cannot move anything.
     */
    static boolean ownsItsGeometry(int boneIndex, Map<Integer, float[]> geometry,
                                   Map<Integer, int[]> partOrdinals) {
        int[] parts = partOrdinals == null ? null : partOrdinals.get(boneIndex);
        if (parts == null || parts.length == 0) {
            return false;
        }
        float[] entry = geometry == null ? null : geometry.get(boneIndex);
        return entry != null && entry[3] >= MIN_VERTICES;
    }

    /**
     * Whether Epic Fight owns this bone's pose, which leaves nothing of it for us to swing.
     *
     * <p>A directly mapped bone is one whose own name is a body part - {@code UpBody},
     * {@code RightArm}, {@code Head} - and Epic Fight animates it directly. Writing a second
     * rotation on top of a pose someone else is driving is not secondary motion, it is a body part
     * being turned twice: the coat sleeve is drawn by the arm joint, and rotating it again about its
     * own pivot moves the arm. The name classifier has always excluded mapped bones
     * ({@code YsmPhysicsChains#build}); this is the same rule stated where a <i>declared</i> part
     * list is filtered, so an author's animation naming a mapped bone cannot smuggle one in.
     */
    static boolean poseBelongsToEpicFight(YSMRuntimeModel.BoneRt bone) {
        return bone == null || bone.mapped;
    }

    /**
     * How near two panels' pivots must be, in bind space blocks, to count as sewn together.
     *
     * <p>A few centimetres: the panels of a skirt are modelled side by side around the waist, so
     * their tops are close together while their hems spread apart. Coupling on the pivot rather
     * than on the geometry is deliberate - what has to move together is where the fabric is
     * attached, not where it happens to hang.
     */
    private static final float NEIGHBOUR_RADIUS = 0.18F;

    /**
     * How alike two panels' rest directions must be to count as sewn together.
     *
     * <p>Roughly forty degrees. A skirt's panels fan out around the waist, so neighbours differ by
     * a modest angle; the front and back panels differ by much more and should not be coupled, or
     * the whole skirt would average itself into a single rigid cone.
     */
    private static final float NEIGHBOUR_MAX_ANGLE = 0.7F;

    /** At most this many neighbours each, so the relaxation stays a short fixed loop. */
    private static final int NEIGHBOUR_COUNT = 4;

    /**
     * Records which segments are sewn to which, from the model's own bind geometry.
     *
     * <p>This is what makes a panel skirt behave like a skirt: without it every panel is an
     * independent pendulum, and the shipped numbers show what that produces - a 20.0 degree front
     * panel beside a 5.1 degree one, which on screen is not a curve but a gap. See
     * {@code YsmMeshSecondaryMotion#relaxTowardsNeighbours} for what is done with the links.
     */
    private static void wireNeighbours(Segment[] segments) {
        for (int i = 0; i < segments.length; i++) {
            // Only the nearest few, chosen by distance, so the graph is local and bounded.
            int[] best = new int[NEIGHBOUR_COUNT];
            float[] bestDistance = new float[NEIGHBOUR_COUNT];
            java.util.Arrays.fill(best, -1);
            java.util.Arrays.fill(bestDistance, Float.MAX_VALUE);
            for (int j = 0; j < segments.length; j++) {
                if (i == j || segments[j].parent() == i || segments[i].parent() == j) {
                    // A parent and child are already tied by the chain composition; coupling them
                    // again would fight the budget that keeps a chain inside its limit.
                    continue;
                }
                float distance = segments[i].bindPivot().distance(segments[j].bindPivot());
                if (distance > NEIGHBOUR_RADIUS) {
                    continue;
                }
                float angle = YsmDynamicBoneSolver.angleBetween(
                        segments[i].bindRest(), segments[j].bindRest());
                if (angle > NEIGHBOUR_MAX_ANGLE) {
                    continue;
                }
                for (int slot = 0; slot < NEIGHBOUR_COUNT; slot++) {
                    if (distance < bestDistance[slot]) {
                        for (int shift = NEIGHBOUR_COUNT - 1; shift > slot; shift--) {
                            best[shift] = best[shift - 1];
                            bestDistance[shift] = bestDistance[shift - 1];
                        }
                        best[slot] = j;
                        bestDistance[slot] = distance;
                        break;
                    }
                }
            }
            int count = 0;
            for (int slot = 0; slot < NEIGHBOUR_COUNT; slot++) {
                if (best[slot] >= 0) {
                    count++;
                }
            }
            if (count == 0) {
                continue;
            }
            int[] neighbours = new int[count];
            System.arraycopy(best, 0, neighbours, 0, count);
            segments[i] = new Segment(segments[i].boneIndex(), segments[i].boneName(),
                    segments[i].joint(), segments[i].bindPivot(), segments[i].bindRest(),
                    segments[i].lever(), segments[i].radius(), segments[i].mass(),
                    segments[i].frequency(), segments[i].coefficient(), segments[i].maxAngle(),
                    segments[i].parent(), segments[i].parts(), segments[i].authored(), neighbours);
        }
    }

    /**
     * Resolve a draft's parent to a surviving segment index.
     *
     * <p>Falls back along the skeleton when the preferred bone produced no segment, so a
     * strand whose author-declared driver was dropped for having no lever still hangs off
     * whatever is above it rather than becoming a second chain root.
     */
    private static int resolveParent(Draft draft, List<Draft> drafts, Map<Integer, Integer> segmentOfBone) {
        Integer direct = segmentOfBone.get(draft.parentBone());
        if (direct != null && direct != segmentOfBone.get(draft.boneIndex())) {
            return direct;
        }
        return -1;
    }

    /**
     * How far a segment may swing from its rest direction, radians.
     *
     * <p>Extracted and package-private because the decision is easy to get subtly wrong and its
     * failure is invisible: a base held at three times its intended limit does not crash or log
     * anything, it just lets a whole skirt splay. Both of this method's rules were wrong at some
     * point - the firmer root limit was overridden for anything resolving to the torso, and the
     * rule was applied before the parent link was final - so it is pinned by tests.
     *
     * @param hasParent true when the segment hangs off another <b>surviving</b> segment; false
     *                  makes it the base of its piece, which is held to the firm root limit
     * @param torso     whether the segment resolves to the torso, where a panel a knee pushes may
     *                  come further up - a relaxation for strands only, never for a base
     */
    static float swingLimit(boolean hasParent, boolean torso, float maxAngle, float maxAngleRoot) {
        float limit = hasParent ? maxAngle : maxAngleRoot;
        if (hasParent && torso) {
            limit = Math.max(limit, maxAngle);
        }
        return limit;
    }

    /**
     * The least bend a chain's total allowance must give each of its joints, radians.
     *
     * <p>Fifteen degrees. A joint held below this is a joint that reads as still: on the wine fox's
     * tail, sharing one flat sixty degrees over seven bones gave 8.6 degrees each, and a tail that
     * moves 8.6 degrees per bone is a tail nobody sees move - which is the opposite of the defect
     * this whole feature exists for. The floor is what makes the allowance scale with the chain
     * instead of being eaten by it; the total cap below is what keeps a very long chain from
     * splaying.
     */
    static final float MIN_CHAIN_ANGLE_PER_JOINT = (float) Math.toRadians(15.0D);

    /**
     * The most a piece may bend as a whole, radians, however many joints it has.
     *
     * <p>One hundred and twenty degrees. A single piece of cloth or a ponytail is allowed sixty by
     * the per-joint configuration; a chain of bones is not one piece of cloth - its joints add up,
     * and the shape a viewer reads is the <i>sum</i> of them - so a chain is allowed more in total
     * than any one of its joints. This is the ceiling on that: past it the total stops growing, and
     * a piece long enough to reach it does so by giving each joint the floor above rather than by
     * bending further and further at the end.
     */
    static final float MAX_CHAIN_ANGLE_TOTAL = (float) Math.toRadians(120.0D);

    /**
     * How far a piece made of {@code joints} bones may bend as a whole, radians.
     *
     * <pre>
     *   limit = min(maxAngle x joints, max(MIN_PER_JOINT x joints, MAX_CHAIN_TOTAL))
     * </pre>
     *
     * <h2>Why the piece's total is not the per-joint limit</h2>
     *
     * <p>The configuration's {@code secondaryMotionMaxAngleDegrees} (sixty) is what <b>one</b> piece
     * of cloth may swing. Treating it as the whole chain's total - which is what this did first -
     * makes a chain stiffer the longer it is: seven bones sharing sixty degrees get 8.6 each, so the
     * tail barely moves, and the physical intuition is the other way round (a real tail bends more,
     * not less, the further from the root you look).
     *
     * <p>So the total grows with the joint count, with two bounds on it:
     *
     * <ul>
     *   <li><b>{@link #MIN_CHAIN_ANGLE_PER_JOINT} per joint</b>, so no chain is ever shared out into
     *       invisibility. This is what binds once a chain is long enough that the cap below would
     *       give it less than fifteen degrees a joint.</li>
     *   <li><b>{@link #MAX_CHAIN_ANGLE_TOTAL} in all</b>, so a very long chain does not simply
     *       multiply: without it, twelve joints of sixty would be a seven-hundred-degree allowance,
     *       which is not a piece of cloth bending, it is a piece of cloth folding through itself.</li>
     * </ul>
     *
     * <p>The outer {@code min} is the configuration's own word on the matter: a piece may never be
     * allowed more in total than its joints would each be allowed separately. That is what keeps a
     * model configured with a small {@code secondaryMotionMaxAngleDegrees} - ten degrees, say -
     * from having its long chains handed a large allowance that its per-joint limits would never
     * grant.
     *
     * <p>Numbers under the shipped configuration (per-joint 60, root 20, cap 120, floor 15):
     *
     * <pre>
     *   1 joint  min(60,  max(15, 120)) = 60
     *   2 joints min(120, max(30, 120)) = 120    - root 20, hem 60: the shipped panel, unchanged
     *   3 joints min(180, max(45, 120)) = 120    - root 20, then 50 and 50
     *   7 joints min(420, max(105, 120)) = 120   - 60/7 plus the floor: 17.1 each, not 8.6
     *   9 joints min(540, max(135, 120)) = 135   - the floor has taken over from the cap
     * </pre>
     *
     * @param joints           the number of <b>resolved segments</b> in the piece - the bones that
     *                         are actually simulated, not the bones in the skeleton's subtree. A
     *                         decoration hanging off the garment that is not a segment must not
     *                         raise the allowance of the chain it hangs near.
     * @param maxAnglePerJoint the configuration's per-joint limit, radians
     */
    static float chainLimitFor(int joints, float maxAnglePerJoint) {
        if (joints <= 0 || !Float.isFinite(maxAnglePerJoint) || maxAnglePerJoint <= 0.0F) {
            return 0.0F;
        }
        float ownTotal = maxAnglePerJoint * joints;
        float floorTotal = MIN_CHAIN_ANGLE_PER_JOINT * joints;
        return Math.min(ownTotal, Math.max(floorTotal, MAX_CHAIN_ANGLE_TOTAL));
    }

    /**
     * How far one joint of a chain may turn, given how much of the piece's total bend is already
     * used up by the joints above it and how many joints are left to share the rest.
     *
     * <h2>Two ceilings, and the mistake was letting one of them starve the chain</h2>
     *
     * <p>{@code ownLimit} is this joint's own allowance - firm at the base of a piece
     * ({@code maxAngleRoot}, 20 degrees in the shipped config) and loose for the strands below it
     * ({@code maxAngle}, 60). {@code chainLimit} is how far the piece <i>as a whole</i> may bend,
     * and it is <b>not</b> the same number: it is the piece's total, scaled by how many joints the
     * piece has ({@link #chainLimitFor}), and it is the number the per-joint allowance is shared out
     * of. Using the per-joint sixty as the piece's total - which is what this did first - made a
     * chain stiffer the longer it was: seven bones sharing sixty degrees get 8.6 each.
     *
     * <p>What it also did was hand each joint everything that was left: {@code min(ownLimit,
     * chainLimit - used)}. On a short piece that is right, and it is why the shipped skirt works. On
     * a long one it is a first-come-first-served queue, and the queue is ordered root first - so the
     * joints near the root eat the whole allowance and every joint below runs out. The wine fox's
     * seven-bone tail is the measured case: at 18.4 degrees a bone the chain reaches 73.6 degrees by
     * its third joint, and from the fourth on {@code max(0, 60 - 73.6)} is <b>zero</b> - the bone is
     * slerped back to no swing at all, on every frame, forever. Four of the tail's seven bones could
     * not move, and no log said why: {@code own=0.0deg, axis=(0,0,0)} looks exactly like a bone that
     * was never classified.
     *
     * <h2>The rule: the piece's bend is shared out, not queued for</h2>
     *
     * <p>What is left of the piece's allowance is divided by the number of joints that still have to
     * bend inside it - this one and every joint below it - and that share is this joint's ceiling,
     * under its own limit:
     *
     * <pre>
     *   allowed = min(ownLimit, max(0, chainLimit - usedByAncestors) / jointsLeft)
     * </pre>
     *
     * <p>Chosen over "bound only this joint's angle relative to its parent" because that rule has no
     * ceiling on the piece at all - seven joints of sixty degrees each is a fan, not a tail - and the
     * requirement is that the <i>shape</i> of the piece stays bounded. Chosen over "scale the whole
     * chain down after the fact" because a second pass over a chain whose deltas are already composed
     * root-first would have to rewrite them, and the composition is what keeps a nested piece from
     * tearing.
     *
     * <p><b>Why a joint can never be frozen by its ancestors.</b> Each joint takes at most
     * {@code remaining / jointsLeft}, so what is left for the joints below is at least
     * {@code remaining x (jointsLeft - 1) / jointsLeft}: a positive quantity, strictly decreasing
     * but never zero, until the last joint of the chain. A joint with anything left below it
     * therefore always has an allowance above zero. On top of that the share is floored at
     * {@link #MIN_CHAIN_ANGLE_PER_JOINT}, so the promise does not depend on the caller having
     * handed in a total worthy of the chain: a joint is never allowed less than the floor (unless
     * its own limit is smaller, which the {@code min} keeps), however small the total it is sharing
     * or however much of it the joints above have spent. The sum of the joints' angles is bounded by
     * {@code chainLimit} whenever the total was scaled to the chain (see {@link #chainLimitFor}, and
     * that is the only caller the frame path has); the floor is what stops a caller that passes a
     * flat per-joint limit as the total - which is what this seam's earlier version meant by that
     * argument - from starving the far end of a long chain.
     *
     * <h2>What it does to the shipped configuration (base 20, strand 60, piece limit from
     * {@link #chainLimitFor})</h2>
     *
     * <ul>
     *   <li><b>A one-joint piece:</b> limit 60, so {@code min(20, 60/1)} = 20 - the firm base itself,
     *       and the scaling never hands a lone joint more than its own limit.</li>
     *   <li><b>A two-joint piece</b> - a panel top with one strand, and most of the maid's garment:
     *       limit 120, so the base takes {@code min(20, 60)} = 20 exactly as it always did, and the
     *       strand is capped by its own 60 rather than by the chain. The strand's allowance grew from
     *       40 to 60, and that is invisible on this model: its panels were measured at 20.0, 12.1,
     *       5.1 and 4.6 degrees, all far under 40, so nothing that used to be clamped is not clamped
     *       now. The base - the number that decides how the skirt hangs - is bit for bit what it
     *       was.</li>
     *   <li><b>A three-joint piece:</b> limit 120, so 20 then 50 then 50, and the hem leads the waist
     *       by design rather than by accident.</li>
     *   <li><b>A seven-joint tail:</b> limit 120, so 17.1 degrees a bone - above the fifteen-degree
     *       floor, and where the flat sixty-degree version gave 8.6, which is a tail that does not
     *       visibly move. This is the number the change was for.</li>
     * </ul>
     *
     * @param ownLimit         this joint's own allowance, radians
     * @param chainLimit       how far the whole piece may bend from its rest, radians; the piece's
     *                         scaled total in the frame path ({@link #chainLimitFor})
     * @param usedByAncestors  what this joint's ancestors have already taken, radians: the sum of
     *                         their own granted angles, which is what the allowance above is spent
     *                         out of. Deliberately not the composed chain angle - rotations about
     *                         different axes compose to something larger than their sum, so a budget
     *                         measured that way is spent faster than the joints spend it.
     * @param jointsLeft       how many joints of this piece are at or below this one, this joint
     *                         included; at least 1
     */
    static float chainAllowance(float ownLimit, float chainLimit, float usedByAncestors, int jointsLeft) {
        float remaining = Math.max(0.0F, chainLimit - Math.max(0.0F, usedByAncestors));
        float share = Math.max(remaining / Math.max(1, jointsLeft), MIN_CHAIN_ANGLE_PER_JOINT);
        return Math.min(ownLimit, share);
    }

    /**
     * The bones to simulate, out of the model's hanging pieces, under a cap on how many bones may
     * be simulated at once.
     *
     * <p>Each accepted piece is the top of the piece its name identified plus every bone under it
     * that carries geometry - the subtree rather than the top alone, because the dynamics are per
     * bone now and dragging the whole piece around one pivot is precisely the rigid-plank behaviour
     * being replaced.
     *
     * <p>Two rules, and both of them were learned from a garment that came apart:
     *
     * <ol>
     *   <li><b>Every candidate starts a piece, not just the ones the limit rule calls a root.</b>
     *       {@link YsmPhysicsChains#isChainRoot} answers "is the bone above me itself a
     *       hangs-kind of bone", which decides how firmly a piece is held - it is not a statement
     *       about where pieces begin. Gating on it drops every piece whose parent happens to be
     *       named {@code RightClothe}, {@code BackClothe}, {@code Hair} ..., and keeps the ones
     *       whose parent is named {@code RB} or {@code LM2}. On the shipped maid that is exactly
     *       the right-hand and back panels left rigid while the left and front ones swing: half a
     *       skirt moving and half of it bolted down, which reads as the other half having been
     *       dragged off to one side.</li>
     *   <li><b>A bone is simulated once.</b> Nested candidates - {@code RB} and {@code RB2} and
     *       {@code RB3} can all be candidates, since each is asked about its own parent - each
     *       collect the piece they hang in, and the outer one's piece contains the inner one's
     *       bones. Collecting them into separate lists and concatenating them puts the same bone
     *       in the segment list twice; both copies then write their delta to the same mesh part,
     *       and the second write is the one that shows. Deduplicating against what has already
     *       been taken is what keeps the count honest, and the count is what the cap spends.</li>
     * </ol>
     *
     * @param bones           the model's bone table
     * @param carriesGeometry which bones carry mesh, so a container can be told from a panel
     * @param cap             the most bones that may be simulated
     * @param droppedOut      receives the number of pieces left out by the cap, for the log
     */
    static List<Integer> selectBones(YSMRuntimeModel.BoneRt[] bones, IntPredicate carriesGeometry,
                                     int cap, int[] droppedOut) {
        List<Integer> selected = new ArrayList<>();
        List<Integer> piece = new ArrayList<>();
        for (YsmPhysicsChains.Chain chain : YsmPhysicsChains.build(bones, carriesGeometry)) {
            piece.clear();
            collectPiece(bones, chain.boneIndex(), carriesGeometry, piece);
            piece.removeIf(selected::contains);
            if (piece.isEmpty()) {
                continue;
            }
            if (selected.isEmpty() || selected.size() + piece.size() <= cap) {
                selected.addAll(piece);
            } else if (droppedOut != null && droppedOut.length > 0) {
                droppedOut[0]++;
            }
        }
        return withoutBrackets(bones, selected);
    }

    /**
     * Drop the bones that only hold other swinging bones apart.
     *
     * <p>A garment is modelled as a bracket per group - {@code FM} over {@code FM1} and {@code FM2},
     * {@code LB} over {@code LB2} and {@code LB3} - and the bracket is what the author rotates when
     * they want the whole group to move. It is not a piece of cloth with a hang of its own: swinging
     * it as well as the panels under it turns the group twice, once at the bracket and once at the
     * panel, and the two rotations compose into a position nothing was ever meant to be in. On the
     * shipped maid that is exactly what the log showed once every candidate was taken -
     * {@code FL FM FR LB LF LM} listed beside the panels they hold - and the same double rotation is
     * what "the parts are in the wrong place" is.
     *
     * <p>The test is a <i>branch</i>: two or more of this bone's own children are simulated. It is
     * deliberately not "has descendants", which would throw away the top of every chain -
     * {@code RB -> RB2 -> RB3} is one panel in three bones and all three must swing, while
     * {@code FM} over two panels is a bracket and must not. Dropping a bracket is safe for the
     * panels beneath it: each one's parent link already resolves to the nearest simulated ancestor,
     * and a bracket was never simulated.
     */
    private static List<Integer> withoutBrackets(YSMRuntimeModel.BoneRt[] bones, List<Integer> selected) {
        Map<Integer, Integer> childrenOf = new HashMap<>();
        for (int index : selected) {
            int parent = bones[index] == null ? -1 : bones[index].parent;
            if (parent >= 0) {
                childrenOf.merge(parent, 1, Integer::sum);
            }
        }
        List<Integer> kept = new ArrayList<>(selected.size());
        for (int index : selected) {
            Integer children = childrenOf.get(index);
            if (children == null || children < 2) {
                kept.add(index);
            }
        }
        return kept;
    }

    private static void collectPiece(YSMRuntimeModel.BoneRt[] bones, int top,
                                     IntPredicate carriesGeometry, List<Integer> out) {
        // The veto holds for every bone of a piece, not only for the bone that starts it. A
        // placeholder such as `bone5` is swept in as a *descendant* of a garment container, so
        // testing the candidates alone never sees it - which is exactly how the shipped maid ended
        // up with a one-block-long unnamed bone among its skirt's pieces.
        if (!out.contains(top) && bones[top] != null && !YsmPhysicsChains.isVetoed(bones[top].name)) {
            out.add(top);
        }
        for (int i = 0; i < bones.length; i++) {
            if (i == top || bones[i] == null || out.contains(i) || !carriesGeometry.test(i)
                    || YsmPhysicsChains.isVetoed(bones[i].name)) {
                continue;
            }
            int guard = 0;
            for (int parent = bones[i].parent; parent >= 0 && guard++ <= bones.length; parent = bones[parent].parent) {
                if (parent == top) {
                    out.add(i);
                    break;
                }
            }
        }
    }

    /**
     * One segment, or null when this bone cannot be one.
     *
     * <p>Three tests stand between a bone and a segment, and each of them is a way a piece of cloth
     * ends up being moved by something other than itself:
     *
     * <ul>
     *   <li><b>It has a joint</b> ({@code bone.joint >= 0}), or the pose carries no geometry of it
     *       and the simulation would have nothing to read a pivot from.</li>
     *   <li><b>Epic Fight does not own its pose</b> ({@link #poseBelongsToEpicFight}). A mapped
     *       bone is a body part, and turning a body part a second time about its own pivot moves the
     *       limb rather than the cloth on it.</li>
     *   <li><b>Its geometry is its own</b> ({@link #ownsItsGeometry}). The lever, the rest direction
     *       and the collision radius are measured from it, and a bone with no geometry of its own
     *       used to be given a descendant's centroid instead - which is a lever pointing at another
     *       bone's geometry, a delta written to no part at all, and a share of the piece's chain
     *       allowance spent on a bone that cannot move anything.</li>
     * </ul>
     */
    private static Draft buildSegment(YSMRuntimeModel model, int boneIndex,
                                      Map<Integer, List<Vector3f>> vertices, Map<Integer, float[]> geometry,
                                      Map<Integer, int[]> partOrdinals,
                                      Map<String, YsmPhysicsBinding.Part> bindings,
                                      IntPredicate ownsGeometry,
                                      float fallbackFrequency, float fallbackDamping) {
        YSMRuntimeModel.BoneRt bone = model.bones[boneIndex];
        if (bone == null || bone.joint < 0 || poseBelongsToEpicFight(bone)
                || !ownsItsGeometry(boneIndex, geometry, partOrdinals)) {
            return null;
        }
        Vector3f pivot = bindPivot(model, bone);
        if (pivot == null || !YsmDynamicBoneSolver.isFinite(pivot)) {
            return null;
        }
        Vector3f centroid = centroid(geometry.get(boneIndex));
        if (centroid == null) {
            return null;
        }
        Vector3f rest = new Vector3f(centroid).sub(pivot);
        float lever = rest.length();
        if (!Float.isFinite(lever) || lever < MIN_LEVER) {
            // The geometry sits on the pivot: this bone cannot swing, and pretending it can
            // would hand the solver a zero-length lever to divide by.
            return null;
        }

        YsmPhysicsBinding.Part binding = bindings.get(bone.name);
        float frequency = binding != null ? (float) binding.frequency() : fallbackFrequency;
        float coefficient = binding != null ? (float) binding.coefficient() : fallbackDamping;
        if (!Float.isFinite(frequency) || frequency <= 0.0F) {
            frequency = fallbackFrequency;
        }
        if (!Float.isFinite(coefficient) || coefficient < 0.0F) {
            coefficient = fallbackDamping;
        }

        int parentBone = nearestSimulatedAncestor(model, boneIndex, bindings, ownsGeometry);

        int[] parts = partOrdinals.getOrDefault(boneIndex, new int[0]);
        // The swing limit is not decided here: the parent link is only provisional until the
        // surviving segments are known, and the limit has to follow the link that survives. See
        // the second pass in build().
        return new Draft(boneIndex, bone.name, bone.joint, pivot, rest, lever,
                radiusFor(vertices.get(boneIndex), pivot, rest), mass(geometry.get(boneIndex)),
                frequency, coefficient, 0.0F, parentBone, parts, binding != null);
    }

    /**
     * The bone's pivot in the mesh's model bind space.
     *
     * <p>{@code bindWorld} is the accumulated {@code T(p) R T(-p)} chain the mesh writer
     * used, so its image of the bone's own pivot is the point the geometry was placed at -
     * which is what a part delta has to rotate about. The pivot is scaled the way the
     * vertices were ({@code width_scale} on x and z, {@code height_scale} on y), because
     * the runtime JSON stores the raw authored pivot while the mesh carries the scaled one.
     */
    private static Vector3f bindPivot(YSMRuntimeModel model, YSMRuntimeModel.BoneRt bone) {
        float scaleX = model.widthScale;
        float scaleY = model.heightScale;
        if (!Float.isFinite(scaleX) || scaleX <= 0.0F) {
            scaleX = 1.0F;
        }
        if (!Float.isFinite(scaleY) || scaleY <= 0.0F) {
            scaleY = 1.0F;
        }
        return pivotInMeshSpace(bone.bindWorld, bone.px, bone.py, bone.pz, scaleX, scaleY);
    }

    /**
     * A bone's pivot in the mesh's own space - the space the writer bakes the vertices in, and the
     * space a part transform acts in.
     *
     * <p>The scale goes on last, and that order is the whole of this method. The writer places a
     * vertex as {@code scale x (bindWorld x corner)}: the bone chain acts in the model's own
     * unscaled units and the model is scaled once, at the end, about the origin. Scaling the pivot
     * <i>before</i> the chain - {@code bindWorld x (scale x pivot)} - names a different point
     * whenever any bone on the way has a rest rotation, because such a bone's own
     * {@code T(p)RT(-p)} carries a translation that is then scaled while it should not be.
     *
     * <p>What that costs is not a wrong angle but a wrong <i>centre</i>: the delta is
     * {@code T(P)R T(-P)}, so the point it holds still is P. If P is not the point the geometry
     * hangs from, the attachment moves - by {@code (R - I) x error}, which grows with the swing -
     * and the piece leaves the garment it belongs to. On the shipped maid the error is 5.6 cm, and
     * mirrored on the left side of the model, so the two sides of the skirt pull in opposite
     * directions: the same defect read twice, as a tear at the waistband.
     */
    static Vector3f pivotInMeshSpace(Matrix4f bindWorld, float px, float py, float pz,
                                     float scaleX, float scaleY) {
        Vector3f pivot = new Vector3f(px, py, pz);
        if (!YsmDynamicBoneSolver.isFinite(pivot)) {
            return null;
        }
        if (bindWorld != null) {
            bindWorld.transformPosition(pivot);
        }
        pivot.mul(scaleX, scaleY, scaleX);
        return YsmDynamicBoneSolver.isFinite(pivot) ? pivot : null;
    }

    /** The centroid of a geometry entry, or null. */
    private static Vector3f centroid(float[] entry) {
        if (entry == null || entry[3] < MIN_VERTICES) {
            return null;
        }
        return new Vector3f(entry[0], entry[1], entry[2]);
    }

    /**
     * The nearest ancestor bone that can itself be a segment, or -1.
     *
     * <p>The author's {@code follows} link is preferred when it names such a bone, because
     * it is what the model author actually wrote: the wine fox drives {@code BackHairB2}
     * from {@code BackHairA1} through a timeline variable, and those two are not always
     * parent and child in the skeleton. The skeleton is the fallback, and it is all the
     * name-classifier path has.
     */
    private static int nearestSimulatedAncestor(YSMRuntimeModel model, int boneIndex,
                                                Map<String, YsmPhysicsBinding.Part> bindings,
                                                IntPredicate ownsGeometry) {
        YsmPhysicsBinding.Part binding = bindings.get(model.bones[boneIndex].name);
        if (binding != null && !binding.follows().isEmpty()) {
            Integer followed = model.boneIndex.get(binding.follows());
            if (followed != null && followed != boneIndex && ownsGeometry.test(followed)
                    && !isUnder(model.bones, followed, boneIndex)) {
                return followed;
            }
        }
        int guard = 0;
        for (int parent = model.bones[boneIndex].parent; parent >= 0 && guard++ <= model.bones.length;
             parent = model.bones[parent].parent) {
            if (ownsGeometry.test(parent)) {
                return parent;
            }
        }
        return -1;
    }

    /** Geometry per bone: [cx, cy, cz, vertexCount]. Thickness is measured in {@link #radiusFor}. */
    static Map<Integer, float[]> geometryByBone(Map<Integer, List<Vector3f>> vertices) {
        Map<Integer, float[]> out = new HashMap<>();
        if (vertices == null) {
            return out;
        }
        for (Map.Entry<Integer, List<Vector3f>> entry : vertices.entrySet()) {
            List<Vector3f> list = entry.getValue();
            if (list == null || list.isEmpty()) {
                continue;
            }
            Vector3f centre = new Vector3f();
            for (Vector3f vertex : list) {
                centre.add(vertex);
            }
            centre.div(list.size());
            out.put(entry.getKey(), new float[]{centre.x, centre.y, centre.z, list.size()});
        }
        return out;
    }

    /**
     * The mesh part ordinals carrying each bone's geometry.
     *
     * <p>Ordinals rather than names: this runs per drawn frame, and resolving a name
     * through a hash map every time is the kind of per-frame cost this feature has to be
     * cheap enough for a phone to pay. Computed once per mesh and cached against the mesh.
     */
    static Map<Integer, int[]> partOrdinalsByBone(YSMMesh mesh, YSMRuntimeModel model) {
        Map<Integer, int[]> cached = PART_ORDINALS.get(mesh);
        if (cached != null) {
            return cached;
        }
        Map<Integer, List<Integer>> collected = new HashMap<>();
        String prefix = EFMeshJsonWriter.BONE_PART_PREFIX;
        int ordinal = 0;
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (partName.startsWith(prefix)) {
                Integer boneIndex = model.boneIndex.get(partName.substring(prefix.length()));
                if (boneIndex != null && boneIndex >= 0 && boneIndex < model.bones.length) {
                    collected.computeIfAbsent(boneIndex, key -> new ArrayList<>()).add(ordinal);
                }
            }
            ordinal++;
        }
        Map<Integer, int[]> out = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : collected.entrySet()) {
            List<Integer> list = entry.getValue();
            int[] array = new int[list.size()];
            for (int i = 0; i < array.length; i++) {
                array[i] = list.get(i);
            }
            out.put(entry.getKey(), array);
        }
        PART_ORDINALS.put(mesh, out);
        return out;
    }

    /** mesh -&gt; bone -&gt; part ordinals. Weak keys: a mesh is dropped when its model is unloaded. */
    private static final Map<YSMMesh, Map<Integer, int[]>> PART_ORDINALS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** mesh -&gt; bone -&gt; vertices. Weak keys, same reason. */
    private static final Map<YSMMesh, Map<Integer, List<Vector3f>>> VERTICES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static Map<Integer, List<Vector3f>> verticesByBone(YSMMesh mesh, YSMRuntimeModel model) {
        Map<Integer, List<Vector3f>> cached = VERTICES.get(mesh);
        if (cached != null) {
            return cached;
        }
        Map<Integer, List<Vector3f>> out = new HashMap<>();
        float[] positions = mesh == null ? null : mesh.positions();
        // Bones the model's own scripts collapse to nothing in its default form - variant
        // bodies, weapons, expressions, accessories - are still in the mesh, still carry
        // vertices, and are only marked hidden at draw time. Their vertices sit wherever that
        // variant was authored, which for a different form of the same character can be most of
        // a block away, so a centre of mass averaged over them points somewhere the visible
        // piece is not: the lever is wrong, the rest direction is wrong, and the piece is then
        // swung about a pivot it does not have. YsmBindArmature excludes exactly these bones
        // from its own pivot computation for the same reason; this is the same rule.
        java.util.Set<String> hidden = model.defaultHiddenBoneNames();
        if (positions != null) {
            String prefix = EFMeshJsonWriter.BONE_PART_PREFIX;
            for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
                String partName = entry.getKey();
                if (!partName.startsWith(prefix)) {
                    continue;
                }
                Integer boneIndex = model.boneIndex.get(partName.substring(prefix.length()));
                if (boneIndex == null || boneIndex < 0 || boneIndex >= model.bones.length) {
                    continue;
                }
                if (hidden.contains(model.bones[boneIndex].name)) {
                    continue;
                }
                MeshPart part = entry.getValue();
                if (part == null || part.getVertices() == null) {
                    continue;
                }
                List<Vector3f> list = out.computeIfAbsent(boneIndex, key -> new ArrayList<>());
                for (VertexBuilder builder : part.getVertices()) {
                    int position = builder.position * 3;
                    if (position + 2 < positions.length) {
                        list.add(new Vector3f(positions[position], positions[position + 1], positions[position + 2]));
                    }
                }
            }
        }
        VERTICES.put(mesh, out);
        return out;
    }

    /** Forget every per-mesh index (model reload, world leave). */
    public static void clear() {
        PART_ORDINALS.clear();
        VERTICES.clear();
    }

    /**
     * A segment's collision radius, in blocks: the piece's own thickness, across the direction it
     * hangs.
     *
     * <p>Half the geometry's <i>width</i>, and the correction is which width. This used to be the
     * median distance from the vertices to their centroid, which measures the piece's <b>length</b>
     * as much as its thickness: on a strand - a tail, a braid, a long lock of hair - most vertices
     * are far from the centre along the strand, so the median is roughly half the strand's length.
     * The numbers that produces are wrong in both directions, and both were shipped:
     *
     * <ul>
     *   <li>a long thin piece gets a radius of the length, clamped at {@link #MAX_RADIUS} (0.22
     *       blocks) - a two-block tail with a 22 cm collision sphere, which pushes the whole body
     *       away from it;</li>
     *   <li>a short piece (a few centimetres of strand, or a small accessory strand) gets a few
     *       millimetres, clamped up to {@link #MIN_RADIUS} - a sphere that reaches nothing, so the
     *       piece passes through the body it is supposed to rest on and collision is, in the only
     *       sense a viewer has, not there at all.</li>
     * </ul>
     *
     * <p>The thickness does not have that ambiguity: distance from the line the piece hangs along,
     * taken as a median so a stray vertex (a spike, a corner of the neighbouring face) cannot size
     * the sphere. A garment panel measured this way gets its half-width, which is what a sphere
     * standing in for a sheet of cloth should be.
     *
     * <p>Package-private so the two regimes can be pinned by a test: the numbers above are what a
     * user sees as "hair goes through the shoulder" and "the tail shoves the body", and neither is
     * visible in any log.
     */
    static float radiusFor(List<Vector3f> vertices, Vector3f pivot, Vector3f restDir) {
        if (vertices == null || vertices.size() < MIN_VERTICES
                || pivot == null || restDir == null || !YsmDynamicBoneSolver.isFinite(restDir)) {
            return MIN_RADIUS;
        }
        float length = restDir.length();
        if (!Float.isFinite(length) || length < 1.0E-6F) {
            return MIN_RADIUS;
        }
        float ux = restDir.x / length;
        float uy = restDir.y / length;
        float uz = restDir.z / length;
        float[] distances = new float[vertices.size()];
        int used = 0;
        for (Vector3f vertex : vertices) {
            if (vertex == null) {
                continue;
            }
            float wx = vertex.x - pivot.x;
            float wy = vertex.y - pivot.y;
            float wz = vertex.z - pivot.z;
            float along = wx * ux + wy * uy + wz * uz;
            float ex = wx - along * ux;
            float ey = wy - along * uy;
            float ez = wz - along * uz;
            distances[used++] = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        }
        if (used < MIN_VERTICES) {
            return MIN_RADIUS;
        }
        java.util.Arrays.sort(distances, 0, used);
        float median = distances[used / 2];
        if (!Float.isFinite(median)) {
            return MIN_RADIUS;
        }
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, median * COLLISION_RADIUS_FRACTION));
    }

    /**
     * Relative mass: the geometry's vertex count over {@link #VERTICES_PER_MASS_UNIT},
     * clamped to a sane range.
     *
     * <p>A proxy, and only ever used to decide how easily the air moves the piece, which is
     * the ratio of a cloth panel's area to its mass. Vertex count tracks that ratio well
     * enough for the difference that matters (a broad skirt panel against a thin braid),
     * and the model package carries nothing better.
     */
    private static float mass(float[] entry) {
        if (entry == null || entry[3] <= 0.0F) {
            return 1.0F;
        }
        return Math.max(0.25F, Math.min(4.0F, entry[3] / VERTICES_PER_MASS_UNIT));
    }

    /** Whether {@code bone} hangs anywhere under {@code ancestor}, bounded against cycles. */
    private static boolean isUnder(YSMRuntimeModel.BoneRt[] bones, int bone, int ancestor) {
        int guard = 0;
        for (int i = bones[bone].parent; i >= 0 && guard++ <= bones.length; i = bones[i].parent) {
            if (i == ancestor) {
                return true;
            }
        }
        return false;
    }
}
