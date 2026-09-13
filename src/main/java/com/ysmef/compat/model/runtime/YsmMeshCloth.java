package com.ysmef.compat.model.runtime;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.model.Armature;
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
 * <p>The converted mesh and YSM's own model are never on screen at the same time, and which of
 * the two is drawn depends on battle mode: outside battle mode YSM's own renderer draws the
 * player and this mod steps aside, while inside battle mode Epic Fight draws the converted
 * mesh. So the pose that matters - the one a player can actually see moving - is the one passed
 * into {@link YSMMesh#draw}, and that is where this takes its input from.
 *
 * <h2>How a piece is attached</h2>
 *
 * <p>A piece's particles start where its vertices are, in the model's bind space. The ones
 * nearest its attachment end are pinned, and every frame they are placed exactly where the
 * skinning path places them:
 *
 * <pre>    pin = pose(joint) x toOrigin(joint) x bindVertex</pre>
 *
 * <p>the same product {@code YsmCpuRenderPath} and the GPU bone buffer use. The attachment
 * therefore cannot disagree with the geometry it holds; everything hanging under it is solved
 * against it.
 *
 * <p><b>Reconstructing that position instead of computing it is what broke the first three
 * attempts at this.</b> They placed the pin from the bone's motion accumulated from its bind
 * origin, a quantity that crosses between two frames this model does not agree with itself
 * about - its {@code UpBody} binds at (0, 0, 0) and poses at y = 1.09. Each attempt fixed one
 * symptom and produced another: a pin a block above the hair, then a piece dragged across the
 * model, then pieces thrown tens of blocks and stretched until they vanished. The joint
 * matrices have no such problem, because they are the transform rather than a summary of it.
 *
 * <h2>What is capped</h2>
 *
 * <p>Pieces are capped in total particle count, because this is per-vertex work on a phone and
 * a model whose every leaf is named "hair" would otherwise spend the frame on cloth. What was
 * dropped is reported once rather than silently skipped.
 */
public final class YsmMeshCloth {

    /** A piece with fewer particles than this is not worth a solve. */
    private static final int MIN_PARTICLES = 6;

    /**
     * How much of a piece's height is pinned to the skeleton.
     *
     * <p>A fraction rather than a distance, because a bobble and a floor-length braid have to
     * pin a comparable share of themselves. Too small and the top row tears away from the body;
     * too large and most of the piece is rigid and nothing swings.
     */
    private static final float PIN_FRACTION = 0.15F;

    /** One piece of cloth, plus where its result has to be written back. */
    private static final class Piece {
        final YsmClothSolver.Cloth cloth;
        /** The mesh part ordinals this piece covers, and the particles each one owns. */
        final int[] partOrdinal;
        final int[] partStart;
        final int[] partCount;
        /** Where the piece is attached, in model bind space: what its swing turns about. */
        final Vector3f pivot;
        /** The joint the pinned particles follow. */
        final int pinJoint;
        final String boneName;

        Piece(YsmClothSolver.Cloth cloth, int[] partOrdinal, int[] partStart, int[] partCount,
              Vector3f pivot, int pinJoint, String boneName) {
            this.cloth = cloth;
            this.partOrdinal = partOrdinal;
            this.partStart = partStart;
            this.partCount = partCount;
            this.pivot = pivot;
            this.pinJoint = pinJoint;
            this.boneName = boneName;
        }
    }

    /** Per-model cloth, built once from the mesh and then stepped every frame. */
    private static final class State {
        final List<Piece> pieces = new ArrayList<>();
        /** The inverse bind matrix of every joint, so a pin needs no reconstruction. */
        final OpenMatrix4f[] toOrigin;
        double lastStepSeconds = -1.0;
        long frames;
        /** How many part transforms the last frame wrote, and the largest turn among them. */
        int lastWrites;
        float lastTurnDegrees;
        /** How far the cloth sat from where it was bound on the last frame, blocks. */
        float lastSpread;
        /** The worst single link stretch on the last frame, as a fraction. */
        float lastLinkStretch;
        boolean reported;

        State(YSMRuntimeModel model, YSMMesh mesh, Armature armature) {
            this.toOrigin = toOriginOf(armature);
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
            int refused = 0;
            int dropped = 0;
            for (YsmPhysicsChains.Chain chain : chains) {
                // Every bone of the piece, not just its root: a braid is modelled as a run of
                // segments, and the cloth has to cover all of them.
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
                    refused++;
                    continue;
                }
                if (piece.cloth.particleCount() > budget) {
                    dropped++;
                    continue;
                }
                budget -= piece.cloth.particleCount();
                pieces.add(piece);
            }
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [cloth] '{}': {} of {} candidate piece(s) simulated; {} refused (no attachment or too little geometry), {} over budget",
                    model.modelId, pieces.size(), chains.size(), refused, dropped);
        }

        /** Turn one hanging piece into a lattice; null when it has too little to be worth it. */
        private Piece buildPiece(YSMRuntimeModel model, YSMMesh mesh, float[] positions,
                                 List<Map.Entry<String, MeshPart>> parts, Map<String, Integer> partOrdinals,
                                 String prefix, YsmPhysicsChains.Chain chain, List<Integer> bones) {
            // Every vertex of the piece, deduplicated: a vertex is shared between the triangles
            // around it and must not become two particles.
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
                int first = vertices.size();
                int owned = 0;
                List<Integer> local = new ArrayList<>();
                for (var vb : part.getVertices()) {
                    int idx = vb.position;
                    if (idx * 3 + 2 >= positions.length) {
                        continue;
                    }
                    Integer mapped = particleOfVertex.get(idx);
                    if (mapped == null) {
                        mapped = vertices.size();
                        particleOfVertex.put(idx, mapped);
                        vertices.add(idx);
                    }
                    local.add(mapped);
                    owned++;
                }
                // Triangles come from consecutive vertex triples, which is how the exporter
                // emits them.
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

            // Links: every triangle edge, plus a bending link across each shared edge, which is
            // what stops a strand folding flat onto itself.
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
                cloth.vertexOfParticle[i] = vertices.get(i);
            }
            for (int l = 0; l < links.size(); l++) {
                int[] link = links.get(l);
                YsmClothSolver.addLink(cloth, l, link[0], link[1],
                        link[2] == 0 ? YsmClothSolver.structuralStiffness() : YsmClothSolver.bendingStiffness());
            }

            // Attach the highest part of the piece: a hanging piece leaves the body downwards,
            // whether it is hair leaving a skull or a skirt leaving a waist.
            int pinBone = nearestMappedBone(model, chain);
            if (pinBone < 0) {
                return null;
            }
            YSMRuntimeModel.BoneRt pinBoneRt = model.bones[pinBone];
            int pinJoint = pinBoneRt.joint;
            if (pinJoint < 0 || pinJoint >= toOrigin.length) {
                return null;
            }
            float highestY = -Float.MAX_VALUE;
            float lowestY = Float.MAX_VALUE;
            float sumX = 0.0F, sumY = 0.0F, sumZ = 0.0F;
            for (int i = 0; i < vertices.size(); i++) {
                int v = vertices.get(i) * 3;
                sumX += positions[v];
                sumY += positions[v + 1];
                sumZ += positions[v + 2];
                highestY = Math.max(highestY, positions[v + 1]);
                lowestY = Math.min(lowestY, positions[v + 1]);
            }
            float attachment = highestY - Math.max((highestY - lowestY) * PIN_FRACTION, 0.01F);

            int pinned = 0;
            for (int i = 0; i < vertices.size(); i++) {
                int v = vertices.get(i) * 3;
                if (positions[v + 1] >= attachment) {
                    YsmClothSolver.pin(cloth, i, pinJoint);
                    pinned++;
                }
                // Collision: the joint of the nearest mapped body bone to where the particle
                // sits. A particle with no body bone near it gets none.
                int avoid = nearestMappedBoneTo(model, positions[v], positions[v + 1], positions[v + 2]);
                cloth.avoidJoint[i] = avoid >= 0 ? model.bones[avoid].joint : -1;
                cloth.avoidRadius[i] = avoid >= 0 ? YsmClothTuning.current().bodyRadius : 0.0F;
            }
            if (pinned == 0) {
                // Geometry that does not sit on its own attachment still has to hang from
                // something: the highest particle is the attachment by the same definition.
                int best = 0;
                for (int i = 1; i < vertices.size(); i++) {
                    if (positions[vertices.get(i) * 3 + 1] > positions[vertices.get(best) * 3 + 1]) {
                        best = i;
                    }
                }
                YsmClothSolver.pin(cloth, best, pinJoint);
            }

            int count = vertices.size();
            int[] ordinals = new int[owningOrdinals.size()];
            int[] starts = new int[owningStarts.size()];
            int[] counts = new int[owningCounts.size()];
            for (int i = 0; i < ordinals.length; i++) {
                ordinals[i] = owningOrdinals.get(i);
                starts[i] = owningStarts.get(i);
                counts[i] = owningCounts.get(i);
            }
            return new Piece(cloth, ordinals, starts, counts,
                    new Vector3f(sumX / count, sumY / count, sumZ / count), pinJoint, chain.boneName());
        }
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static final Map<Armature, OpenMatrix4f[]> TO_ORIGIN_CACHE = new ConcurrentHashMap<>();

    private YsmMeshCloth() {}

    /** Forget every model's cloth (world leave, resource reload). */
    public static void clear() {
        STATES.clear();
        TO_ORIGIN_CACHE.clear();
    }

    /**
     * The inverse bind matrix of every joint of an armature.
     *
     * <p>Cached per armature: this is the matrix that carries a bind vertex into the joint's
     * own space, and it is what makes the attachment exact - see the class comment.
     */
    private static OpenMatrix4f[] toOriginOf(Armature armature) {
        if (armature == null) {
            return new OpenMatrix4f[0];
        }
        return TO_ORIGIN_CACHE.computeIfAbsent(armature, a -> {
            int jointCount = a.getJointNumber();
            OpenMatrix4f[] toOrigin = new OpenMatrix4f[jointCount];
            for (int j = 0; j < jointCount; j++) {
                var joint = a.searchJointById(j);
                toOrigin[j] = joint != null ? joint.getToOrigin() : OpenMatrix4f.IDENTITY;
            }
            return toOrigin;
        });
    }

    /**
     * Step this model's cloth against the pose about to be drawn and write the result into the
     * mesh's parts.
     *
     * @param mesh     the converted mesh being drawn
     * @param model    the model behind it
     * @param armature the armature the pose belongs to, for the inverse bind matrices
     * @param poses    the live pose matrices, in the model's own bind space
     */
    public static void apply(YSMMesh mesh, YSMRuntimeModel model, Armature armature, OpenMatrix4f[] poses) {
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
                state = new State(model, mesh, armature);
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: [cloth] could not build cloth for model '{}'; it stays rigid",
                        model.modelId, t);
                return;
            }
            STATES.put(model.modelId, state);
        }
        if (state.pieces.isEmpty()) {
            return;
        }

        double now = System.nanoTime() / 1.0E9D;
        float dt = state.lastStepSeconds < 0.0 ? 0.0F : (float) (now - state.lastStepSeconds);
        state.lastStepSeconds = now;

        float[] positions = mesh.positions();
        YsmClothTuning tuning = YsmClothTuning.current();
        int written = 0;
        float largestStretch = 0.0F;
        float worstLink = 0.0F;
        for (Piece piece : state.pieces) {
            YsmClothSolver.INSTANCE.step(piece.cloth, poses, state.toOrigin, piece.pinJoint, dt, tuning);
            float[] result = writeBack(mesh, piece, positions);
            written += (int) result[0];
            state.lastTurnDegrees = Math.max(state.lastTurnDegrees, result[1]);
            largestStretch = Math.max(largestStretch, result[2]);
            worstLink = Math.max(worstLink, YsmClothSolver.worstLinkStretch(piece.cloth));
        }
        state.lastWrites = written;
        state.lastSpread = largestStretch;
        state.lastLinkStretch = worstLink;

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
                    "YSM-EF Compat: [cloth] frame {}: dt={}ms, {} piece(s) solved, cloth moved {} blocks, worst link stretch {}%, {} part transform(s) written, largest turn {}deg",
                    state.frames, Math.round(dt * 1000.0F), state.pieces.size(),
                    Math.round(state.lastSpread * 1000.0F) / 1000.0F,
                    Math.round(state.lastLinkStretch * 1000.0F) / 10.0F, written,
                    Math.round(state.lastTurnDegrees * 10.0F) / 10.0F);
        }
    }

    // Scratch, so a per-frame write-back allocates nothing.
    private static final Vector3f scratchFrom = new Vector3f();
    private static final Vector3f scratchTo = new Vector3f();
    private static final Vector3f scratchSwingFrom = new Vector3f();
    private static final Vector3f scratchFromAxis = new Vector3f();
    private static final Vector3f scratchToAxis = new Vector3f();
    private static final Vector3f scratchCross = new Vector3f();
    private static final Quaternionf scratchRotation = new Quaternionf();
    private static final Matrix4f scratchDelta = new Matrix4f();
    private static final OpenMatrix4f scratchOpen = new OpenMatrix4f();
    private static final float[] scratchResult = new float[3];

    /** Below this a piece counts as settled and no transform is written. */
    private static final float MIN_TURN_RADIANS = 0.003F;
    /** Above this a rotation is not applied: see {@link #rotationBetween}. */
    private static final float MAX_TURN_RADIANS = 1.4F;

    /**
     * Write each part's cloth result as the transform it is drawn with.
     *
     * <p>A rigid part carries exactly one transform, so the solve's per-vertex result has to be
     * summarised as one rotation per part: the turn taking the direction the part hangs in at
     * bind onto the direction it hangs in now, both measured from where the piece is attached.
     *
     * @return {transforms written, largest turn in degrees, largest movement in blocks}
     */
    private static float[] writeBack(YSMMesh mesh, Piece piece, float[] positions) {
        int written = 0;
        float largestTurn = 0.0F;
        float largestMove = 0.0F;
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
                int v = piece.cloth.vertexOf(p) * 3;
                if (v + 2 >= positions.length) {
                    continue;
                }
                fromX += positions[v];
                fromY += positions[v + 1];
                fromZ += positions[v + 2];
                piece.cloth.position(p, scratchTo);
                toX += scratchTo.x;
                toY += scratchTo.y;
                toZ += scratchTo.z;
                n++;
            }
            if (n == 0) {
                continue;
            }
            scratchFrom.set(fromX / n, fromY / n, fromZ / n).sub(piece.pivot);
            // The <i>current</i> attachment is what the swing is measured from, so the number
            // reports the cloth's own movement rather than the body's travel; the bind pivot is
            // only the reference the rotation maps back onto.
            piece.cloth.attachment(scratchTo);
            largestMove = Math.max(largestMove, scratchTo.distance(
                    toX / n, toY / n, toZ / n));
            scratchTo.set(toX / n, toY / n, toZ / n).sub(piece.pivot);
            Quaternionf turn = rotationBetween(scratchFrom, scratchTo);
            if (turn == null) {
                continue;
            }
            scratchDelta.identity().rotate(turn);
            importInto(scratchOpen, scratchDelta);
            mesh.setRuntimeTransformAt(piece.partOrdinal[i], scratchOpen);
            written++;
            largestTurn = Math.max(largestTurn, (float) Math.toDegrees(turn.angle()));
        }
        scratchResult[0] = written;
        scratchResult[1] = largestTurn;
        scratchResult[2] = largestMove;
        return scratchResult;
    }

    /**
     * The rotation turning the direction a part hangs in at bind onto the direction it hangs in
     * now, both measured from where the piece is attached.
     *
     * <p>Null when there is nothing worth applying: the part has not turned, no axis is
     * determined, or it has turned further than one rigid transform can honestly express.
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
        // axis = from x to, and its length is sin(angle): the angle comes from atan2 so a small
        // swing is measured accurately instead of being lost to acos.
        scratchToAxis.cross(scratchFromAxis, scratchCross);
        float sine = scratchCross.length();
        float angle = (float) Math.atan2(sine, dot);
        if (sine < 1.0E-5F || angle < MIN_TURN_RADIANS || angle > MAX_TURN_RADIANS) {
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
