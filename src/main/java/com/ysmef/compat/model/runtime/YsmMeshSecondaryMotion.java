package com.ysmef.compat.model.runtime;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.model.YSMMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.Map;

/**
 * Secondary motion for the converted mesh, driven by the pose Epic Fight is about to draw.
 *
 * <h2>Why the animator's own simulation is not enough</h2>
 *
 * <p>The converted mesh and YSM's own model are never on screen at the same time, and which
 * of the two is drawn depends on battle mode: outside battle mode YSM's own renderer draws
 * the player and this mod deliberately steps aside, while inside battle mode Epic Fight
 * draws the converted mesh. YSM's script physics therefore runs where nobody can see it, so
 * this class drives the simulation from the pose that is really being drawn.
 *
 * <h2>The three things that made the previous version look wrong</h2>
 *
 * <ol>
 *   <li><b>The rotation had no moment arm.</b> The part transform a mesh part receives is
 *       applied <i>before</i> the joint's skinning transform (Epic Fight computes
 *       {@code pose x toOrigin x partTransform}, see {@code vanilla_mesh_transformer.comp}),
 *       so it acts in the mesh's model bind space. The previous version wrote a bare
 *       rotation there, which swings the part about the model's origin - the feet - rather
 *       than about the bone's own pivot. Every piece therefore had a lever the length of the
 *       model, which is exactly the "flung away with violent motion and snapped home" in the
 *       in-game report. The fix is {@code T(pivot) x rotation x T(-pivot)}, with the pivot
 *       taken from the bind deformation the mesh was written with
 *       ({@link YsmPhysicsParts}).</li>
 *   <li><b>One rotation for a whole piece.</b> Now every physics bone is its own pendulum
 *       ({@link YsmDynamicBoneSolver}), and a segment's delta is composed under its nearest
 *       simulated ancestor's so a nested piece cannot tear apart.</li>
 *   <li><b>Nothing to collide with.</b> Now {@link YsmBodyColliders} builds one collision
 *       volume per body joint from the model's own bind geometry and places them with the
 *       same transform the skinning uses.</li>
 * </ol>
 *
 * <h2>What feeds the dynamics</h2>
 *
 * <p>A segment's pivot is its bone's bind pivot carried through the joint transform of the
 * pose about to be drawn, and its rest direction is the bind direction carried through the
 * same transform - so the swing is measured against the animation without the swing. The
 * body's own velocity enters as airflow in the model's frame, which is what makes a skirt
 * flare and hair stream while running.
 *
 * <h2>What the author's physics animation is for</h2>
 *
 * <p>A model whose controller plays a physics animation ({@code Hair_Physics}) declares which
 * bones move and, in each expression's {@code ysm.second_order} call, how fast each one
 * settles: that is a part list and a set of spring parameters, and it is what
 * {@link YsmPhysicsParts} takes from it ({@code YsmPhysicsParts.Source.AUTHORED}). It is
 * <b>not</b> a source of transforms. An animation that has been evaluated into angles and
 * written onto the parts is a keyframe animation with the body's motion as its input - no
 * moment arm, no mass, no inertia, nothing to collide with - which is precisely the "hard
 * sheet flung out and snapped home" in the report. So there is one path here for every model,
 * {@link #simulate}, and the author's contribution arrives as numbers: which bones, and how
 * stiff. YSM's own physics is no richer (its filters are a first-order lerp and a
 * second-order spring, with no lever, mass or collision anywhere in it), so there is nothing
 * to lose by integrating instead of copying.
 */
public final class YsmMeshSecondaryMotion {

    /** A segment's composition chain is bounded by this, against a cyclic parent table. */
    private static final int MAX_CHAIN_DEPTH = 64;

    /** Per-model simulation state, keyed by the mesh because the levers come from its geometry. */
    static final class State {
        final YsmPhysicsParts.Model parts;
        final YsmDynamicBoneSolver.SegmentState[] states;
        final YsmBodyColliders colliders;
        /**
         * The transform handed to the mesh, per segment. Held so it is not shared.
         *
         * <p>Filled by {@link #simulate}, from the composed matrix the resolution produced, and read
         * by {@link #apply} to write the mesh's parts. Nothing else writes it, which is what makes
         * "every part's transform comes out of the simulation" a property of the code rather than a
         * claim about it.
         */
        final OpenMatrix4f[] deltas;
        /** JOML twin of {@link #deltas}, where the composition happens. */
        final Matrix4f[] jomlDeltas;
        final boolean[] resolved;
        final int[] resolveDepth;
        /**
         * Which segments the solver integrated on the last frame.
         *
         * <p>The answer to "did this model's parts go through the solver" as a value rather than as
         * an argument about the code: only {@link #resolveSegment} sets an entry, and it sets it
         * exactly where it hands the segment to {@link YsmDynamicBoneSolver}. A frame path that
         * wrote something else onto the parts would leave these false, which is what a test can
         * read.
         */
        final boolean[] integrated;
        /** The drawn frame's pose, created on the first frame. See {@link ArmaturePose}. */
        private ArmaturePose live;
        /** Per-segment scratch: the joint deformation, its pivot and its rest direction. */
        final OpenMatrix4f[] deformations;
        final Vector3f[] pivots;
        final Vector3f[] restDirections;
        /** The swing each segment produced on the last frame, degrees, for the log. */
        final float[] lastDegrees;
        /** How far each segment's centre of mass moved, blocks; the honest size of the swing. */
        final float[] lastDisplacement;
        /** How far each segment's whole chain has swung, radians. */
        final float[] chainAngle;
        /** The same, as it stood at the end of the previous frame: the per-frame turn of a chain,
         *  which is what a segment below it is carried by. */
        final float[] lastChainAngle;
        /** The swing this segment was allowed on the last frame, radians: its own limit, or its
         *  share of what the piece has left, whichever is smaller. Logged beside the swing. */
        final float[] chainBudget;
        /**
         * What this segment and its ancestors have spent of the piece's allowance, radians.
         *
         * <p>The sum of their own granted angles, which is the quantity the allowance is spent out
         * of. The composed chain angle ({@link #chainAngle}) is what the piece reads as on screen and
         * is logged, but it is not the budget: rotations about different axes compose to more than
         * their sum, so a budget measured that way is spent faster than the joints spend it - which
         * is how a four-joint chain "used up" sixty degrees of allowance at 36.8 degrees of bend.
         */
        final float[] chainUsed;
        /**
         * How many joints of this segment's piece are at or below it, itself included.
         *
         * <p>The piece's bend allowance is divided by this, so a long chain shares it out evenly
         * instead of the joints near the root taking it all and the ones below being held still.
         * Computed once, from the parent links, because the links are model data and need not be in
         * bone order.
         */
        final int[] jointsLeft;
        /**
         * Which segments are sewn to this one, and whether this one is pulled toward them.
         *
         * <p>Computed here from the model's own geometry ({@link YsmPhysicsChains#sewnTogether})
         * rather than read from {@link YsmPhysicsParts.Segment#neighbours()}, and that is the
         * correction this field exists for: the wiring in the builder leaves the <b>parent/child</b>
         * pairs out, and on a panel skirt those are precisely the same-piece links. See
         * {@link YsmPhysicsChains#sewnTogether} for why they belong in, and
         * {@link #relaxTowardsNeighbours} for what is done with them.
         */
        final Knits knits;
        /**
         * How far this segment's piece may bend as a whole, radians, per segment.
         *
         * <p>Per piece, not one number for the model: a piece's total is scaled by how many joints
         * it has ({@link YsmPhysicsParts#chainLimitFor}), because one flat per-joint limit shared
         * over a long chain makes the chain <i>stiffer</i> the longer it is - a seven-bone tail at
         * 8.6 degrees a bone is a tail nobody sees move. A table rather than a computation in the
         * frame loop, so the frame allocates nothing and the answer cannot drift frame to frame.
         */
        final float[] pieceLimit;
        /** How many joints each segment's piece has in all; the log reads it beside the limit. */
        final int[] jointsInPiece;
        /**
         * The configuration's per-joint limit the pieces' totals are scaled from, radians.
         *
         * <p>Kept so the log can print what the scaling started from, which is the difference
         * between "this piece is allowed little" and "the config is small".
         */
        final float maxAnglePerJoint;
        /** The axis of each segment's own swing in model space, and its rest direction. Logged
         *  because the angle alone cannot say whether a piece is trailing or being spun. */
        final Vector3f[] lastAxis;
        final Vector3f[] lastRest;
        /** The body's turn, filtered the same way the pivot's motion is. */
        float smoothedYawRate;
        float yawAccel;
        boolean turnInitialized;
        /** How far each segment's centre of mass was pushed by a collision, blocks. */
        final float[] lastContact;
        double lastStepSeconds = -1.0;
        long frames;
        int detailLogged;
        boolean logged;
        boolean colliderLogged;

        State(YsmPhysicsParts.Model parts, YsmBodyColliders colliders, float maxAnglePerJoint) {
            this.parts = parts;
            this.colliders = colliders;
            this.maxAnglePerJoint = maxAnglePerJoint;
            int count = parts.segments().length;
            this.states = new YsmDynamicBoneSolver.SegmentState[count];
            this.deltas = new OpenMatrix4f[count];
            this.jomlDeltas = new Matrix4f[count];
            this.resolved = new boolean[count];
            this.resolveDepth = new int[count];
            this.integrated = new boolean[count];
            this.deformations = new OpenMatrix4f[count];
            this.pivots = new Vector3f[count];
            this.restDirections = new Vector3f[count];
            this.lastDegrees = new float[count];
            this.lastDisplacement = new float[count];
            this.chainAngle = new float[count];
            this.lastChainAngle = new float[count];
            this.chainBudget = new float[count];
            this.chainUsed = new float[count];
            PieceTable pieces = piecesOf(parts.segments());
            this.jointsLeft = pieces.jointsLeft;
            this.jointsInPiece = pieces.jointsInPiece;
            this.knits = knitsOf(parts.segments());
            this.pieceLimit = new float[count];
            for (int i = 0; i < count; i++) {
                this.pieceLimit[i] = YsmPhysicsParts.chainLimitFor(
                        pieces.jointsInPiece[i], maxAnglePerJoint);
            }
            this.lastAxis = new Vector3f[count];
            this.lastRest = new Vector3f[count];
            this.lastContact = new float[count];
            for (int i = 0; i < count; i++) {
                this.states[i] = new YsmDynamicBoneSolver.SegmentState();
                this.deltas[i] = new OpenMatrix4f();
                this.jomlDeltas[i] = new Matrix4f();
                this.deformations[i] = new OpenMatrix4f();
                this.pivots[i] = new Vector3f();
                this.restDirections[i] = new Vector3f();
                this.lastAxis[i] = new Vector3f();
                this.lastRest[i] = new Vector3f();
            }
        }
    }

    /** Per segment: how many joints its piece has, and how many of them are at or below it. */
    private static final class PieceTable {
        final int[] jointsInPiece;
        final int[] jointsLeft;

        PieceTable(int[] jointsInPiece, int[] jointsLeft) {
            this.jointsInPiece = jointsInPiece;
            this.jointsLeft = jointsLeft;
        }
    }

    /**
     * The piece each segment belongs to, as counts rather than as a list.
     *
     * <p>Walked from the parent links rather than assumed from the list order, because the links are
     * model data and a piece's bones need not be listed root first - on the shipped maid the
     * right-hand panel is {@code RB3, RB2, RB} and the left-hand one {@code LM, LM2, LM3}. The walk
     * is bounded, because a damaged table can contain a cycle and this runs on the render thread's
     * path to a model's first frame.
     *
     * <p>The counts are of <b>segments</b>: the bones that are really simulated, which is what the
     * piece's allowance is shared among. A bone in the skeleton's subtree that produced no segment -
     * geometry that is not its own, a mapped body part, a bracket over two panels - must not raise
     * the allowance of the chain it hangs near.
     */
    private static PieceTable piecesOf(YsmPhysicsParts.Segment[] segments) {
        int count = segments.length;
        int[] depth = new int[count];
        int[] root = new int[count];
        int[] size = new int[count];
        for (int i = 0; i < count; i++) {
            int top = i;
            int guard = 0;
            for (int parent = segments[i].parent();
                 parent >= 0 && parent < count && parent != top && guard++ <= count;
                 parent = segments[parent].parent()) {
                top = parent;
                depth[i]++;
            }
            root[i] = top;
        }
        for (int i = 0; i < count; i++) {
            size[root[i]]++;
        }
        int[] inPiece = new int[count];
        int[] left = new int[count];
        for (int i = 0; i < count; i++) {
            inPiece[i] = Math.max(1, size[root[i]]);
            left[i] = Math.max(1, size[root[i]] - depth[i]);
        }
        return new PieceTable(inPiece, left);
    }

    /**
     * Which segments are sewn to which.
     *
     * <p>A flat array rather than a list of lists because it is read once per segment per frame and
     * the frame allocates nothing: {@code partners} is a run {@code [start[i], start[i] + count[i])}
     * for each segment. A pair is stored on <b>both</b> sides - each of the two is pulled toward the
     * other's direction, so they meet in the middle and neither is the only one that gives way.
     */
    static final class Knits {
        final int[] partners;
        final int[] start;
        final int[] count;

        Knits(int[] partners, int[] start, int[] count) {
            this.partners = partners;
            this.start = start;
            this.count = count;
        }
    }

    /**
     * Build the coupling graph from the model's own geometry.
     *
     * <p>The sewn relation is {@link YsmPhysicsChains#sewnTogether}, in one place so the rule can be
     * read and tested without a frame: a parent and its child are always sewn (they are the same
     * piece of cloth), and two segments that are not related are sewn when their pivots are within
     * {@code KNIT_RADIUS} and their rest directions within {@code KNIT_MAX_ANGLE}.
     *
     * <p>The candidate set is the union of the parent/child links and the nearest few by pivot
     * distance, capped at {@code KNIT_COUNT} + 1 partners. A cap rather than a threshold alone,
     * because a skirt's waistband puts a dozen pivots inside the radius and a per-frame loop over
     * all of them is work the model did not ask for; the nearest few are the panels actually beside
     * each other.
     *
     * <p>Bounded by the segment count, because the parent links are model data and a damaged table
     * can contain a cycle; this runs on the render thread's path to a model's first frame.
     */
    private static Knits knitsOf(YsmPhysicsParts.Segment[] segments) {
        int count = segments.length;
        int slotCount = YsmPhysicsChains.KNIT_COUNT;
        int[] depth = new int[count];
        int[] parentOf = new int[count];
        for (int i = 0; i < count; i++) {
            parentOf[i] = parentOf(segments, i);
        }
        for (int i = 0; i < count; i++) {
            int guard = 0;
            for (int parent = parentOf[i]; parent >= 0 && guard++ <= count;
                 parent = parentOf[parent]) {
                depth[i]++;
            }
        }
        // One partner slot per knit, plus the parent and the child, which the cap must never drop:
        // they are the same piece of cloth and the reason this table exists.
        int[] partners = new int[count * (slotCount + 2)];
        int[] start = new int[count];
        int[] size = new int[count];
        int[] best = new int[slotCount];
        float[] bestDistance = new float[slotCount];
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            start[i] = cursor;
            java.util.Arrays.fill(best, -1);
            java.util.Arrays.fill(bestDistance, Float.MAX_VALUE);
            for (int j = 0; j < count; j++) {
                if (i == j) {
                    continue;
                }
                // A parent/child link is recorded on the PARENT's side only, and that is not a
                // detail of the data structure - it is what keeps the chain's composition honest.
                // A child is resolved after its parent and composes its delta under the parent's,
                // so its own swing IS its angle relative to the parent; pulling the parent toward
                // the child is what closes that angle, and the child has nothing to gain from a
                // pull in the other direction. Recording it on the child's side as well makes the
                // parent's relaxation depend on a partner that has not been resolved yet, and the
                // child then composes under a stale parent - which doubles the composed angle per
                // level, the exact "tail folds onto the lower body" this file was fixed for. The
                // measurement that caught it is in tmp_verify/T8_delta_probe.txt.
                boolean child = j == parentOf[i];
                if (child) {
                    partners[cursor++] = j;
                    size[i]++;
                    continue;
                }
                if (i == parentOf[j]) {
                    // This segment is the parent: the link is on the child's side, not here.
                    continue;
                }
                float distance = Float.MAX_VALUE;
                if (segments[i].bindPivot() != null && segments[j].bindPivot() != null) {
                    distance = segments[i].bindPivot().distance(segments[j].bindPivot());
                }
                if (distance > YsmPhysicsChains.KNIT_RADIUS
                        || segments[i].bindRest() == null || segments[j].bindRest() == null
                        || YsmDynamicBoneSolver.angleBetween(segments[i].bindRest(),
                                segments[j].bindRest()) > YsmPhysicsChains.KNIT_MAX_ANGLE) {
                    continue;
                }
                for (int slot = 0; slot < slotCount; slot++) {
                    if (distance < bestDistance[slot]) {
                        for (int shift = slotCount - 1; shift > slot; shift--) {
                            best[shift] = best[shift - 1];
                            bestDistance[shift] = bestDistance[shift - 1];
                        }
                        best[slot] = j;
                        bestDistance[slot] = distance;
                        break;
                    }
                }
            }
            for (int slot = 0; slot < slotCount; slot++) {
                if (best[slot] < 0) {
                    continue;
                }
                partners[cursor++] = best[slot];
                size[i]++;
            }
        }
        // Every segment of a coupled garment is relaxed toward its partners, the top of a piece
        // included: the hips are where a skirt's panels are sewn to each other, so the panel that
        // is a root is the one with the most to gain from being held by the ones beside it.
        return new Knits(java.util.Arrays.copyOf(partners, cursor), start, size);
    }

    /** A segment's parent index when it is a usable one, else -1. */
    private static int parentOf(YsmPhysicsParts.Segment[] segments, int index) {
        if (index < 0 || index >= segments.length) {
            return -1;
        }
        int parent = segments[index].parent();
        return parent >= 0 && parent < segments.length && parent != index ? parent : -1;
    }

    /**
     * Resolve the panels sewn to this one, so the coupling below reads this frame's directions.
     *
     * <p><b>Not called any more, and the reason is worth more than the method.</b> It was added so
     * the relaxation would read this frame's directions rather than last frame's, and it breaks the
     * chain instead: a knit partner is very often this segment's own child, and resolving the child
     * first makes the child compose its delta under a parent that has not been built yet. The
     * composed angle then doubles per level - the reported "tail folds onto the lower body" - while
     * every individual number still looks plausible. The coupling tolerates a partner that is a
     * frame behind; a chain does not tolerate a composition against a stale parent. Kept as a
     * written-down dead end so it is not re-invented.
     */
    @SuppressWarnings("unused")
    private static void resolveKnitPartners(State state, PoseSource poses, int index, float dt,
                                            Vector3f bodyVelocity, float[] turn,
                                            YsmDynamicBoneSolver.Colliders colliders) {
        Knits knits = state.knits;
        if (knits == null || index < 0 || index >= knits.count.length || knits.count[index] == 0) {
            return;
        }
        int start = knits.start[index];
        for (int slot = 0; slot < knits.count[index]; slot++) {
            int partner = knits.partners[start + slot];
            if (partner >= 0 && partner < state.states.length && !state.resolved[partner]) {
                resolveSegment(state, poses, partner, dt, bodyVelocity, turn, colliders);
            }
        }
    }

    /** mesh -> state. Keyed by the mesh because the segment levers come from its geometry. */
    private static final Map<YSMMesh, State> STATES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Models already reported as drawn on a foreign armature, so the warning is not a flood. */
    private static final java.util.Set<String> MISMATCH_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private YsmMeshSecondaryMotion() {}

    /** Forget every model's state (world leave, resource reload). */
    public static void clear() {
        STATES.clear();
        YsmPhysicsParts.clear();
        MISMATCH_LOGGED.clear();
    }

    /**
     * Advance this model's segments against the pose about to be drawn and write the swings
     * into the mesh's parts.
     *
     * <p>Called after {@code YSMRuntimeBridge.apply} has cleared the mesh's runtime
     * transforms, because those transforms are what this writes: an earlier caller's output
     * must not be composed with this one's.
     *
     * @param mesh     the converted mesh being drawn
     * @param model    the model behind it
     * @param entity   the entity it belongs to
     * @param armature the armature the pose belongs to; it must be the model's own re-bound
     *                 armature, because the physics reads each joint's {@code toOrigin} from
     *                 it to place pivots and collision volumes in bind space
     * @param poses    the live pose matrices, in the model's own bind space
     */
    public static void apply(YSMMesh mesh, YSMRuntimeModel model, LivingEntity entity,
                             Armature armature, OpenMatrix4f[] poses) {
        if (mesh == null || model == null || poses == null
                || model.bones == null || model.bones.length == 0) {
            return;
        }
        boolean enabled;
        try {
            enabled = YSMCompatConfig.ENABLE_SECONDARY_MOTION.get();
        } catch (Throwable t) {
            enabled = false;
        }
        if (!enabled) {
            return;
        }
        // The physics places pivots and collision volumes in the mesh's bind space through
        // each joint's toOrigin, which only exists on the model's own re-bound armature. Any
        // other armature would put them somewhere the geometry is not, so the feature stands
        // down for the frame rather than simulating in the wrong frame. Reported once, because
        // "the pieces do not move" and "the pieces are simulated in the wrong frame" look
        // identical on screen and only this tells them apart.
        if (armature == null || armature != YsmBindArmature.getBuiltArmature(model.modelId)) {
            if (MISMATCH_LOGGED.add(model.modelId)) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: [physics] model '{}' is being drawn on an armature that is not its own bind armature; secondary motion is skipped for it",
                        model.modelId);
            }
            return;
        }

        State state = STATES.get(mesh);
        if (state == null) {
            state = create(mesh, model);
            if (state == null) {
                return;
            }
            STATES.put(mesh, state);
        }

        if (!state.logged) {
            state.logged = true;
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [physics] model '{}': {} simulated bone(s) from {}; gravity {} blocks/s^2, air drag {}, collision {}",
                    model.modelId, state.parts.segments().length, sourceName(state.parts.source()),
                    YsmPhysicsTuning.gravityAcceleration(), YsmPhysicsTuning.airDrag(),
                    state.colliders == null ? "off (no body geometry)" : state.colliders.count() + " volume(s)");
            // The chain allowance, said once, because it is the one number behind "the tail does not
            // move": a piece's total is scaled by how many joints it has rather than being one flat
            // figure, and a reader who does not know that cannot tell a long chain shared too
            // thinly from a model whose parts were never classified.
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [physics] chain allowance of '{}': a piece's total is scaled from {} deg per joint by its joint count, at least {} deg per joint and at most {} deg in all",
                    model.modelId,
                    Math.round(Math.toDegrees(state.maxAnglePerJoint) * 10.0F) / 10.0F,
                    Math.round(Math.toDegrees(YsmPhysicsParts.MIN_CHAIN_ANGLE_PER_JOINT) * 10.0F) / 10.0F,
                    Math.round(Math.toDegrees(YsmPhysicsParts.MAX_CHAIN_ANGLE_TOTAL) * 10.0F) / 10.0F);
            if (state.parts.segments().length > 0) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [physics] bones of '{}': {}", model.modelId, state.parts.names());
            }
            if (state.parts.droppedPieces() > 0) {
                // Said out loud because the symptom is invisible in every other number: a piece
                // that was dropped does not swing at all while the pieces around it do, and a
                // garment of which some panels move and some do not is a garment coming apart.
                // The usual cause is the old default cap, 24, still written in configs made before
                // it was raised.
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: [physics] model '{}': {} hanging piece(s) left out of the simulation by secondaryMotionMaxChains={}; raise it if this is a garment and its panels do not all move together",
                        model.modelId, state.parts.droppedPieces(), YsmPhysicsTuning.maxChains());
            }
        }
        if (state.parts.isEmpty()) {
            return;
        }

        double now = System.nanoTime() / 1.0E9D;
        float dt = state.lastStepSeconds < 0.0 ? 0.0F : (float) (now - state.lastStepSeconds);
        state.lastStepSeconds = now;

        // Collision volumes: placed by the same transform the skinning uses, so a volume
        // follows exactly what is drawn. A failure leaves the previous placement and is
        // reported, rather than being silently simulated as "no collisions".
        boolean colliding = false;
        boolean collisionWanted = state.colliders != null && YsmPhysicsTuning.collisionEnabled();
        if (collisionWanted) {
            colliding = state.colliders.update(armature, poses);
            if (!state.colliderLogged) {
                state.colliderLogged = true;
                if (colliding) {
                    YSMEpicFightCompat.LOGGER.info(
                            "YSM-EF Compat: [physics] collision volumes of '{}': {}",
                            model.modelId, state.colliders.describe());
                } else {
                    YSMEpicFightCompat.LOGGER.warn(
                            "YSM-EF Compat: [physics] collision volumes of '{}' could not be placed; the pieces swing without them",
                            model.modelId);
                }
            }
        }

        Vector3f bodyVelocity = bodyVelocity(entity);
        float[] turn = bodyTurn(state, entity, dt);

        // The author's physics animation decides **which** bones move and **how they are tuned**:
        // that is where the declared part list and each bone's spring frequency and damping come
        // from (see YsmPhysicsParts, and the [physics] line create() logs about the author's rig).
        // It does not decide where the pieces are drawn, and there is deliberately no branch here
        // that would let it. An expression's output is a keyframe, and a keyframe whose input is the
        // body's own motion reads on screen exactly as the report described: a piece flung out and
        // snapped home, with no lever, no mass and nothing to collide with. Every segment of every
        // model is therefore integrated by the pendulum solver against the pose about to be drawn,
        // with the author's parameters.
        state.frames++;
        YsmPhysicsParts.Segment[] segments = state.parts.segments();
        YsmDynamicBoneSolver.Colliders colliders =
                colliding ? state.colliders : YsmDynamicBoneSolver.NO_COLLIDERS;
        if (state.live == null) {
            state.live = new ArmaturePose();
        }
        state.live.armature = armature;
        state.live.poses = poses;
        simulate(state, state.live, dt, bodyVelocity, turn, colliders);

        float maxDegrees = 0.0F;
        float maxDisplacement = 0.0F;
        int moving = 0;
        for (int i = 0; i < segments.length; i++) {
            if (state.lastDegrees[i] > maxDegrees) {
                maxDegrees = state.lastDegrees[i];
            }
            if (state.lastDisplacement[i] > maxDisplacement) {
                maxDisplacement = state.lastDisplacement[i];
            }
            if (state.lastDegrees[i] > 0.05F || state.lastContact[i] > 1.0E-4F) {
                moving++;
            }
            // Each segment's delta goes to the geometry its own bone draws, and the same delta the
            // simulation produced: this loop writes, it does not decide.
            for (int ordinal : segments[i].parts()) {
                mesh.setRuntimeTransformAt(ordinal, state.deltas[i]);
            }
        }

        // Throttled, because "the pieces do not move" has several different causes - nothing
        // was classified, the swing is computed as zero, or it is computed and written
        // somewhere that is not drawn - and the log has to say which. The displacement in
        // blocks is reported next to the angle because the angle alone cannot say whether a
        // swing is a flicker or a metre: what a viewer calls "the skirt came apart" is a
        // displacement, and this is that number.
        if (state.frames % 240 == 0) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [physics] frame {}: dt={}ms, max swing={}deg, max displacement={} blocks, {} of {} bone(s) moving, collision {}",
                    state.frames, Math.round(dt * 1000.0F),
                    Math.round(maxDegrees * 10.0F) / 10.0F,
                    Math.round(maxDisplacement * 1000.0F) / 1000.0F, moving, segments.length,
                    colliding ? "active" : "inactive");
            if (state.detailLogged < 3) {
                state.detailLogged++;
                StringBuilder detail = new StringBuilder();
                for (int i = 0; i < segments.length; i++) {
                    detail.append(detail.length() == 0 ? "" : ", ")
                            .append(segments[i].boneName())
                            .append(segments[i].authored() ? "(authored," : "(name,")
                            .append(segments[i].parent() < 0 ? "root," : "seg,")
                            .append(segments[i].parts().length).append(" parts, ")
                            .append(Math.round(segments[i].frequency() * 100.0F) / 100.0F).append("Hz, ")
                            .append(Math.round(state.lastDegrees[i] * 10.0F) / 10.0F).append("deg own/")
                            .append(Math.round(Math.toDegrees(state.chainAngle[i]) * 10.0F) / 10.0F)
                            .append("deg whole/")
                            .append(Math.round(Math.toDegrees(state.chainBudget[i]) * 10.0F) / 10.0F)
                            .append("deg allowed/")
                            // What this segment and its ancestors have spent of the piece's
                            // allowance, how big that allowance is, and how many joints are left to
                            // share the rest. All three are needed: "own=0.0deg" with
                            // "spent=120.0deg of 120.0deg" says the budget ran out above it, while
                            // the same zero beside a small limit says the piece is allowed too
                            // little, and neither can be told from the angle alone.
                            .append(Math.round(Math.toDegrees(state.chainUsed[i]) * 10.0F) / 10.0F)
                            .append("deg of ")
                            .append(Math.round(Math.toDegrees(state.pieceLimit[i]) * 10.0F) / 10.0F)
                            .append("deg (")
                            .append(state.jointsInPiece[i]).append("-joint piece) spent, ")
                            .append(state.jointsLeft[i]).append(" joint(s) left")
                            .append(", moved ")
                            .append(Math.round(state.lastDisplacement[i] * 1000.0F) / 1000.0F)
                            .append(" blocks, L=")
                            .append(Math.round(segments[i].lever() * 1000.0F) / 1000.0F)
                            .append(", m=").append(Math.round(segments[i].mass() * 100.0F) / 100.0F)
                            // The axis, in model space, of the swing this segment actually got.
                            // Cloth falls and trails, so the axis of a hanging piece is horizontal
                            // and across its own rest direction; an axis that is close to vertical
                            // means the piece is being turned around the body instead of away from
                            // it, which is a different defect with the same angle in this log and
                            // no way to tell the two apart without printing it.
                            .append(", axis=(")
                            .append(Math.round(state.lastAxis[i].x * 100.0F) / 100.0F).append(",")
                            .append(Math.round(state.lastAxis[i].y * 100.0F) / 100.0F).append(",")
                            .append(Math.round(state.lastAxis[i].z * 100.0F) / 100.0F)
                            .append("), rest=(")
                            .append(Math.round(state.lastRest[i].x * 100.0F) / 100.0F).append(",")
                            .append(Math.round(state.lastRest[i].y * 100.0F) / 100.0F).append(",")
                            .append(Math.round(state.lastRest[i].z * 100.0F) / 100.0F)
                            .append("), hit=")
                            .append(Math.round(state.lastContact[i] * 1000.0F) / 1000.0F)
                            .append(")");
                }
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [physics] segments of '{}': {}", model.modelId, detail);
            }
        }
    }

    private static State create(YSMMesh mesh, YSMRuntimeModel model) {
        try {
            YsmPhysicsTuning tuning = YsmPhysicsTuning.current();
            YsmPhysicsParts.Model parts = YsmPhysicsParts.build(model, mesh,
                    (float) tuning.frequency(), (float) tuning.dampingRatio(),
                    (float) tuning.maxAngle, (float) tuning.maxAngleRoot);
            if (parts.isEmpty()) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [physics] model '{}': no bone is physics-driven (no controller-bound physics animation, and no bone name reads as hanging cloth or hair)",
                        model.modelId);
            } else {
                reportDeclaredRig(model, parts);
            }
            YsmBodyColliders colliders = parts.isEmpty() ? null : YsmBodyColliders.build(mesh, model);
            // The per-joint limit is what one joint may swing; the piece's own total is scaled from
            // it by how many joints the piece has, so a long chain is not shared into stillness.
            return new State(parts, colliders, (float) tuning.maxAngle);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: [physics] could not classify physics bones for model '{}'; secondary motion stays off for it",
                    model.modelId, t);
            return null;
        }
    }

    /**
     * What the model's own physics animation declared, once per model: which bones, how many of
     * them are simulated. It reads and it logs; it applies nothing.
     *
     * <p>This is the whole of the animation's runtime role now, and the line is here because of what
     * its absence cost. The animation names the bones that move and carries their spring tuning into
     * the segments; nothing evaluates its expressions a frame at a time any more, and the
     * interesting case is a model whose animation names bones that own no geometry of their own -
     * those produce no segment ({@link YsmPhysicsParts#ownsItsGeometry}), so part of the declared
     * rig moves and part of it cannot. Without this line the reader cannot tell "the model declared
     * nothing" from "the model declared bones that carry no mesh", and both look like a garment half
     * animated.
     *
     * <p>Named for what it reads - the declaration - rather than for the animation that carries it,
     * so that a reader (and the acceptance suite's reflection over this class's methods) cannot
     * mistake it for a path that applies the declaration's angles: there is no such path, and this
     * one has no return value, no state and no write to the mesh.
     */
    private static void reportDeclaredRig(YSMRuntimeModel model, YsmPhysicsParts.Model parts) {
        if (model.physicsAnimation == null) {
            return;
        }
        YsmPhysicsLayer layer = YsmPhysicsLayer.of(model.authoredAnimation(model.physicsAnimation));
        int declared = layer.size() > 0
                ? layer.size()
                : (model.physicsParts == null ? 0 : model.physicsParts.size());
        if (declared == 0) {
            return;
        }
        int simulated = 0;
        java.util.Set<String> simulatedNames = new java.util.HashSet<>();
        for (YsmPhysicsParts.Segment segment : parts.segments()) {
            if (segment.authored()) {
                simulated++;
                simulatedNames.add(segment.boneName());
            }
        }
        StringBuilder unwritten = new StringBuilder();
        int unwrittenCount = 0;
        for (String name : layer.boneNames()) {
            if (simulatedNames.contains(name)) {
                continue;
            }
            unwrittenCount++;
            if (unwrittenCount <= 8) {
                unwritten.append(unwritten.length() == 0 ? "" : ", ").append(name);
            }
        }
        YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [physics] model '{}': the author's physics animation '{}' names {} bone(s); {} of them own the geometry they are measured from and are simulated, and the frame's {} segment(s) come from {}",
                model.modelId, model.physicsAnimation, declared, simulated,
                parts.segments().length, sourceName(parts.source()));
        if (unwrittenCount > 0) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [physics] model '{}': {} declared bone(s) carry no mesh of their own, so they are not simulated ({}); the geometry under them swings on the bone that draws it",
                    model.modelId, unwrittenCount, unwritten
                            + (unwrittenCount > 8 ? ", ..." : ""));
        }
    }

    /**
     * The pose a frame is drawn with, as the simulation reads it.
     *
     * <p>An interface rather than the armature and the pose array themselves, and package-private,
     * for one reason: the frame loop is where "every part is integrated, and by the solver" is
     * decided, and a decision that can only be exercised by drawing a player is a decision no test
     * can check. Production passes {@link ArmaturePose}; a test passes a stand-in pose and reads the
     * deltas the loop produced.
     */
    interface PoseSource {
        /** The joint's bind-space {@code toOrigin}, or null when this armature has no such joint. */
        OpenMatrix4f toOriginOf(int joint);

        /** The deformation the pose puts this joint in, or null when it is not in this pose. */
        OpenMatrix4f poseOf(int joint);
    }

    /** The drawn frame's pose: the armature and pose array {@link #apply} was called with. */
    private static final class ArmaturePose implements PoseSource {
        private Armature armature;
        private OpenMatrix4f[] poses;

        @Override
        public OpenMatrix4f toOriginOf(int joint) {
            return YsmMeshSecondaryMotion.toOriginOf(this.armature, joint);
        }

        @Override
        public OpenMatrix4f poseOf(int joint) {
            OpenMatrix4f[] current = this.poses;
            return current != null && joint >= 0 && joint < current.length ? current[joint] : null;
        }
    }

    /**
     * Integrate every segment of one frame against the pose, and fill each one's delta.
     *
     * <p><b>There is one path through here.</b> No caller, argument or field selects between a
     * simulation and a copy of somebody's angles, and the count this returns is what makes that
     * checkable: a segment the solver did not integrate keeps identity and is not counted, so a
     * model whose pieces were written from somewhere else shows up as a smaller number rather than
     * as a picture that merely looks wrong. Package-private so a test can drive it with a stand-in
     * pose and a synthetic model - see {@code YsmSegmentDeltaTest}.
     *
     * @param dt           seconds since the model's last frame
     * @param bodyVelocity the body's velocity in the model's frame, blocks/s, or null
     * @param turn         {@code {yaw rate, yaw acceleration}} of the body, radians/s and radians/s^2
     * @param colliders    the body's volumes, or {@link YsmDynamicBoneSolver#NO_COLLIDERS}
     * @return how many segments the solver integrated
     */
    static int simulate(State state, PoseSource poses, float dt, Vector3f bodyVelocity, float[] turn,
                        YsmDynamicBoneSolver.Colliders colliders) {
        java.util.Arrays.fill(state.resolved, false);
        java.util.Arrays.fill(state.resolveDepth, 0);
        java.util.Arrays.fill(state.integrated, false);
        // The solver's drag term takes the body's velocity as a vector, and a null one would be a
        // NullPointerException on the render thread rather than a still body. "No velocity to give"
        // means no airflow, so it becomes zero here - the one place a null can enter.
        Vector3f velocity = bodyVelocity == null || !YsmDynamicBoneSolver.isFinite(bodyVelocity)
                ? ZERO_VELOCITY : bodyVelocity;
        int integrated = 0;
        YsmPhysicsParts.Segment[] segments = state.parts.segments();
        for (int i = 0; i < segments.length; i++) {
            resolveSegment(state, poses, i, dt, velocity, turn, colliders);
            // Publication: the frame's transform for this segment, converted once, here. Resolution
            // composes the swing (into jomlDeltas); this loop is the only place a delta the mesh
            // receives comes into existence, which is what makes "every part is integrated" a
            // property of one code path rather than of a convention.
            state.deltas[i] = importInto(state.deltas[i], state.jomlDeltas[i]);
            if (state.integrated[i]) {
                integrated++;
            }
        }
        return integrated;
    }

    /** A body standing still, for the frames that have no velocity to give the airflow. */
    private static final Vector3f ZERO_VELOCITY = new Vector3f();

    /**
     * Simulate one segment - and, first, whatever it hangs off - and write its delta.
     *
     * <p>Recursive rather than iterative because the parent links come from model data and
     * need not be in bone order; a segment is simulated at most once per frame, and the depth
     * bound keeps a cyclic table from recursing forever.
     */
    private static void resolveSegment(State state, PoseSource poses, int index, float dt,
                                       Vector3f bodyVelocity, float[] turn,
                                       YsmDynamicBoneSolver.Colliders colliders) {
        if (state.resolved[index]) {
            return;
        }
        state.resolved[index] = true;
        YsmPhysicsParts.Segment[] segments = state.parts.segments();
        YsmPhysicsParts.Segment segment = segments[index];

        int parent = segment.parent();
        boolean hasParent = parent >= 0 && parent < segments.length && parent != index
                && state.resolveDepth[parent] < MAX_CHAIN_DEPTH;
        if (hasParent) {
            state.resolveDepth[index] = state.resolveDepth[parent] + 1;
            resolveSegment(state, poses, parent, dt, bodyVelocity, turn, colliders);
        }
        // The panels sewn to this one are deliberately NOT resolved here. Doing so was tried and it
        // is wrong in a way that looks like arithmetic rather than like a bug: a knit partner can be
        // this segment's own *child* (a panel and the one hanging below it are sewn together), and
        // resolving the child first means the child composes its delta under a parent that has not
        // been built yet - last frame's value, or the identity on the first frame. The composed angle
        // then doubles per level and the child's own pivot leaves the transform, which is the exact
        // defect the composition below was fixed for. A partner that is not resolved yet simply
        // contributes nothing to the mean this frame (see #relaxTowardsNeighbours, which skips
        // segments the solver did not integrate), and the coupling catches up on the next frame.
        // Carried into every exit below, so a segment this frame could not move still reports the
        // budget its own children are measured against: what its ancestors spent, and nothing of its
        // own. Last frame's answer must not be left standing for them.
        state.chainUsed[index] = hasParent ? state.chainUsed[parent] : 0.0F;

        OpenMatrix4f toOrigin = poses.toOriginOf(segment.joint());
        OpenMatrix4f jointPose = poses.poseOf(segment.joint());
        if (toOrigin == null || jointPose == null) {
            // No pose for this joint this frame: the segment keeps the pose's answer, which is no
            // swing at all. Resolution leaves the composed delta at the identity and {@link #simulate}
            // publishes it; nothing here writes a mesh transform.
            state.jomlDeltas[index].identity();
            return;
        }

        // M = pose x toOrigin, the deformation Epic Fight applies to this joint's geometry;
        // it maps the mesh's bind space to the posed model space.
        OpenMatrix4f deformation = OpenMatrix4f.mul(jointPose, toOrigin, state.deformations[index]);
        Vector3f pivot = transformPoint(deformation, segment.bindPivot(), state.pivots[index]);
        Vector3f restDir = transformDirection(deformation, segment.bindRest(), state.restDirections[index]);
        if (restDir.lengthSquared() < 1.0E-8F) {
            state.jomlDeltas[index].identity();
            return;
        }
        restDir.normalize();

        // A child is *already* carried by its parent: the delta written below is composed under the
        // parent's, which is what keeps a chain connected and makes a nested piece follow the one it
        // hangs from. An earlier build also rotated this segment's simulated direction by the
        // parent's swing before solving, on the theory that a chain is rigid in its parent's
        // direction - and that was one carry too many. The parent's rotation then reached the child
        // twice: once rigidly, through the composition, and once more as extra self-rotation
        // measured against the pose. On a three-bone skirt panel the error is small enough to read
        // as liveliness; on the model's seven-bone tail every segment added the whole chain's swing
        // again, and the tail ended up where the body is not. A chain needs exactly what the
        // composition gives it.
        YsmDynamicBoneSolver.INSTANCE.update(state.states[index],
                (float) YsmPhysicsTuning.gravityAcceleration(),
                (float) YsmPhysicsTuning.airDrag(),
                pivot, restDir,
                segment.lever(), segment.frequency(), segment.coefficient(), segment.mass(),
                segment.maxAngle(), bodyVelocity, colliders, segment.radius(), null,
                turn[0], turn[1], dt, scratch);
        // Recorded here, at the one place a segment is handed to the solver, so "this piece was
        // integrated" is a fact about what ran rather than about what the code looks like.
        state.integrated[index] = true;

        float ownAngle = state.states[index].lastAngle;

        // Fabric continuity. Every panel of this skirt is solved as its own pendulum, and the
        // coupling that ties one to the next is the one in YsmPhysicsParts#wireNeighbours - which
        // has no say at all inside a chain, because it excludes the parent/child pairs and a panel
        // skirt is exactly a chain of panels. On the real maid skirt the shipped numbers are a 20.0
        // degree front panel next to a 5.1 degree one, and 12.1 next to 4.6: a fifteen degree
        // disagreement between neighbours of the same garment. Because the panels are separate mesh
        // parts with no geometry between them, that disagreement is not a smooth curve, it is a gap
        // - which is what "the pieces spread apart" is. See #knitsOf.
        //
        // Cloth does not do that: it is continuous, and a panel is held by the ones sewn to it.
        // The relaxation below is that hold, applied as an angular pull toward the average of the
        // segment's knit partners. It is deliberately a pull and not a constraint: a panel still
        // leads when it is genuinely pushed, the others simply follow rather than staying behind.
        relaxTowardsNeighbours(state, index, ownAngle, dt);

        ownAngle = state.states[index].lastAngle;

        // Two ceilings, kept apart on purpose, and a third number that used to be confused with one
        // of them. This joint's own allowance is firm at the base of a piece and loose for the
        // strands below it. The piece as a whole may bend by ITS OWN total - which grows with how
        // many joints it has, so a long chain is not shared into stillness - and what is left of
        // that total is SHARED OUT among the joints that still have to bend inside it. See
        // YsmPhysicsParts#chainLimitFor for the total and #chainAllowance for the division.
        //
        // What is spent so far is the sum of the ancestors' own granted angles, not their composed
        // chain angle. The composed angle is what the piece reads as on screen (and what the log
        // prints as "whole"), and rotations about different axes compose to more than their sum: a
        // budget measured from it is spent faster than the joints spend it, which is how a chain
        // whose joints had each turned 18.4 degrees reported 73.6 degrees of bend and then froze
        // every joint below the third.
        float used = hasParent ? state.chainUsed[parent] : 0.0F;
        float allowed = YsmPhysicsParts.chainAllowance(segment.maxAngle(), state.pieceLimit[index],
                used, state.jointsLeft[index]);
        if (ownAngle > allowed && ownAngle > 1.0E-4F) {
            scratch.set(scratch).slerp(IDENTITY, 1.0F - allowed / ownAngle);
            ownAngle = allowed;
        }
        state.chainBudget[index] = allowed;
        // Written after the clamp, because it is the budget the joints below will be measured
        // against: what this joint was actually allowed, not what it asked for.
        state.chainUsed[index] = used + Math.max(0.0F, ownAngle);

        state.lastDegrees[index] = (float) Math.toDegrees(ownAngle);
        state.lastContact[index] = state.states[index].lastContact;
        storeSwingAxis(state, index, scratch, restDir);
        // The visible size of this segment's swing, in blocks: the distance its centre of mass
        // travels. Angles lie about how far a piece actually moved - forty degrees of a
        // two-centimetre strand is a millimetre, forty degrees of a forearm-long panel is most
        // of a block - so this is the number that says which kind of report you are looking at.
        state.lastDisplacement[index] = 2.0F * segment.lever() * (float) Math.sin(ownAngle * 0.5F);

        // The swing is a model-space rotation; the part transform acts in bind space, so it is
        // conjugated out of the joint's deformation and applied about the bone's own bind
        // pivot. Without the pivot the piece swings about the model origin.
        bindSwingOf(deformation, scratch, bindRotation);
        Matrix4f delta = state.jomlDeltas[index];
        buildSegmentDelta(segment.bindPivot(), bindRotation, delta);
        if (hasParent) {
            // Composed under the parent so a nested piece keeps its shape: the parent's swing
            // carries the child, and the child's own swing is measured from there.
            //
            // The child's own delta has to be copied OUT of this slot before the slot is
            // overwritten, and it has to be a statement of its own. JOML's `set(m)` copies m into
            // `this` and `mul(r)` computes `this x r`; `delta` IS `state.jomlDeltas[index]`, so
            // `delta.set(parent).mul(delta)` makes `this` and `r` the same object and yields the
            // parent squared. Moving the copy into the argument does not help - and this is the trap
            // that made the first attempt at this fix fail while reading as correct:
            //
            //     delta.set(jomlDeltas[parent]).mul(composeScratch.set(jomlDeltas[index]));
            //
            // Java evaluates the receiver chain `delta.set(parent)` BEFORE the argument, so by the
            // time the argument runs, `jomlDeltas[index]` is already the parent and the scratch is
            // handed the parent as well: the product is the parent squared again. Measured on a
            // seven-link chain that form produced 8.643, 17.287, 34.574, 69.148 degrees, where one
            // composition per link gives 8.643, 17.287, 25.930, 34.574.
            //
            // Copying first, in its own statement, is what makes each link compose exactly once, so
            // a chain's composed angle grows by one link's own rotation per level and every link
            // turns about its own pivot.
            composeScratch.set(delta);
            delta.set(state.jomlDeltas[parent]).mul(composeScratch);
        }
        state.chainAngle[index] = rotationAngleOf(delta);
        // Remembered so the segments below can be told how far their parent turned this frame; see
        // the carry at the top of this method. Written after the angle is known, and read only by
        // children, which are resolved after their parent.
        state.lastChainAngle[index] = state.chainAngle[index];
    }

    /**
     * Record the axis of a segment's swing, in model space, for the log.
     *
     * <p>The angle answers "how far", never "which way", and the two ways this can go wrong in the
     * frame conventions look identical in an angle. A rotation taken about the wrong axis in the
     * right frame turns a panel the stated number of degrees about its own hang; taken in the wrong
     * frame it turns the same panel the same number of degrees <i>around the body</i>, which is the
     * difference between a skirt that sways and a skirt whose panels fan out into the space beside
     * the player. The rest direction is printed beside it so the reader can see the two are
     * perpendicular, and both are rounded to centimetres because that is the resolution the
     * question is asked at.
     */
    private static void storeSwingAxis(State state, int index, Quaternionf swing, Vector3f restDir) {
        Vector3f axis = state.lastAxis[index];
        Vector3f rest = state.lastRest[index];
        rest.set(restDir);
        float lengthSquared = swing.x() * swing.x() + swing.y() * swing.y() + swing.z() * swing.z();
        if (!Float.isFinite(lengthSquared) || lengthSquared < 1.0E-12F) {
            axis.zero();
            return;
        }
        float inverseLength = 1.0F / (float) Math.sqrt(lengthSquared);
        axis.set(swing.x() * inverseLength, swing.y() * inverseLength, swing.z() * inverseLength);
    }

    /**
     * The rotation angle of a rigid matrix, radians, from its trace. */
    private static float rotationAngleOf(Matrix4f m) {
        float trace = m.m00() + m.m11() + m.m22();
        float cosine = Math.max(-1.0F, Math.min(1.0F, (trace - 1.0F) * 0.5F));
        float angle = (float) Math.acos(cosine);
        return Float.isFinite(angle) ? angle : 0.0F;
    }

    /**
     * Pull a segment's swing toward the average of the panels sewn to it.
     *
     * <p>Applied after the segment's own dynamics, so it shapes the result rather than replacing
     * it: a panel that is genuinely pushed still leads, and its neighbours follow it instead of
     * staying where they were.
     *
     * <p>The partners are fixed at build time from the model's own geometry and its parent links
     * ({@link #knitsOf}), so this costs one pass over a short list per segment and allocates
     * nothing. A segment further from its piece's root than its parent is - which is every joint
     * of a chain - is the side that moves; the root is pulled toward its own neighbours instead.
     *
     * <h2>Why the factor is per second and not per frame</h2>
     *
     * <p>The pull was a flat quarter of the disagreement per frame, which is a different coupling
     * at 30 fps than at 240: at 240 the garment converges in half the time, so the same model
     * behaves differently on two machines and neither number is the one that was tuned. It is the
     * fraction of the disagreement that closes in one step of length {@code dt}, so
     * {@code 1 - 2^(-dt/H)}: with {@code H} = 0.03 s a tenth of a second closes 90 per cent of the
     * disagreement at any frame rate, and a long frame cannot overshoot because the factor is
     * bounded by one.
     *
     * <h2>Why it does not fight the collision</h2>
     *
     * <p>Nothing here writes state the solver owns: it rotates {@code direction} - the swing - and
     * leaves the velocity in {@link YsmDynamicBoneSolver.SegmentState} alone, so a panel a thigh is
     * holding is pulled by its neighbours <i>and</i> pushed by the collider on the next frame, and
     * the collider is a hard projection while this is a fraction of an angle. Measured on a pair
     * held against a leg, the blocked panel keeps its displacement and its neighbour comes to it;
     * see {@code MaidSkirtCoherenceTest}. The one case the pull cannot win is a neighbour that is
     * itself held on its stop, and there both panels are already at their limits and the gap
     * between them is the budget's business, not the coupling's.
     */
    private static void relaxTowardsNeighbours(State state, int index, float ownAngle, float dt) {
        Knits knits = state.knits;
        if (knits == null || index < 0 || index >= knits.count.length
                || knits.count[index] == 0 || ownAngle < 1.0E-4F) {
            return;
        }
        Vector3f mean = coherenceMean;
        mean.zero();
        int used = 0;
        int start = knits.start[index];
        for (int slot = 0; slot < knits.count[index]; slot++) {
            int partner = knits.partners[start + slot];
            if (partner < 0 || partner >= state.states.length || !state.integrated[partner]) {
                // A partner with no pose this frame has no direction to be pulled toward; its
                // stale one is what a garment must not follow.
                continue;
            }
            mean.add(state.states[partner].direction);
            used++;
        }
        if (used == 0 || mean.lengthSquared() < 1.0E-8F) {
            return;
        }
        mean.normalize();

        Vector3f direction = state.states[index].direction;
        float disagreement = YsmDynamicBoneSolver.angleBetween(direction, mean);
        if (disagreement < COHERENCE_DEADBAND) {
            return;
        }
        // Rotate the piece a fraction of the way toward where the fabric around it points. A
        // rotation rather than a blend of positions: the segment keeps its lever and its state,
        // it simply aims where its neighbours aim.
        relaxDirection(direction, mean, coherenceFactor(dt));
        state.states[index].lastAngle = YsmDynamicBoneSolver.angleBetween(state.restDirections[index], direction);
    }

    /**
     * The fraction of the neighbours' disagreement that closes in one frame of {@code dt} seconds.
     *
     * <p>{@code 1 - 2^(-dt/H)}: the coupling's time constant is {@link #COHERENCE_HALF_LIFE_SECONDS},
     * expressed as a half-life because that is the form whose meaning does not depend on the frame
     * rate. Zero for a frame with no time in it, which leaves the garment exactly as it is rather
     * than pulling it by an amount nobody asked for.
     */
    static float coherenceFactor(float dt) {
        if (!(dt > 0.0F) || !Float.isFinite(dt)) {
            return 0.0F;
        }
        double fraction = 1.0 - Math.pow(2.0, -dt / COHERENCE_HALF_LIFE_SECONDS);
        return (float) Math.max(0.0, Math.min(1.0, fraction));
    }

    /**
     * Turn {@code direction} a fraction of the way toward {@code target}.
     *
     * <p>The coupling's whole mechanism, extracted so its two properties can be tested: it is a
     * <b>pull</b>, so one application closes exactly {@code factor} of the disagreement and never
     * overshoots, and repeated application converges rather than oscillating. A constraint
     * (snapping to the target) would make a panel that is genuinely pushed unable to lead, and
     * cloth does lead where it is pushed - the rest of the garment follows it.
     *
     * <p>Written as an explicit axis-and-angle rotation rather than a quaternion slerp. The
     * fraction is the whole point of the method, and getting it wrong is invisible - the piece
     * simply snaps to its neighbours instead of being pulled toward them, which reads as the
     * garment having no give at all - so it is done in the one form whose meaning is unambiguous
     * from the line itself.
     */
    static void relaxDirection(Vector3f direction, Vector3f target, float factor) {
        if (direction == null || target == null || factor <= 0.0F) {
            return;
        }
        float angle = YsmDynamicBoneSolver.angleBetween(direction, target);
        if (angle < 1.0E-4F) {
            return;
        }
        coherenceAxis.set(direction).cross(target);
        if (coherenceAxis.lengthSquared() < 1.0E-10F) {
            // Exactly opposed: there is no shortest way round, and a pull has no business
            // inventing one. The piece keeps the direction it is in.
            return;
        }
        coherenceAxis.normalize();
        direction.rotateAxis(angle * Math.min(1.0F, factor),
                coherenceAxis.x, coherenceAxis.y, coherenceAxis.z);
        direction.normalize();
    }

    /**
     * The time constant of the panel coupling, as the time in which half the disagreement between
     * two neighbours is closed.
     *
     * <p>Thirty milliseconds: a tenth of a second closes 90 per cent of it, which is well inside
     * the time a viewer reads a garment as one object, while a panel that is genuinely being pushed
     * is still free to lead for several frames. This replaces a flat quarter-per-frame, which was
     * 0.25 at 60 fps and 0.5 at 120 - two different garments on two machines, and neither one the
     * number anybody tuned. See {@link #coherenceFactor}.
     */
    private static final float COHERENCE_HALF_LIFE_SECONDS = 0.03F;

    /** Disagreement below this is left alone, so a settled garment is exactly still. */
    private static final float COHERENCE_DEADBAND = 0.01F;

    private static final Vector3f coherenceMean = new Vector3f();
    private static final Vector3f coherenceAxis = new Vector3f();

    // Scratch, so a per-frame update allocates nothing.
    private static final Quaternionf scratch = new Quaternionf();
    private static final Quaternionf bindRotation = new Quaternionf();
    private static final Quaternionf deformationRotation = new Quaternionf();
    private static final Matrix4f jomlScratch = new Matrix4f();
    /**
     * The chain composition's own scratch, and <b>only</b> the chain composition's.
     *
     * <p>It holds one link's own delta while that link's slot is overwritten with its parent's - the
     * copy has to be taken before the {@code set} that overwrites the slot, because the slot
     * <i>is</i> the destination (see {@code resolveSegment}). Kept separate from
     * {@link #jomlScratch}, which is a general working matrix inside {@link #rotationOf}: sharing one
     * scratch between the composition and the rotation extraction is the kind of coupling that only
     * fails when a call order changes, and this value is read across two statements.
     */
    private static final Matrix4f composeScratch = new Matrix4f();
    private static final Vector3f bodyScratch = new Vector3f();
    /** The identity, for scaling a swing down toward no rotation at all. */
    private static final Quaternionf IDENTITY = new Quaternionf();

    /**
     * The bind-space transform that rotates a part by {@code bindSwing} about {@code bindPivot}.
     *
     * <p>Package-private and static so the identity it has to satisfy can be tested rather than
     * argued about. Epic Fight draws a part as {@code deformation x delta x vertex}, and the
     * caller's {@code deformation} is {@code pose x toOrigin}; the pair must therefore be
     * equivalent to rotating the <i>posed</i> part by the model-space swing about the
     * <i>posed</i> pivot:
     *
     * <pre>
     *   deformation x delta  ==  T(P) x Q x T(-P) x deformation,   P = deformation x bindPivot
     * </pre>
     *
     * <p>Getting this wrong does not look like a rotation error. Every point of the part is
     * displaced instead of turned, by an amount proportional to how far the pivot is from the
     * model origin - so a piece at hip height moves half a block while a piece at head height
     * moves a block, each in its own direction. On a skirt of two dozen separate panels that
     * reads as the garment shattering, which is why it is pinned by a test rather than by a
     * comment.
     */
    static void buildSegmentDelta(Vector3f bindPivot, Quaternionf bindSwing, Matrix4f out) {
        if (bindPivot == null || bindSwing == null || out == null) {
            if (out != null) {
                out.identity();
            }
            return;
        }
        // A swing of exactly zero leaves the part alone, and skipping the two translations
        // keeps the common case allocation-free and exactly identity.
        boolean neutral = Math.abs(bindSwing.w() - 1.0F) < 1.0E-4F
                && Math.abs(bindSwing.x()) < 1.0E-4F
                && Math.abs(bindSwing.y()) < 1.0E-4F
                && Math.abs(bindSwing.z()) < 1.0E-4F;
        if (neutral) {
            out.identity();
            return;
        }
        out.identity()
                .translate(bindPivot.x, bindPivot.y, bindPivot.z)
                .rotate(bindSwing)
                .translate(-bindPivot.x, -bindPivot.y, -bindPivot.z);
    }

    private static OpenMatrix4f toOriginOf(Armature armature, int joint) {
        Joint skeletonJoint = armature.searchJointById(joint);
        return skeletonJoint == null ? null : skeletonJoint.getToOrigin();
    }

    /**
     * The body's velocity in the model's own frame, blocks/s.
     *
     * <p>Epic Fight draws the model with {@code rotationDegrees(180 - yRot)} about Y, so a
     * world vector is carried into model space by the inverse of that rotation. This is the
     * airflow the pieces feel: standing still inside a running body still means moving air,
     * and that is what blows a skirt back.
     */
    private static Vector3f bodyVelocity(LivingEntity entity) {
        if (entity == null) {
            return null;
        }
        Vec3 delta = entity.getDeltaMovement();
        if (delta == null) {
            return null;
        }
        float partialTick;
        try {
            partialTick = Minecraft.getInstance().getFrameTime();
        } catch (Throwable t) {
            partialTick = 0.0F;
        }
        float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        bodyScratch.set((float) delta.x * 20.0F, (float) delta.y * 20.0F, (float) delta.z * 20.0F);
        bodyScratch.rotateY((float) Math.toRadians(yaw - 180.0F));
        return YsmDynamicBoneSolver.isFinite(bodyScratch) ? bodyScratch : null;
    }

    /**
     * How fast the body is turning about its own vertical axis, radians/s, and how fast that turn
     * rate is changing, radians/s^2.
     *
     * <p>Taken from the entity's own yaw difference across a <i>tick</i> - {@code yRot - yRotO} -
     * rather than from frame-to-frame differencing, and that choice is the whole reason this can be
     * fed to the solver at all. The yaw arrives quantised to ticks, so differencing it per frame
     * gives the same staircase that made the pivot's acceleration unusable (see
     * {@code PIVOT_SMOOTHING_SECONDS}): one frame of huge turn, the next of none. Across a tick the
     * difference is exactly the average turn rate over that tick, which is both what the model
     * authors use - their expressions are written against {@code q.yaw_speed} in degrees per second
     * - and a quantity with no quantisation left in it.
     *
     * <p>The acceleration is then the change of that rate, and it is filtered with the same
     * one-pole average the pivot uses, for the same reason: the rate itself only steps once per
     * tick, and the step is not a real change in acceleration. It is clamped in the solver as well.
     */
    private static float[] bodyTurn(State state, LivingEntity entity, float dt) {
        if (entity == null) {
            return NO_TURN;
        }
        float turnDegrees = Mth.wrapDegrees(entity.getYRot() - entity.yRotO);
        float rate = (float) Math.toRadians(turnDegrees) * 20.0F;
        if (!Float.isFinite(rate)) {
            return NO_TURN;
        }
        if (state.turnInitialized && dt > 0.0F) {
            float blend = Math.min(1.0F, dt / PIVOT_SMOOTHING_SECONDS);
            float previous = state.smoothedYawRate;
            state.smoothedYawRate += (rate - previous) * blend;
            float accel = (state.smoothedYawRate - previous) / dt;
            state.yawAccel = Float.isFinite(accel) ? accel : 0.0F;
        } else {
            // First frame for this model: adopt the rate and claim no change, or the model would
            // be handed an acceleration made out of nothing but its own arrival.
            state.smoothedYawRate = rate;
            state.yawAccel = 0.0F;
            state.turnInitialized = true;
        }
        TURN[0] = state.smoothedYawRate;
        TURN[1] = state.yawAccel;
        return TURN;
    }

    /** No turn at all, for an entity-less or paused frame. */
    private static final float[] NO_TURN = new float[2];

    /** The turn scratch for the frame; per model, on the render thread. Reused, not shared. */
    private static final float[] TURN = new float[2];

    /** Matches the solver's pivot smoothing: the window a quantised signal is averaged over. */
    private static final float PIVOT_SMOOTHING_SECONDS = 0.05F;

    /** A point through an OpenMatrix4f, exactly as the skinning does it. */
    static Vector3f transformPoint(OpenMatrix4f m, Vector3f v, Vector3f out) {
        return out.set(
                v.x * m.m00 + v.y * m.m10 + v.z * m.m20 + m.m30,
                v.x * m.m01 + v.y * m.m11 + v.z * m.m21 + m.m31,
                v.x * m.m02 + v.y * m.m12 + v.z * m.m22 + m.m32);
    }

    /** A direction through an OpenMatrix4f (no translation). */
    static Vector3f transformDirection(OpenMatrix4f m, Vector3f v, Vector3f out) {
        return out.set(
                v.x * m.m00 + v.y * m.m10 + v.z * m.m20,
                v.x * m.m01 + v.y * m.m11 + v.z * m.m21,
                v.x * m.m02 + v.y * m.m12 + v.z * m.m22);
    }

    /**
     * The bind-space rotation that, once applied about the part's own bind pivot, makes the
     * <i>posed</i> part turn by {@code modelSwing}.
     *
     * <p>The skinning applies the deformation first ({@code deformation x delta x v}), so a swing
     * expressed in model space has to be carried backwards through it: for a deformation with
     * rotation {@code Mr}, the bind-space form is {@code Mr^-1 x Q x Mr}. Package-private so the
     * chain from a model-space quaternion to the matrix the mesh receives can be tested as a
     * whole, which is the only way this pair of conventions can be kept honest.
     */
    static void bindSwingOf(OpenMatrix4f deformation, Quaternionf modelSwing, Quaternionf out) {
        rotationOf(deformation, deformationRotation);
        YsmPhysicsSimulator.toLocal(out, modelSwing, deformationRotation);
    }

    /**
     * The rotation part of an OpenMatrix4f as a quaternion.
     *
     * <p>JOML names its fields {@code m<column><row>} and stores a translation at
     * {@code m30/m31/m32} - the same place Epic Fight's row-vector convention puts it - so the
     * sixteen-argument {@code set(...)} fills column 0 first and the fields can be copied
     * position for position. That is asserted rather than assumed: see the test that compares
     * the extracted rotation with {@link #transformDirection} on the same matrix, which fails
     * loudly if either side is transposed.
     */
    static void rotationOf(OpenMatrix4f m, Quaternionf out) {
        jomlScratch.set(
                m.m00, m.m01, m.m02, 0.0F,
                m.m10, m.m11, m.m12, 0.0F,
                m.m20, m.m21, m.m22, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        float determinant = jomlScratch.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0E-8F) {
            out.identity();
            return;
        }
        out.setFromUnnormalized(jomlScratch);
        if (!Float.isFinite(out.w()) || out.lengthSquared() < 1.0E-8F) {
            out.identity();
        } else {
            out.normalize();
        }
    }

    private static boolean isNeutral(Quaternionf q) {
        return Math.abs(q.w() - 1.0F) < 1.0E-4F
                && Math.abs(q.x()) < 1.0E-4F && Math.abs(q.y()) < 1.0E-4F && Math.abs(q.z()) < 1.0E-4F;
    }

    /**
     * Copy a JOML matrix into Epic Fight's, without allocating, and return the destination.
     *
     * <p>Both address their fields column-first ({@code mRC} is column c, row r, and the
     * translation lives in m30/m31/m32), so this is a field copy rather than a transpose.
     *
     * <p>The destination is returned so the one call site that publishes a frame's transform can be
     * an assignment - {@code deltas[i] = importInto(deltas[i], composed)} - rather than a write
     * hidden inside somebody else's method. That is the whole of the difference between "the deltas
     * come out of the simulation" being a fact a reader can check and being a convention.
     */
    private static OpenMatrix4f importInto(OpenMatrix4f out, Matrix4f m) {
        out.m00 = m.m00();
        out.m01 = m.m01();
        out.m02 = m.m02();
        out.m03 = m.m03();
        out.m10 = m.m10();
        out.m11 = m.m11();
        out.m12 = m.m12();
        out.m13 = m.m13();
        out.m20 = m.m20();
        out.m21 = m.m21();
        out.m22 = m.m22();
        out.m23 = m.m23();
        out.m30 = m.m30();
        out.m31 = m.m31();
        out.m32 = m.m32();
        out.m33 = m.m33();
        return out;
    }

    private static String sourceName(YsmPhysicsParts.Source source) {
        return switch (source) {
            case AUTHORED -> "the model's own physics animation";
            case BONE_NAMES -> "bone names (this model declares no physics animation)";
            case NONE -> "nothing";
        };
    }
}

