package com.ysmef.compat.model.runtime;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.YsmDiag;
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

import java.util.List;
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
         * read. A segment held rigid by the user's override is never handed to the solver, so its
         * entry stays false - which is also what keeps it out of the knit relaxation.
         */
        final boolean[] integrated;
        /**
         * Which segments the user's {@code physics_overrides} file holds rigid.
         *
         * <p>False for every segment of every model that has no override file: the file is read
         * once, where the segments are built, and the frame path only ever asks these flags. See
         * {@link YsmPhysicsOverrides#markHeld} - with no file the array is left untouched, so the
         * branch that reads it is unreachable and the shipped behaviour stays the shipped
         * behaviour, byte for byte.
         */
        final boolean[] held;
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
        /**
         * The pose's rotation of each segment's own joint - the difference between the joint's
         * authored orientation and its posed one - which is what the solver scales the
         * gravity-follow weight by, so that a piece the pose has not moved is pulled by its spring
         * alone. Per segment rather than per frame because a piece's joint is its own.
         */
        final Quaternionf[] pivotRotations;
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
            this.held = new boolean[count];
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
            this.pivotRotations = new Quaternionf[count];
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
                this.pivotRotations[i] = new Quaternionf();
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
        LEG_REGION_LOGGED.clear();
        HEAD_REGION_LOGGED.clear();
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
        if (YsmDiag.isEnabled() && LEG_REGION_LOGGED.add(model.modelId)) {
            // Reads only: this branch prints what the frame already computed and writes nothing.
            logLegRegion(model, mesh, state, armature, poses);
        }
        if (YsmDiag.isEnabled() && HEAD_REGION_LOGGED.add(model.modelId)) {
            // The same, for the chest and the head: the region the reported head-hair defect is in.
            // Reads only, once per model, bounded - see logHeadRegion.
            logHeadRegion(model, mesh, state, armature, poses);
        }
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
                            // How much of the piece's spring points at the world's vertical rather
                            // than at the pose. Printed because it separates two failures that the
                            // angles above cannot: a piece whose weight is 0 because its name was
                            // not recognised follows its pose and looks "not moving", while a piece
                            // with a weight and a swing of zero has something else wrong with it.
                            // A weight that is present but whose effect is invisible is then a
                            // question about the limit or the collision, not about classification.
                            .append(", follow=")
                            .append(Math.round(segments[i].verticalFollow() * 100.0F) / 100.0F)
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
            State state = new State(parts, colliders, (float) tuning.maxAngle);
            // The user's choice, if they made one: the bones this model must not swing. Read here -
            // once, where the pieces are built - and it applies nothing at all unless
            // config/ysm_epicfight_compat/physics_overrides/<model>.json exists, so the report line
            // it writes says which bones were held and the frame path below needs no file of its own.
            YsmPhysicsOverrides.markHeld(model.modelId, model.bones, parts.segments(), state.held);
            return state;
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

    // ------------------------------------------------------------------
    // The leg-region diagnostic
    // ------------------------------------------------------------------

    /**
     * Models whose leg-region diagnostic has already been printed, so a model reload does not repeat
     * it. Cleared with the rest of the per-mesh state by {@link #clear()}.
     */
    private static final java.util.Set<String> LEG_REGION_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The most leg pieces one model prints; anything beyond it is counted, not listed. */
    private static final int LEG_DIAG_MAX_PIECES = 16;

    /**
     * Models whose head-region diagnostic has already been printed. Its own set rather than the leg
     * one's, because the two are asked about different models in different sessions and one being
     * printed must not suppress the other. Cleared with the rest of the per-mesh state by
     * {@link #clear()}.
     */
    private static final java.util.Set<String> HEAD_REGION_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The most head pieces one model prints; anything beyond it is counted, not listed. */
    private static final int HEAD_DIAG_MAX_PIECES = 24;

    /**
     * Epic Fight's chest and head joints, by {@code JointTable}: Chest = 8, Head = 9. The head region
     * is where the reported "the top-of-head hair moves the wrong way when the head pitches" lives,
     * and it is a region rather than a joint because the pieces that move are drawn on the head while
     * the ones below them hang off the chest.
     */
    private static final int JOINT_CHEST = 8;
    private static final int JOINT_HEAD = 9;
    private static final int[] HEAD_JOINTS = {JOINT_CHEST, JOINT_HEAD};

    private static boolean isHeadJoint(int joint) {
        return joint == JOINT_CHEST || joint == JOINT_HEAD;
    }

    /** Epic Fight's leg joints, by {@code JointTable}: Thigh_R, Leg_R, Knee_R, Thigh_L, Leg_L, Knee_L. */
    private static final int LEG_JOINT_FIRST = 1;
    private static final int LEG_JOINT_LAST = 6;

    private static boolean isLegJoint(int joint) {
        return joint >= LEG_JOINT_FIRST && joint <= LEG_JOINT_LAST;
    }

    /** Both thighs and both shins: the joints whose own rotation is printed beside the pieces. */
    private static final int[] LEG_JOINTS = {1, 2, 4, 5};

    /**
     * The leg region of one model, once per model, behind {@code -Dysm_ef_compat.diag=true}.
     *
     * <h2>What this settles</h2>
     *
     * <p>The reported defect is a thigh drawn nearly horizontal, and two different causes produce
     * that picture: the piece's <b>own delta</b> has laid it out beside the limb (a piece pivoting
     * about a point it does not hang from - what {@link YsmPhysicsParts#risesFromPivot} now refuses),
     * or the <b>pose</b> already had it at that angle and the delta merely added its allowance on top.
     * The log's own {@code rest} column cannot separate them, which is why the previous round's
     * "the pose supplies ~48 degrees" was recovered from the pose rather than measured independently.
     * This line separates them by printing the drawn long axis of each piece three times - in bind,
     * after the joint's pose alone, and after the pose and the piece's own delta - so the reader can
     * subtract: {@code bind -> pose} is what Epic Fight's animation did to that piece, and
     * {@code pose -> pose+delta} is what this mod's simulation did to it.
     *
     * <h2>What it is, and is not</h2>
     *
     * <p><b>Reads only.</b> It is called after the frame's transforms have been written to the mesh
     * and writes nothing: every value comes from what the frame already computed, so it cannot change
     * what is drawn. It prints at most {@value #LEG_DIAG_MAX_PIECES} pieces plus the four leg joints,
     * once per model, and only when the diagnostic flag is on.
     *
     * <p>The pieces listed are the model's bones that carry geometry on a leg joint, <b>including the
     * ones that are not simulated</b> - a piece the new rule rejected has no segment at all, and a
     * diagnostic that could only see simulated pieces would report "the thigh is simulated" on a build
     * where it no longer is. Each row says which it is and, when it is not simulated, which gate
     * dropped it. {@link #logHeadRegion} lists the same rows for the chest and the head.
     *
     * <p>EF's own biped values are printed for the same joint ids from {@code Armatures.BIPED} - the
     * authored rest pose of Epic Fight's biped skeleton, which is what this mod's re-bound armature is
     * built from. EF's biped <i>pose under the same clip</i> is not separately reachable here: EF draws
     * this model on the armature built for it, so the {@code pose} column below IS Epic Fight's own
     * pose for that clip and that joint id, and the biped columns are its rest values.
     */
    private static void logLegRegion(YSMRuntimeModel model, YSMMesh mesh, State state,
                                     Armature armature, OpenMatrix4f[] poses) {
        try {
            if (model == null || state == null || state.parts == null || poses == null) {
                return;
            }
            YsmPhysicsParts.Segment[] segments = state.parts.segments();
            Map<String, Integer> segmentOfBone = new java.util.HashMap<>();
            for (int i = 0; i < segments.length; i++) {
                segmentOfBone.put(segments[i].boneName(), i);
            }
            Map<Integer, List<Vector3f>> vertices = YsmPhysicsParts.verticesByBone(mesh, model);
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [physics] leg diag of '{}': {} simulated piece(s) in all; the leg joints below are EF's own joint ids (Thigh_R=1, Leg_R=2, Knee_R=3, Thigh_L=4, Leg_L=5, Knee_L=6). "
                            + "Columns: pivot; own geometry y range; lever L; rest angle from straight down (bind); upShare = the up-component of the unit rest direction, which is what the new rule reads (margin {}); the drawn long axis from vertical in bind, after the joint's pose only, and after the pose and the piece's own delta; and the joint's pose rotation for that piece (pose = pose matrix, delta = pose x toOrigin, i.e. what the solver uses as the pose's turn of this joint)",
                    model.modelId, segments.length, YsmPhysicsParts.risesFromPivotMargin());
            int printed = 0;
            int beyond = 0;
            for (int boneIndex = 0; boneIndex < model.bones.length; boneIndex++) {
                YSMRuntimeModel.BoneRt bone = model.bones[boneIndex];
                if (bone == null || !isLegJoint(bone.joint)) {
                    continue;
                }
                List<Vector3f> own = vertices.get(boneIndex);
                if (own == null || own.isEmpty()) {
                    continue;
                }
                if (printed >= LEG_DIAG_MAX_PIECES) {
                    beyond++;
                    continue;
                }
                printed++;
                YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: [physics] leg diag '{}': {}",
                        model.modelId, legPieceRow(model, state, bone, boneIndex, own,
                                segmentOfBone, armature, poses));
            }
            if (beyond > 0) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [physics] leg diag '{}': {} further leg piece(s) not listed",
                        model.modelId, beyond);
            }
            for (int joint : LEG_JOINTS) {
                YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: [physics] leg diag '{}': {}",
                        model.modelId, legJointRow(armature, poses, joint));
            }
        } catch (Throwable t) {
            // A diagnostic that can break a frame is worse than no diagnostic: the frame is already
            // drawn by the time this runs, and the line is only ever read by a person.
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: [physics] leg diag of '{}' could not be produced", model.modelId, t);
        }
    }

    /** One leg piece: what it is, whether it is simulated, and which way it is drawn. */
    private static String legPieceRow(YSMRuntimeModel model, State state, YSMRuntimeModel.BoneRt bone,
                                      int boneIndex, List<Vector3f> own,
                                      Map<String, Integer> segmentOfBone, Armature armature,
                                      OpenMatrix4f[] poses) {
        Vector3f pivot = YsmPhysicsParts.bindPivot(model, bone);
        Vector3f centroid = centroidOf(own);
        Vector3f rest = centroid == null || pivot == null ? null : new Vector3f(centroid).sub(pivot);
        float lever = rest == null || !YsmDynamicBoneSolver.isFinite(rest) ? Float.NaN : rest.length();
        Integer segmentIndex = segmentOfBone.get(bone.name);
        double gap = YsmPhysicsParts.pivotGapFromGeometry(own, pivot);
        boolean pointsUp = YsmPhysicsParts.risesFromPivot(rest, lever);
        boolean hingedOnItself = YsmPhysicsParts.pivotOnGeometry(own, pivot);
        String verdict;
        if (segmentIndex != null) {
            verdict = "simulated";
        } else if (YsmPhysicsParts.poseBelongsToEpicFight(bone)) {
            verdict = "rigid (a body bone: Epic Fight poses it)";
        } else if (pivot == null) {
            verdict = "rigid (no usable pivot)";
        } else if (!(lever >= YsmPhysicsParts.minimumLever())) {
            verdict = "rigid (geometry sits on its pivot: lever " + blocks(lever) + ")";
        } else if (YsmPhysicsParts.wrapsPivot(own, pivot)) {
            verdict = "rigid (geometry wraps its pivot: spread "
                    + blocks(YsmPhysicsParts.directionSpread(own, pivot)) + ")";
        } else if (YsmPhysicsParts.risesOffPivot(own, pivot, rest, lever)) {
            verdict = "rigid (NEW RULE: geometry above a pivot that is not on it: upShare "
                    + share(lever <= 0.0F ? Float.NaN : rest.y / lever) + ", pivot " + blocks(gap)
                    + " blocks outside the geometry)";
        } else if (pointsUp && hingedOnItself) {
            verdict = "rigid (not selected; note: its geometry does point up - upShare "
                    + share(lever <= 0.0F ? Float.NaN : rest.y / lever) + " - but its pivot is on the piece, "
                    + "so the new rule keeps it)";
        } else {
            verdict = "rigid (not selected)";
        }

        OpenMatrix4f deformation = deformationOf(armature, poses, bone.joint);
        Vector3f axis = YsmPhysicsParts.longAxisOf(own);
        double bindAngle = YsmPhysicsParts.angleFromVertical(axis);
        double poseAngle = Double.NaN;
        double drawnAngle = Double.NaN;
        if (axis != null && deformation != null) {
            Vector3f posed = transformDirection(deformation, axis, new Vector3f());
            poseAngle = YsmPhysicsParts.angleFromVertical(posed);
            drawnAngle = poseAngle;
            if (segmentIndex != null && segmentIndex >= 0 && segmentIndex < state.jomlDeltas.length) {
                Matrix4f delta = state.jomlDeltas[segmentIndex];
                Vector3f swung = new Vector3f(posed);
                if (delta != null) {
                    delta.transformDirection(swung);
                    drawnAngle = YsmPhysicsParts.angleFromVertical(swung);
                }
            }
        }

        StringBuilder row = new StringBuilder();
        row.append("bone '").append(bone.name).append("' joint ").append(bone.joint)
                .append(" [").append(verdict).append("]");
        row.append(" pivot ").append(point(pivot));
        row.append(" own geom y ").append(blocks(YsmPhysicsParts.minY(own))).append("..")
                .append(blocks(YsmPhysicsParts.maxY(own)));
        row.append(" centroid ").append(point(centroid));
        row.append(" L ").append(blocks(lever));
        row.append(" rest ").append(degrees(angleFromDown(rest))).append(" deg from down");
        row.append(" upShare ").append(share(rest == null || !(lever > 0.0F) ? Float.NaN : rest.y / lever));
        row.append(" pivotGap ").append(blocks(gap)).append(" blocks (pivot ")
                .append(hingedOnItself ? "on the piece" : "off the piece").append(")");
        row.append(" longAxis bind ").append(degrees(bindAngle))
                .append(" deg / after pose ").append(degrees(poseAngle))
                .append(" deg / after pose+delta ").append(degrees(drawnAngle)).append(" deg from vertical");
        row.append(" | joint pose ").append(degrees(degreesOf(poses, bone.joint)))
                .append(" deg, pose x toOrigin ").append(degrees(degreesOf(deformation))).append(" deg");
        return row.toString();
    }

    /**
     * The chest-and-head region of one model, once per model, behind {@code -Dysm_ef_compat.diag=true}.
     *
     * <h2>What this settles</h2>
     *
     * <p>The reported defect is the <b>top-of-head hair</b> under a head pitch: it moves the wrong way
     * - down when the head looks up - and the whole block moves rather than the tip. Three different
     * causes produce a picture like that and the leg line's columns separate them exactly as they do
     * for a thigh: the piece's <b>own delta</b> has laid it out (a piece swinging about a pivot that
     * is not where it is attached - {@link YsmPhysicsParts#risesOffPivot} refuses the up-pointing
     * case, the wrap rule the band-shaped one), the <b>joint's pose</b> already put it there, or the
     * piece is not simulated at all and simply rides the head. Each row prints the drawn long axis
     * from vertical three times - in bind, after the pose alone, and after the pose and the piece's
     * own delta - so {@code bind -> pose} is what Epic Fight's animation did and
     * {@code pose -> pose+delta} is what this mod's simulation did. The joint rows below print the
     * Chest's and the Head's own pose rotations, which is the pitch the region is being asked about.
     *
     * <h2>What it is, and is not</h2>
     *
     * <p><b>Reads only.</b> It runs after the frame's transforms have been written to the mesh and
     * writes nothing: every value comes from what the frame already computed, so it cannot change what
     * is drawn, and it is the same class of line as {@link #logLegRegion} - the one this round added
     * beside it rather than replacing.
     *
     * <p>It prints at most {@value #HEAD_DIAG_MAX_PIECES} pieces plus the Chest and the Head, once per
     * model, and only when the diagnostic flag is on. The pieces are listed <b>simulated first</b>:
     * the reported model has more than a hundred bones carrying geometry on the head joint, most of
     * them a mouth or an eye a few millimetres across, and a bound that printed them in bone order
     * would spend every row on the face and never reach the hair. Anything past the bound is counted.
     */
    private static void logHeadRegion(YSMRuntimeModel model, YSMMesh mesh, State state,
                                      Armature armature, OpenMatrix4f[] poses) {
        try {
            if (model == null || state == null || state.parts == null || poses == null) {
                return;
            }
            YsmPhysicsParts.Segment[] segments = state.parts.segments();
            Map<String, Integer> segmentOfBone = new java.util.HashMap<>();
            for (int i = 0; i < segments.length; i++) {
                segmentOfBone.put(segments[i].boneName(), i);
            }
            Map<Integer, List<Vector3f>> vertices = YsmPhysicsParts.verticesByBone(mesh, model);
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [physics] head diag of '{}': {} simulated piece(s) in all; the region below is the Chest ({}) and the Head ({}), EF's own joint ids. "
                            + "Columns: pivot; own geometry y range; lever L; rest angle from straight down (bind); upShare = the up-component of the unit rest direction (margin {}); the drawn long axis from vertical in bind, after the joint's pose only, and after the pose and the piece's own delta; and the joint's pose rotation for that piece. Pieces are listed simulated first",
                    model.modelId, segments.length, JOINT_CHEST, JOINT_HEAD,
                    YsmPhysicsParts.risesFromPivotMargin());
            List<Integer> simulated = new java.util.ArrayList<>();
            List<Integer> rigid = new java.util.ArrayList<>();
            for (int boneIndex = 0; boneIndex < model.bones.length; boneIndex++) {
                YSMRuntimeModel.BoneRt bone = model.bones[boneIndex];
                if (bone == null || !isHeadJoint(bone.joint)) {
                    continue;
                }
                List<Vector3f> own = vertices.get(boneIndex);
                if (own == null || own.isEmpty()) {
                    continue;
                }
                // "Would be listed" is asked of everything with geometry, including the pieces a
                // gate dropped: a diagnostic that could only see simulated pieces would report "the
                // hair is simulated" on a build where it no longer is.
                if (segmentOfBone.containsKey(bone.name)) {
                    simulated.add(boneIndex);
                } else {
                    rigid.add(boneIndex);
                }
            }
            int printed = 0;
            int beyond = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int boneIndex : pass == 0 ? simulated : rigid) {
                    if (printed >= HEAD_DIAG_MAX_PIECES) {
                        beyond++;
                        continue;
                    }
                    printed++;
                    YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: [physics] head diag '{}': {}",
                            model.modelId, legPieceRow(model, state, model.bones[boneIndex], boneIndex,
                                    vertices.get(boneIndex), segmentOfBone, armature, poses));
                }
            }
            if (beyond > 0) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [physics] head diag '{}': {} further chest/head piece(s) not listed ({} simulated, {} rigid)",
                        model.modelId, beyond, simulated.size(), rigid.size());
            }
            for (int joint : HEAD_JOINTS) {
                YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: [physics] head diag '{}': {}",
                        model.modelId, legJointRow(armature, poses, joint));
            }
        } catch (Throwable t) {
            // A diagnostic that can break a frame is worse than no diagnostic: the frame is already
            // drawn by the time this runs, and the line is only ever read by a person.
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: [physics] head diag of '{}' could not be produced", model.modelId, t);
        }
    }

    /** One leg joint: its rest rotation, its live pose, and Epic Fight's own biped rest for it. */
    private static String legJointRow(Armature armature, OpenMatrix4f[] poses, int joint) {
        Joint skeletonJoint = armature == null ? null : armature.searchJointById(joint);
        OpenMatrix4f toOrigin = skeletonJoint == null ? null : skeletonJoint.getToOrigin();
        OpenMatrix4f local = skeletonJoint == null ? null : skeletonJoint.getLocalTransform();
        OpenMatrix4f deformation = deformationOf(armature, poses, joint);
        Joint biped = null;
        try {
            biped = yesman.epicfight.gameasset.Armatures.BIPED.get().searchJointById(joint);
        } catch (Throwable ignored) {
            // The biped armature is a registry entry; a diagnostic is not worth a frame.
        }
        return "joint " + joint + " '" + (skeletonJoint == null ? "?" : skeletonJoint.getName())
                + "': rest toOrigin " + degrees(degreesOf(toOrigin))
                + " deg, rest local " + degrees(degreesOf(local))
                + " deg, live pose " + degrees(degreesOf(poses, joint))
                + " deg, pose x toOrigin " + degrees(degreesOf(deformation))
                + " deg | EF biped rest toOrigin " + degrees(degreesOf(biped == null ? null : biped.getToOrigin()))
                + " deg, EF biped rest local " + degrees(degreesOf(biped == null ? null : biped.getLocalTransform()))
                + " deg";
    }

    /** {@code pose x toOrigin} for one joint, allocated: this runs once per model, under the flag. */
    private static OpenMatrix4f deformationOf(Armature armature, OpenMatrix4f[] poses, int joint) {
        if (armature == null || poses == null || joint < 0 || joint >= poses.length || poses[joint] == null) {
            return null;
        }
        OpenMatrix4f toOrigin = toOriginOf(armature, joint);
        return toOrigin == null ? null : OpenMatrix4f.mul(poses[joint], toOrigin, new OpenMatrix4f());
    }

    /** The rotation angle of a pose matrix, in degrees, or NaN. */
    private static double degreesOf(OpenMatrix4f[] poses, int joint) {
        if (poses == null || joint < 0 || joint >= poses.length) {
            return Double.NaN;
        }
        return degreesOf(poses[joint]);
    }

    /** The rotation angle a matrix applies, in degrees, or NaN when there is no rotation to read. */
    static double degreesOf(OpenMatrix4f matrix) {
        if (matrix == null) {
            return Double.NaN;
        }
        rotationOf(matrix, diagQuaternion);
        return degreesOf(diagQuaternion);
    }

    /** Scratch for the diagnostic only: it runs once per model, on the render thread. */
    private static final Quaternionf diagQuaternion = new Quaternionf();

    /**
     * The angle of a rotation, degrees 0..180 - the shortest turn that takes the identity to it.
     *
     * <p>{@code 2 acos|w|}, with the absolute value because a quaternion and its negation are the same
     * rotation, and clamped because a float sum can put {@code |w|} a hair above one. A quaternion
     * that is not finite, or has no length, answers NaN rather than 0: "cannot read" must not be
     * printed as "no rotation".
     */
    static double degreesOf(Quaternionf rotation) {
        if (rotation == null || !Float.isFinite(rotation.w())
                || !Float.isFinite(rotation.x()) || !Float.isFinite(rotation.y())
                || !Float.isFinite(rotation.z()) || rotation.lengthSquared() < 1.0E-8F) {
            return Double.NaN;
        }
        double w = Math.min(1.0D, Math.abs(rotation.w()));
        return Math.toDegrees(2.0D * Math.acos(w));
    }

    /** The angle between a direction and straight down, degrees 0..180, or NaN. */
    static double angleFromDown(Vector3f direction) {
        if (direction == null || !YsmDynamicBoneSolver.isFinite(direction)) {
            return Double.NaN;
        }
        double length = Math.sqrt(direction.lengthSquared());
        if (length < 1.0E-9D) {
            return Double.NaN;
        }
        return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, -direction.y / length))));
    }

    private static Vector3f centroidOf(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        Vector3f acc = new Vector3f();
        int used = 0;
        for (Vector3f vertex : vertices) {
            if (vertex == null || !YsmDynamicBoneSolver.isFinite(vertex)) {
                continue;
            }
            acc.add(vertex);
            used++;
        }
        return used == 0 ? null : acc.div(used);
    }

    private static String point(Vector3f v) {
        return v == null || !YsmDynamicBoneSolver.isFinite(v) ? "n/a"
                : String.format(java.util.Locale.ROOT, "(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }

    private static String blocks(float value) {
        return Float.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.3f", value) : "n/a";
    }

    private static String blocks(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.3f", value) : "n/a";
    }

    private static String share(float value) {
        return Float.isFinite(value) ? String.format(java.util.Locale.ROOT, "%+.3f", value) : "n/a";
    }

    private static String degrees(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.1f", value) : "n/a";
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
     * The world's downward direction in the model's own space, blocks-free and never mutated.
     *
     * <p>{@code (0,-1,0)}, and that is a measured fact rather than a convention, because the whole
     * gravity-follow mechanism is worthless if it is wrong: a target that is not really the world's
     * vertical would pull every piece toward the <i>body's</i> own axis, which is the defect this
     * feature exists to remove, and nothing on screen would say so.
     *
     * <p>The two halves of the render transform settle it:
     *
     * <ul>
     *   <li>Epic Fight applies the model matrix OUTSIDE the pose: {@code PatchedEntityRenderer
     *       #mulPoseStack} does {@code poseStack.mulPose(Y.rotationDegrees(180))} then
     *       {@code MathUtils.mulStack(poseStack, getModelMatrix(partialTick))}, and only then is
     *       {@code SkinnedMesh.draw(poseStack, ..., armature, armature.getPoseMatrices())} called.
     *       The pose matrices this class reads are therefore in the model's own space, with no
     *       entity rotation in them.</li>
     *   <li>That model matrix is a yaw and a uniform scale, and nothing else.
     *       {@code MathUtils.getModelMatrixIntegral} builds {@code translate * rotateDeg(-yaw, Y) *
     *       rotateDeg(-pitch, X) * scale}, and every override that feeds it -
     *       {@code LivingEntityPatch}, {@code PlayerPatch} and {@code CustomHumanoidMobPatch} (via
     *       {@code HumanoidMobPatch}) - passes eight hard-coded zeroes for the pitch/roll pair
     *       (javap: offsets 126-135 and 77-84 respectively are {@code fconst_0} eight times). A yaw
     *       about Y leaves the Y axis alone, and a uniform scale leaves a direction alone, so the
     *       model's vertical IS the world's vertical.</li>
     * </ul>
     *
     * <p>The third part of the transform, the body's forward lean, is in the ANIMATION: it is
     * carried by {@code poses[Root]}, so it reaches the solver as the {@code restDir} of each piece
     * rather than as any part of the frame. That is the arrangement this feature needs - the lean
     * tilts the pose the piece is drawn in, while the direction gravity pulls toward stays the
     * world's - and it is why the target must NOT be derived from the root joint's own rotation.
     * Doing that would give back a "vertical" that leans with the body, which is the defect.
     *
     * <p><b>One known exception.</b> {@code LivingEntityRenderer#isEntityUpsideDown} (vanilla's
     * "Dinnerbone" name tag) makes Epic Fight translate and rotate the model by a further 180
     * degrees about Z, which flips the model's Y against the world's and would make the true
     * downward direction {@code (0,+1,0)}. This constant does not follow that case: a hanging piece
     * on an upside-down entity follows the body's axis instead of gravity, which is visible only on
     * a name-tagged mob and was not reproduced or measured.
     */
    private static final Vector3f DOWN_IN_MODEL_SPACE = new Vector3f(0.0F, -1.0F, 0.0F);

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

        // Held rigid by the user's physics_overrides file: the piece follows the pose exactly, so its
        // delta is the identity - the same thing this method writes for a joint with no pose this
        // frame, and what the mesh already holds for a part nothing moved. Three consequences, all of
        // them intended and all of them stated here because each is visible somewhere else:
        //
        //  - the piece is not handed to the solver, so `integrated` stays false and the knit
        //    relaxation neither pulls it nor is pulled by it. A held panel simply does not take part;
        //    it is not a panel held at zero degrees, which would still drag its neighbours toward it.
        //  - `chainUsed` is already the ancestors' usage and nothing of this piece's own, so the
        //    joints below are not charged for a swing that no longer exists. That is what makes a
        //    strand hanging under a held cap keep the budget it had while the cap itself moves none.
        //  - the children resolved after this one compose under the identity (see the composition at
        //    the end of this method), so they swing about their own anchors from wherever the pose
        //    put the piece they hang from - a held piece is a piece that is simply not simulated, it
        //    is never a hole in the chain.
        if (state.held[index]) {
            state.jomlDeltas[index].identity();
            state.lastDegrees[index] = 0.0F;
            state.lastContact[index] = 0.0F;
            state.lastDisplacement[index] = 0.0F;
            state.chainAngle[index] = 0.0F;
            state.lastChainAngle[index] = 0.0F;
            state.chainBudget[index] = 0.0F;
            return;
        }

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
        // The rotation the pose applied to the joint this piece hangs from: the difference between
        // the joint's authored orientation and its posed one, which is what the solver scales the
        // gravity-follow weight by. It is the one quantity here that is an identity frame for a pose
        // that has not moved the piece, so a garment standing still is pulled by its spring and
        // nothing else - see YsmDynamicBoneSolver#update's target. Read from the same deformation the
        // pivot and the rest direction come from, so the three cannot disagree about the frame.
        pivotDeltaOf(deformation, state.pivotRotations[index]);
        YsmDynamicBoneSolver.INSTANCE.update(state.states[index],
                (float) YsmPhysicsTuning.gravityAcceleration(),
                (float) YsmPhysicsTuning.airDrag(),
                segment.verticalFollow(), DOWN_IN_MODEL_SPACE,
                pivot, restDir,
                segment.lever(), segment.frequency(), segment.coefficient(), segment.mass(),
                segment.maxAngle(), bodyVelocity, colliders, segment.radius(), null,
                turn[0], turn[1], dt, scratch, state.pivotRotations[index]);
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
        // conjugated out of the joint's deformation and applied about the point the piece is HELD by -
        // its own contact patch with what it rests on, not its bind pivot. The two are the same point
        // for a strand whose pivot sits at its root; for a piece whose pivot is inside its own volume
        // (the reported top-of-head cap, pivot 0.107 blocks from its nearest vertex) rotating about the
        // pivot sweeps the end the piece is attached by, and that sweep is the whole-piece slide the
        // user reports as the hair lifting off the skull and sinking into it. The anchor is measured
        // once per model in YsmPhysicsParts#contactAnchor; nothing about the solved angle changes, since
        // the solver never sees it. Without a pivot the piece swings about the model origin.
        bindSwingOf(deformation, scratch, bindRotation);
        Matrix4f delta = state.jomlDeltas[index];
        buildSegmentDelta(segment.bindAnchor(), bindRotation, delta);
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
     * The bind-space transform that rotates a part by {@code bindSwing} about {@code bindAnchor}.
     *
     * <p>Package-private and static so the identity it has to satisfy can be tested rather than
     * argued about. Epic Fight draws a part as {@code deformation x delta x vertex}, and the
     * caller's {@code deformation} is {@code pose x toOrigin}; the pair must therefore be
     * equivalent to rotating the <i>posed</i> part by the model-space swing about the
     * <i>posed</i> anchor:
     *
     * <pre>
     *   deformation x delta  ==  T(A) x Q x T(-A) x deformation,   A = deformation x bindAnchor
     * </pre>
     *
     * <p>The anchor is the point the piece is held by ({@code Segment#bindAnchor}), not its bind pivot:
     * the two coincide for a piece whose pivot is where it is held, and differ exactly where rotating
     * about the pivot would slide the piece off the body - see {@code YsmPhysicsParts#contactAnchor}.
     * A caller that passes the pivot here gets the older behaviour, which is what the tests that build a
     * segment by hand rely on.
     *
     * <p>Getting this wrong does not look like a rotation error. Every point of the part is
     * displaced instead of turned, by an amount proportional to how far the pivot is from the
     * model origin - so a piece at hip height moves half a block while a piece at head height
     * moves a block, each in its own direction. On a skirt of two dozen separate panels that
     * reads as the garment shattering, which is why it is pinned by a test rather than by a
     * comment.
     */
    static void buildSegmentDelta(Vector3f bindAnchor, Quaternionf bindSwing, Matrix4f out) {
        if (bindAnchor == null || bindSwing == null || out == null) {
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
                .translate(bindAnchor.x, bindAnchor.y, bindAnchor.z)
                .rotate(bindSwing)
                .translate(-bindAnchor.x, -bindAnchor.y, -bindAnchor.z);
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
        toLocal(out, modelSwing, deformationRotation);
    }

    /** Scratch for {@link #toLocal}: never read across a call, so it cannot be aliased. */
    private static final Quaternionf toLocalScratch = new Quaternionf();

    /**
     * The same rotation expressed in another frame: {@code out = frame^-1 * rotation * frame}.
     *
     * <p>The conjugation, and the reason it is a conjugation rather than a multiplication: a
     * rotation carries no frame of its own, so moving one into the frame a caller works in leaves
     * the <i>rotation</i> alone and changes only the basis its axis is written in. Written as
     * {@code frame^-1 Q frame}, which is the order that composes to the identity when {@code Q} is,
     * and which is its own inverse map.
     *
     * @param out      receives the rotation in {@code frame}, which may alias {@code rotation}
     * @param rotation the rotation in the model's own frame
     * @param frame    the frame to express it in
     */
    static void toLocal(Quaternionf out, Quaternionf rotation, Quaternionf frame) {
        if (rotation == null || frame == null) {
            out.identity();
            return;
        }
        // `out` is allowed to be the same object as either argument, so the conjugated rotation is
        // built from copies: reading an argument after writing `out` would read the answer instead
        // of the input. The inverse is built first, before `out` is touched at all.
        toLocalScratch.set(frame).conjugate();
        out.set(toLocalScratch).mul(rotation).mul(frame);
        if (!Float.isFinite(out.w()) || out.lengthSquared() < 1.0E-8F) {
            out.identity();
        } else {
            out.normalize();
        }
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

    /**
     * The rotation a joint's <b>deformation</b> applies to the geometry it carries, as a quaternion,
     * and the one place the gravity-follow scale reads it from.
     *
     * <p>The deformation is {@code pose x toOrigin}, i.e. the pose's joint transform times the
     * inverse of the joint's own authored one, so it is the identity exactly when the pose has left
     * that joint where the rig authors it - which is the property {@code YsmDynamicBoneSolver#update}
     * needs and the reason it is taken from the deformation rather than from the pose matrix: a pose
     * matrix carries the joint's authored orientation as well, so it is never the identity and could
     * not tell "the animation held this piece still" apart from "the model was authored this way".
     *
     * <p>{@link #rotationOf} already normalises, refuses a collapsed matrix and answers the identity
     * for anything it cannot read - and a caller that gets the identity back gets the unscaled
     * gravity-follow weight, which is the behaviour that predates this parameter.
     */
    static void pivotDeltaOf(OpenMatrix4f deformation, Quaternionf out) {
        if (deformation == null) {
            out.identity();
            return;
        }
        rotationOf(deformation, out);
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

