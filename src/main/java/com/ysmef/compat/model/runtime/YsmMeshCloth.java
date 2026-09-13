package com.ysmef.compat.model.runtime;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cloth simulation for the converted mesh as Epic Fight draws it.
 *
 * <h2>Why this is driven from the draw call</h2>
 *
 * <p>The converted mesh and YSM's own model are never on screen at the same time, and
 * which one is drawn depends on battle mode: outside battle mode YSM's own renderer draws
 * the player and this mod steps aside, while inside battle mode Epic Fight draws the
 * converted mesh. So the pose that matters - the one a player can actually see moving - is
 * the one passed into {@link YSMMesh#draw}, and this class takes its input from there.
 *
 * <h2>What is simulated</h2>
 *
 * <p>For every hanging piece the classification finds, the piece's own triangles are
 * turned into a particle lattice: one particle per vertex, a link along every triangle
 * edge, and a bending link across every shared edge so the piece keeps its shape instead
 * of collapsing into a ribbon. The vertices nearest the piece's root are pinned to the
 * skeleton and follow it exactly; the rest are solved.
 *
 * <p>Pieces are capped in total particle count, because this is per-vertex work on a phone
 * and a model whose every leaf is named "hair" would otherwise spend the frame on cloth.
 * What was dropped is reported once rather than silently skipped.
 */
public final class YsmMeshCloth {

    /**
     * Total cloth particles one model may simulate, before the config says otherwise. The
     * number is chosen from what real models carry - the test model's hanging geometry is a
     * few thousand vertices - so the default simulates all of it and the cap only stops a
     * pathological model.
     */
    private static final int MAX_PARTICLES = YsmClothSolver.DEFAULT_MAX_PARTICLES;

    /** A piece with fewer particles than this is not worth a solve. */
    private static final int MIN_PARTICLES = 6;

    /** Per-step displacement ceiling per particle, blocks; see the solver. */
    private static final float MAX_STEP_VELOCITY = 0.4F;

    /** One piece of cloth, plus where its result has to be written back. */
    private static final class Piece {
        final YsmClothSolver.Cloth cloth;
        /** The mesh part ordinals this piece covers, and the particles each one owns. */
        final int[] partOrdinal;
        /** For each part, the half-open particle range it owns: [start, start + count). */
        final int[] partStart;
        final int[] partCount;
        /** The mesh vertex index of each particle. */
        final int[] vertexOfParticle;
        /** The piece's pivot in model bind space: what its rotation is measured about. */
        final Vector3f pivot;
        final String boneName;

        Piece(YsmClothSolver.Cloth cloth, int[] partOrdinal, int[] partStart, int[] partCount,
              int[] vertexOfParticle, Vector3f pivot, String boneName) {
            this.cloth = cloth;
            this.partOrdinal = partOrdinal;
            this.partStart = partStart;
            this.partCount = partCount;
            this.vertexOfParticle = vertexOfParticle;
            this.pivot = pivot;
            this.boneName = boneName;
        }
    }

    /** Per-model cloth, built once from the mesh and then stepped every frame. */
    private static final class State {
        final List<Piece> pieces = new ArrayList<>();
        /** Body bones the cloth follows and collides with, with their bind rotations. */
        final Quaternionf[] poseCache;
        final Vector3f[] originCache;
        final int boneSlots;
        double lastStepSeconds = -1.0;
        long frames;
        /** How many part transforms the last frame wrote, and the largest turn among them. */
        int lastWrites;
        float lastTurnDegrees;
        boolean reported;

        State(YSMRuntimeModel model, YSMMesh mesh) {
            this.boneSlots = model.bones.length;
            this.poseCache = new Quaternionf[boneSlots];
            this.originCache = new Vector3f[boneSlots];
            for (int i = 0; i < boneSlots; i++) {
                this.poseCache[i] = new Quaternionf();
                this.originCache[i] = new Vector3f();
            }
            build(model, mesh);
        }

        private void build(YSMRuntimeModel model, YSMMesh mesh) {
            float[] positions = mesh.positions();
            if (positions == null || positions.length < 3) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [cloth] '{}': the mesh exposes no vertex positions ({}), so there is nothing to simulate",
                        model.modelId, positions == null ? "null" : String.valueOf(positions.length));
                return;
            }
            Map<String, Integer> partOrdinals = new HashMap<>();
            List<Map.Entry<String, MeshPart>> parts = new ArrayList<>(mesh.getPartEntrySetSafe());
            String prefix = EFMeshJsonWriter.BONE_PART_PREFIX;
            for (int i = 0; i < parts.size(); i++) {
                partOrdinals.put(parts.get(i).getKey(), i);
            }

            List<YsmPhysicsChains.Chain> chains = YsmPhysicsChains.build(model.bones,
                    bone -> hasGeometry(parts, prefix, model, bone));
            int budget = YsmClothTuning.maxParticles();
            int dropped = 0;
            int tooSmall = 0;
            int unattached = 0;
            for (YsmPhysicsChains.Chain chain : chains) {
                // Every bone of the piece, not just its root: a braid is modelled as a run
                // of segments, and the cloth has to cover all of them.
                List<Integer> bones = new ArrayList<>();
                bones.add(chain.boneIndex());
                for (int i = 0; i < model.bones.length; i++) {
                    if (i != chain.boneIndex() && model.bones[i] != null
                            && descendsFrom(model.bones, i, chain.boneIndex())) {
                        bones.add(i);
                    }
                }
                Piece piece = buildPiece(model, mesh, positions, parts, partOrdinals, prefix, chain, bones);
                if (piece == null) {
                    unattached++;
                    continue;
                }
                if (piece.cloth.particleCount() < MIN_PARTICLES) {
                    tooSmall++;
                    continue;
                }
                if (piece.cloth.particleCount() > budget) {
                    dropped++;
                    continue;
                }
                budget -= piece.cloth.particleCount();
                pieces.add(piece);
            }
            // Reported here rather than on the first simulated frame: this is the one place
            // that knows why a piece was refused, and "no cloth on screen" has several
            // causes that look identical in game.
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [cloth] '{}': {} of {} candidate piece(s) simulated; refused: {} unattached, {} too small, {} over budget",
                    model.modelId, pieces.size(), chains.size(), unattached, tooSmall, dropped);
        }

        /**
         * Turn one hanging piece into a lattice.
         *
         * <p>Returns null when the piece has too little geometry to be worth solving, which
         * is the common case: a model's hanging bones are mostly one or two quads.
         */
        private Piece buildPiece(YSMRuntimeModel model, YSMMesh mesh, float[] positions,
                                 List<Map.Entry<String, MeshPart>> parts, Map<String, Integer> partOrdinals,
                                 String prefix, YsmPhysicsChains.Chain chain, List<Integer> bones) {
            // Every vertex of the piece, deduplicated: a vertex is shared between the
            // triangles around it and must not become two particles.
            Map<Integer, Integer> particleOfVertex = new HashMap<>();
            List<Integer> vertices = new ArrayList<>();
            List<int[]> triangles = new ArrayList<>();
            List<Integer> owningOrdinals = new ArrayList<>();
            List<Integer> owningStarts = new ArrayList<>();
            List<Integer> owningCounts = new ArrayList<>();

            for (int bone : bones) {
                YSMRuntimeModel.BoneRt boneRt = model.bones[bone];
                if (boneRt == null || boneRt.name == null) {
                    continue;
                }
                Integer ordinal = partOrdinals.get(prefix + boneRt.name);
                if (ordinal == null) {
                    continue;
                }
                MeshPart part = parts.get(ordinal).getValue();
                if (part == null || part.getVertices() == null) {
                    continue;
                }
                int owned = 0;
                int first = vertices.size();
                for (var vb : part.getVertices()) {
                    int idx = vb.position;
                    if (idx * 3 + 2 >= positions.length) {
                        continue;
                    }
                    if (!particleOfVertex.containsKey(idx)) {
                        particleOfVertex.put(idx, vertices.size());
                        vertices.add(idx);
                    }
                    // Counted for every vertex of the part, not only the newly added ones:
                    // a vertex shared with an earlier part is still part of this part's
                    // geometry and still has to move with it.
                    owned++;
                }
                // Triangles come from consecutive vertex triples, which is how the exporter
                // emits them.
                List<Integer> local = new ArrayList<>();
                for (var vb : part.getVertices()) {
                    Integer mapped = particleOfVertex.get(vb.position);
                    if (mapped != null) {
                        local.add(mapped);
                    }
                }
                for (int t = 0; t + 2 < local.size(); t += 3) {
                    triangles.add(new int[]{local.get(t), local.get(t + 1), local.get(t + 2)});
                }
                if (owned > 0) {
                    owningOrdinals.add(ordinal);
                    owningStarts.add(first);
                    owningCounts.add(owned);
                }
            }

            if (vertices.size() < MIN_PARTICLES) {
                return null;
            }

            // Links: every triangle edge, plus a bending link across each shared edge.
            Map<Long, Integer> edgeLink = new HashMap<>();
            List<int[]> links = new ArrayList<>();
            for (int[] tri : triangles) {
                for (int e = 0; e < 3; e++) {
                    int a = tri[e];
                    int b = tri[(e + 1) % 3];
                    if (a == b) {
                        continue;
                    }
                    long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
                    if (edgeLink.putIfAbsent(key, links.size()) == null) {
                        links.add(new int[]{a, b, 0});
                    }
                }
            }
            // Bending links: the two vertices opposite a shared edge. They are what stops a
            // strand folding flat onto itself.
            Map<Long, Integer> edgeOpposite = new HashMap<>();
            for (int[] tri : triangles) {
                for (int e = 0; e < 3; e++) {
                    int a = tri[e];
                    int b = tri[(e + 1) % 3];
                    int opposite = tri[(e + 2) % 3];
                    long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
                    Integer previous = edgeOpposite.put(key, opposite);
                    if (previous != null && previous != opposite) {
                        links.add(new int[]{previous, opposite, 1});
                    }
                }
            }

            YsmClothSolver.Cloth cloth = YsmClothSolver.allocate(vertices.size(), links.size());
            for (int i = 0; i < vertices.size(); i++) {
                int v = vertices.get(i) * 3;
                YsmClothSolver.initParticle(cloth, i, positions[v], positions[v + 1], positions[v + 2]);
            }
            for (int l = 0; l < links.size(); l++) {
                int[] link = links.get(l);
                YsmClothSolver.addLink(cloth, l, link[0], link[1],
                        link[2] == 0 ? YsmClothSolver.structuralStiffness() : YsmClothSolver.bendingStiffness());
            }

            // The piece hangs from its own root bone, and the skeleton bone that root sits
            // on is what carries it: pinning to the root bone's own pivot instead put the
            // attachment region at the wrong place - a head bone's pivot is not where a lock
            // of hair leaves the skull - and every piece came out unattached.
            int pinBone = nearestMappedBone(model, chain);
            if (pinBone < 0) {
                return null;
            }
            Quaternionf bindRotation = new Quaternionf();
            YsmClothSolver.rotationOf(model.bones[pinBone].bindWorld, bindRotation);
            Quaternionf inverseBind = new Quaternionf(bindRotation).conjugate();
            float pivotX = model.bones[chain.boneIndex()].bindWorld.m30();
            float pivotY = model.bones[chain.boneIndex()].bindWorld.m31();
            float pivotZ = model.bones[chain.boneIndex()].bindWorld.m32();

            // The piece's own extent decides what counts as "attached": a fixed radius would
            // pin either everything or nothing depending on the model's scale.
            float extent = 0.0F;
            for (int i = 0; i < vertices.size(); i++) {
                int v = vertices.get(i) * 3;
                float d = (float) Math.sqrt(sq(positions[v] - pivotX) + sq(positions[v + 1] - pivotY)
                        + sq(positions[v + 2] - pivotZ));
                extent = Math.max(extent, d);
            }
            float pinRadius = Math.max(extent * 0.25F, 0.02F);

            Vector3f local = new Vector3f();
            int pinned = 0;
            int nearest = -1;
            float nearestDistance = Float.MAX_VALUE;
            for (int i = 0; i < vertices.size(); i++) {
                int v = vertices.get(i) * 3;
                float dx = positions[v] - pivotX;
                float dy = positions[v + 1] - pivotY;
                float dz = positions[v + 2] - pivotZ;
                float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = i;
                }
                if (distance <= pinRadius) {
                    YsmClothSolver.toLocal(local, dx, dy, dz, inverseBind);
                    YsmClothSolver.pin(cloth, i, pinBone, local.x, local.y, local.z);
                    pinned++;
                }
                // Collision: the nearest mapped body bone to where the particle sits.
                int avoid = nearestMappedBoneTo(model, positions[v], positions[v + 1], positions[v + 2]);
                cloth.avoidBone[i] = avoid;
                cloth.avoidRadius[i] = avoid >= 0 ? YsmClothTuning.current().bodyRadius : 0.0F;
            }
            if (pinned == 0 && nearest >= 0) {
                // Geometry that does not actually sit on its own root bone still has to hang
                // from something; pinning the single closest vertex keeps the piece attached
                // instead of dropping it, and is reported through the pinned count.
                int v = vertices.get(nearest) * 3;
                YsmClothSolver.toLocal(local, positions[v] - pivotX, positions[v + 1] - pivotY,
                        positions[v + 2] - pivotZ, inverseBind);
                YsmClothSolver.pin(cloth, nearest, pinBone, local.x, local.y, local.z);
                pinned = 1;
            }
            if (pinned == 0) {
                // A piece with nothing attached to the body would fall off the model.
                return null;
            }

            int[] ordinals = new int[owningOrdinals.size()];
            int[] starts = new int[owningStarts.size()];
            int[] counts = new int[owningCounts.size()];
            for (int i = 0; i < ordinals.length; i++) {
                ordinals[i] = owningOrdinals.get(i);
                starts[i] = owningStarts.get(i);
                counts[i] = owningCounts.get(i);
            }
            int[] particleVertices = new int[vertices.size()];
            for (int i = 0; i < particleVertices.length; i++) {
                particleVertices[i] = vertices.get(i);
            }
            return new Piece(cloth, ordinals, starts, counts, particleVertices,
                    new Vector3f(pivotX, pivotY, pivotZ), chain.boneName());
        }
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private YsmMeshCloth() {}

    /** Forget every model's cloth (world leave, resource reload). */
    public static void clear() {
        STATES.clear();
    }

    /** Whether any model is currently simulating cloth. */
    public static boolean isActive() {
        return !STATES.isEmpty();
    }

    /**
     * Step this model's cloth against the pose about to be drawn and write the result into
     * the mesh's parts.
     *
     * @param mesh   the converted mesh being drawn
     * @param model  the model behind it
     * @param poses  the live pose matrices, in the model's own bind space
     */
    public static void apply(YSMMesh mesh, YSMRuntimeModel model, OpenMatrix4f[] poses) {
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

        State state = STATES.get(model.modelId);
        if (state == null) {
            try {
                state = new State(model, mesh);
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: [cloth] could not build cloth for model '{}'; it stays rigid",
                        model.modelId, t);
                state = null;
            }
            if (state == null) {
                return;
            }
            STATES.put(model.modelId, state);
        }
        if (state.pieces.isEmpty()) {
            // Reported rather than silently returned: the build above already says why each
            // candidate was refused, and this says that the draw path itself is running.
            if (!state.reported) {
                state.reported = true;
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [cloth] '{}': the draw path ran but no piece qualified, so nothing is simulated",
                        model.modelId);
            }
            return;
        }

        double now = System.nanoTime() / 1.0E9D;
        float dt = state.lastStepSeconds < 0.0 ? 0.0F : (float) (now - state.lastStepSeconds);
        state.lastStepSeconds = now;

        fillBoneState(model, poses, state);
        float[] positions = mesh.positions();
        YsmClothTuning tuning = YsmClothTuning.current();
        state.lastWrites = 0;
        state.lastTurnDegrees = 0.0F;
        for (Piece piece : state.pieces) {
            YsmClothSolver.INSTANCE.step(piece.cloth, state.poseCache, state.originCache,
                    state.boneSlots, dt, MAX_STEP_VELOCITY, tuning);
            writeBack(mesh, state, piece, positions);
        }

        state.frames++;
        if (!state.reported) {
            state.reported = true;
            int particles = 0;
            int pinned = 0;
            StringBuilder names = new StringBuilder();
            for (Piece piece : state.pieces) {
                particles += piece.cloth.particleCount();
                pinned += piece.cloth.pinnedCount();
                names.append(names.length() == 0 ? "" : ", ").append(piece.boneName)
                        .append("(").append(piece.cloth.particleCount()).append(")");
            }
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [cloth] model '{}': {} piece(s), {} particles, {} pinned [{}]",
                    model.modelId, state.pieces.size(), particles, pinned, names);
        }
        if (state.frames % 300 == 0) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [cloth] frame {}: dt={}ms, {} piece(s) solved, {} part transform(s) written, largest turn {}deg (cap {}deg)",
                    state.frames, Math.round(dt * 1000.0F), state.pieces.size(),
                    state.lastWrites, Math.round(state.lastTurnDegrees * 10.0F) / 10.0F, Math.round(MAX_TURN_DEGREES));
        }
    }

    /**
     * The current rotation and origin of every body bone, which is all the solver needs
     * from Epic Fight's pose.
     */
    private static void fillBoneState(YSMRuntimeModel model, OpenMatrix4f[] poses, State state) {
        for (int bone = 0; bone < state.boneSlots; bone++) {
            int joint = model.bones[bone].joint;
            if (joint < 0 || joint >= poses.length || poses[joint] == null) {
                state.poseCache[bone].identity();
                state.originCache[bone].set(0.0F, 0.0F, 0.0F);
                continue;
            }
            OpenMatrix4f pose = poses[joint];
            state.originCache[bone].set(pose.m30, pose.m31, pose.m32);
            scratchPose.set(
                    pose.m00, pose.m01, pose.m02, pose.m03,
                    pose.m10, pose.m11, pose.m12, pose.m13,
                    pose.m20, pose.m21, pose.m22, pose.m23,
                    0.0F, 0.0F, 0.0F, 1.0F);
            state.poseCache[bone].setFromUnnormalized(scratchPose);
            if (!Float.isFinite(state.poseCache[bone].w())
                    || state.poseCache[bone].lengthSquared() < 1.0E-6F) {
                state.poseCache[bone].identity();
            } else {
                state.poseCache[bone].normalize();
            }
        }
    }

    private static final Matrix4f scratchPose = new Matrix4f();

    /**
     * Turn each particle's displacement into the transform its part is drawn with.
     *
     * <p>A rigid part carries exactly one transform, so a part whose particles moved
     * differently cannot be expressed and is left rigid - reported rather than applied
     * wrongly. Pieces whose cloth covers one part each, which is the usual shape of hair
     * and skirt geometry, are exact.
     */
    /**
     * Write each part's cloth result as the transform it is drawn with.
     *
     * <p>A rigid part carries exactly one transform, so the solve's per-vertex result has to
     * be summarised as one rotation per part. The rotation chosen is the one taking the
     * direction the part hangs in at bind - from the piece's pivot to the part's centroid -
     * onto the direction it hangs in now. That is the visible part of the motion (a piece
     * that swings), and it is measured about the piece's pivot rather than about the model
     * origin, because the model origin is nowhere near the hair.
     */
    private static final Vector3f scratchBindCentroid = new Vector3f();
    private static final Vector3f scratchSolvedCentroid = new Vector3f();
    private static final Vector3f scratchFromAxis = new Vector3f();
    private static final Vector3f scratchToAxis = new Vector3f();
    private static final Vector3f scratchCross = new Vector3f();
    private static final Quaternionf scratchRotation = new Quaternionf();
    private static final Matrix4f scratchDelta = new Matrix4f();
    private static final OpenMatrix4f scratchOpen = new OpenMatrix4f();

    /** Below this the piece counts as settled and no transform is written. */
    private static final float MIN_TURN_RADIANS = 0.005F;
    /**
     * Above this the rotation is not applied.
     *
     * <p>Cloth cannot turn 80 degrees in one frame; a rotation that large means the solve
     * produced a configuration one rigid transform cannot express - a piece whose vertices
     * disagreed, or a pinned particle that jumped - and applying it is what throws hair off
     * a model instead of moving it.
     */
    private static final float MAX_TURN_RADIANS = 1.4F;

    /** {@link #MAX_TURN_RADIANS} in degrees, for the one-line report of what was applied. */
    private static final float MAX_TURN_DEGREES = (float) Math.toDegrees(MAX_TURN_RADIANS);

    private static void writeBack(YSMMesh mesh, State state, Piece piece, float[] positions) {
        for (int i = 0; i < piece.partOrdinal.length; i++) {
            int start = piece.partStart[i];
            int count = piece.partCount[i];
            if (count <= 0 || start < 0) {
                continue;
            }
            float fromX = 0.0F, fromY = 0.0F, fromZ = 0.0F;
            float toX = 0.0F, toY = 0.0F, toZ = 0.0F;
            int n = 0;
            for (int p = start; p < start + count && p < piece.cloth.particleCount(); p++) {
                int vertex = piece.vertexOfParticle[p] * 3;
                if (vertex + 2 >= positions.length) {
                    continue;
                }
                fromX += positions[vertex];
                fromY += positions[vertex + 1];
                fromZ += positions[vertex + 2];
                piece.cloth.position(p, scratchSolvedCentroid);
                toX += scratchSolvedCentroid.x;
                toY += scratchSolvedCentroid.y;
                toZ += scratchSolvedCentroid.z;
                n++;
            }
            if (n == 0) {
                continue;
            }
            scratchBindCentroid.set(fromX / n, fromY / n, fromZ / n).sub(piece.pivot);
            scratchSolvedCentroid.set(toX / n, toY / n, toZ / n).sub(piece.pivot);
            Quaternionf turn = rotationBetween(scratchBindCentroid, scratchSolvedCentroid);
            if (turn != null) {
                scratchDelta.identity().rotate(turn);
                importInto(scratchOpen, scratchDelta);
                mesh.setRuntimeTransformAt(piece.partOrdinal[i], scratchOpen);
                state.lastWrites++;
                float degrees = (float) Math.toDegrees(turn.angle());
                if (degrees > state.lastTurnDegrees) {
                    state.lastTurnDegrees = degrees;
                }
            }
        }
    }

    /**
     * The rotation taking one direction onto another, both measured from the same pivot,
     * or null when there is nothing expressible to apply.
     *
     * <p>Built from the cross product and {@code atan2} rather than from the shortest-arc
     * formula, because the two directions can end up nearly opposed - and there the
     * shortest arc is ill-conditioned, reporting a half-turn about an arbitrary axis, which
     * throws the piece to the other side of the model. This form stays continuous and falls
     * through to "no rotation" instead.
     *
     * <p>The result is also capped: a piece of cloth cannot turn further than
     * {@link #MAX_TURN_DEGREES} in one frame, so a larger turn means the solve produced
     * something a single rigid transform cannot represent, and the part is better left where
     * the animation put it.
     */
    private static Quaternionf rotationBetween(Vector3f from, Vector3f to) {
        float fromLength = from.length();
        float toLength = to.length();
        if (fromLength < 1.0E-4F || toLength < 1.0E-4F) {
            return null;
        }
        scratchFromAxis.set(from).div(fromLength);
        scratchToAxis.set(to).div(toLength);
        float dot = Math.max(-1.0F, Math.min(1.0F, scratchFromAxis.dot(scratchToAxis)));
        // axis = from x to, the axis the rotation turns about; its length is sin(angle).
        scratchToAxis.cross(scratchFromAxis, scratchCross);
        float sine = scratchCross.length();
        float angle = (float) Math.atan2(sine, dot);
        if (angle < MIN_TURN_RADIANS || angle > MAX_TURN_RADIANS || sine < 1.0E-5F) {
            // Settled, collapsed, or beyond anything a rigid transform should express.
            return null;
        }
        scratchCross.div(sine);
        scratchRotation.fromAxisAngleRad(scratchCross.x, scratchCross.y, scratchCross.z, angle);
        return scratchRotation;
    }

    private static void importInto(OpenMatrix4f out, Matrix4f m) {
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
    }

    private static boolean hasGeometry(List<Map.Entry<String, MeshPart>> parts, String prefix,
                                       YSMRuntimeModel model, int bone) {
        YSMRuntimeModel.BoneRt boneRt = model.bones[bone];
        if (boneRt == null || boneRt.name == null) {
            return false;
        }
        for (Map.Entry<String, MeshPart> entry : parts) {
            if (entry.getKey().equals(prefix + boneRt.name)) {
                MeshPart part = entry.getValue();
                return part != null && part.getVertices() != null && !part.getVertices().isEmpty();
            }
        }
        return false;
    }

    private static boolean descendsFrom(YSMRuntimeModel.BoneRt[] bones, int bone, int ancestor) {
        int guard = 0;
        for (int i = bones[bone].parent; i >= 0 && guard++ <= bones.length; i = bones[i].parent) {
            if (i == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** The mapped bone to hang this piece from: the root's nearest mapped ancestor. */
    private static int nearestMappedBone(YSMRuntimeModel model, YsmPhysicsChains.Chain chain) {
        int guard = 0;
        for (int i = model.bones[chain.boneIndex()].parent; i >= 0 && guard++ <= model.bones.length;
             i = model.bones[i].parent) {
            if (model.bones[i].mapped) {
                return i;
            }
        }
        return -1;
    }

    /** The mapped bone whose bind pivot is nearest a point - the body part it sits on. */
    private static int nearestMappedBoneTo(YSMRuntimeModel model, float x, float y, float z) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < model.bones.length; i++) {
            YSMRuntimeModel.BoneRt bone = model.bones[i];
            if (bone == null || !bone.mapped) {
                continue;
            }
            float d = sq(bone.bindWorld.m30() - x) + sq(bone.bindWorld.m31() - y)
                    + sq(bone.bindWorld.m32() - z);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private static float sq(float value) {
        return value * value;
    }
}
