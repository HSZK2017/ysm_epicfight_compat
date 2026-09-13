package com.ysmef.compat.model.runtime;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Secondary motion for the converted mesh, driven by the pose Epic Fight is about to
 * draw.
 *
 * <h2>Why the animator's own simulation is not enough</h2>
 *
 * <p>The converted mesh and YSM's own model are never on screen at the same time, and
 * which of the two is drawn depends on battle mode: outside battle mode YSM's own
 * renderer draws the player and this mod deliberately steps aside, while inside battle
 * mode Epic Fight draws the converted mesh. The animator's chain simulation runs where
 * YSM's scripts are evaluated, which is the outside-battle-mode path - so on its own it
 * never moves anything a player can actually see. This class drives the same
 * classification and the same spring from the pose that is really being drawn.
 *
 * <h2>What feeds the dynamics</h2>
 *
 * <p>A chain's pivot is its bone's position in the current pose, and its rest tip is
 * where the piece hangs undisturbed - the bind tip carried through that same pose, so it
 * follows the animation without the swing. Those are exactly the two inputs
 * {@link YsmPhysicsSimulator} asks for, which is why the simulation itself needed no
 * change to run here.
 */
public final class YsmMeshSecondaryMotion {

    /**
     * Per-model simulation state. Shared by every entity drawn with that model: the
     * chains are a property of the model's skeleton, and one state carried frame to frame
     * is what gives the spring a history to work from.
     */
    private static final class State {
        final YsmPhysicsChains.Chain[] chains;
        final YsmPhysicsSimulator.ChainState[] sims;
        /** The bone's position in the current pose, model space. */
        final Vector3f[] pivots;
        /** Where the piece would hang undisturbed right now, model space. */
        final Vector3f[] restTips;
        /** Each chain's hang offset, in its own bone's bind frame. */
        final Vector3f[] bindOffsets;
        final Quaternionf[] bindRots;
        /** Chain index -> the mesh part ordinals carrying that bone's geometry. */
        final int[][] parts;
        /** Per chain, the transform handed to the mesh. Held so it is not shared. */
        final OpenMatrix4f[] deltas;
        /** JOML twin of {@link #deltas}, where the rotation is composed. */
        final org.joml.Matrix4f[] deltaScratch;
        double lastStepSeconds = -1.0;
        boolean logged;
        boolean wiringLogged;

        State(YSMRuntimeModel model) {
            List<YsmPhysicsChains.Chain> classified = YsmPhysicsChains.build(model);
            this.chains = classified.toArray(new YsmPhysicsChains.Chain[0]);
            this.sims = new YsmPhysicsSimulator.ChainState[chains.length];
            this.pivots = new Vector3f[chains.length];
            this.restTips = new Vector3f[chains.length];
            this.bindOffsets = new Vector3f[chains.length];
            this.bindRots = new Quaternionf[chains.length];
            this.parts = new int[chains.length][];
            this.deltas = new OpenMatrix4f[chains.length];
            this.deltaScratch = new org.joml.Matrix4f[chains.length];

            for (int c = 0; c < chains.length; c++) {
                this.sims[c] = new YsmPhysicsSimulator.ChainState();
                YSMRuntimeModel.BoneRt bone = model.bones[chains[c].boneIndex()];
                this.pivots[c] = new Vector3f(bone.bindWorld.m30(), bone.bindWorld.m31(), bone.bindWorld.m32());
                // The average offset to the bones under this one, in this bone's own bind
                // frame: a piece of hair is usually a short fan of strands, and their
                // midpoint is a better lever than any single strand.
                this.bindOffsets[c] = bindHangOffset(model, chains[c]);
                this.restTips[c] = new Vector3f();
                this.bindRots[c] = new Quaternionf();
                YsmPhysicsSimulator.rotationOf(bone.bindWorld, this.bindRots[c]);
                this.deltas[c] = new OpenMatrix4f();
                this.deltaScratch[c] = new org.joml.Matrix4f();
            }
        }
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private YsmMeshSecondaryMotion() {}

    /** Forget every model's state (world leave, resource reload). */
    public static void clear() {
        STATES.clear();
    }

    /**
     * Advance this model's chains against the pose about to be drawn and write the swing
     * into the mesh's parts.
     *
     * <p>Called after {@code YSMRuntimeBridge.apply} has cleared the mesh's runtime
     * transforms, because those transforms are what this writes: an earlier caller's
     * output must not be composed with this one's.
     *
     * @param mesh   the converted mesh being drawn
     * @param model  the model behind it
     * @param entity the entity it belongs to
     * @param poses  the live pose matrices, in the model's own bind space
     */
    public static void apply(YSMMesh mesh, YSMRuntimeModel model, LivingEntity entity, OpenMatrix4f[] poses) {
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

        State state = STATES.get(model.modelId);
        if (state == null) {
            try {
                state = new State(model);
            } catch (Throwable t) {
                // A model whose bone table defeats the classifier must still draw.
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: [physics] could not classify chains for model '{}'; secondary motion stays off for it",
                        model.modelId, t);
                state = null;
            }
            if (state == null) {
                return;
            }
            STATES.put(model.modelId, state);
        }

        if (!state.logged) {
            state.logged = true;
            // The counterpart of the animator's report, and the one that matters here:
            // that one says what the model offers on the script path, this one says what
            // is being driven on the mesh a player can actually see.
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [physics] converted mesh for model '{}': {} chain bone(s){}; secondary motion {}",
                    model.modelId, state.chains.length,
                    state.chains.length == 0
                            ? " - no bone of this model reads as hanging cloth or hair"
                            : " [" + names(state) + "]",
                    enabled ? "active" : "off (config enableSecondaryMotion)");
        }
        if (state.chains.length == 0 || !enabled) {
            return;
        }

        // A chain whose bone carries no mesh part is not necessarily inert: the swing is
        // written to the bone's part, and the bones under it move with their parent, so a
        // hair root with no geometry of its own still swings every strand beneath it.
        // What would be a real gap is a chain with no geometry anywhere under it - that one
        // is simulated, written nowhere, and nothing says so. Reported once, because that
        // is otherwise indistinguishable from "the swing is too small to see".
        if (!state.wiringLogged) {
            state.wiringLogged = true;
            int ownGeometry = 0;
            int moving = 0;
            StringBuilder inert = new StringBuilder();
            for (YsmPhysicsChains.Chain chain : state.chains) {
                if (partsOf(mesh, model, chain.boneIndex()).length > 0) {
                    ownGeometry++;
                    moving++;
                    continue;
                }
                boolean carries = false;
                for (int i = 0; i < model.bones.length; i++) {
                    if (i != chain.boneIndex() && isUnder(model.bones, i, chain.boneIndex())
                            && partsOf(mesh, model, i).length > 0) {
                        carries = true;
                        break;
                    }
                }
                if (carries) {
                    moving++;
                } else if (inert.length() < 120) {
                    inert.append(inert.length() == 0 ? "" : ", ").append(chain.boneName());
                }
            }
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [physics] {} of {} chain bone(s) move geometry on model '{}' ({} carry it themselves){}",
                    moving, state.chains.length, model.modelId, ownGeometry,
                    inert.length() == 0 ? "" : "; inert: " + inert);
        }

        double now = System.nanoTime() / 1.0E9D;
        float dt = state.lastStepSeconds < 0.0 ? 0.0F : (float) (now - state.lastStepSeconds);
        state.lastStepSeconds = now;
        YsmPhysicsTuning tuning = YsmPhysicsTuning.current();

        for (int c = 0; c < state.chains.length; c++) {
            YsmPhysicsChains.Chain chain = state.chains[c];
            int joint = model.bones[chain.boneIndex()].joint;
            if (joint < 0 || joint >= poses.length || poses[joint] == null) {
                continue;
            }
            OpenMatrix4f pose = poses[joint];

            state.pivots[c].set(pose.m30, pose.m31, pose.m32);
            // The bind hang offset carried through the pose: the same point of the model
            // the bind pose put the piece's tip on, moved by however the bone has moved.
            float ox = state.bindOffsets[c].x;
            float oy = state.bindOffsets[c].y;
            float oz = state.bindOffsets[c].z;
            state.restTips[c].set(
                    pose.m00 * ox + pose.m10 * oy + pose.m20 * oz + pose.m30,
                    pose.m01 * ox + pose.m11 * oy + pose.m21 * oz + pose.m31,
                    pose.m02 * ox + pose.m12 * oy + pose.m22 * oz + pose.m32);

            YsmPhysicsSimulator.INSTANCE.update(state.sims[c], state.pivots[c], state.restTips[c],
                    chain, dt, tuning, scratch);
            int[] parts = state.parts[c];
            if (parts == null) {
                parts = partsOf(mesh, model, chain.boneIndex());
                state.parts[c] = parts;
            }
            if (isNeutral(scratch)) {
                // The mesh's transforms were cleared for this draw, so a chain that has
                // settled needs nothing written. Leaving the previous frame's rotation in
                // place instead would hold the piece permanently swung.
                continue;
            }
            YsmPhysicsSimulator.toLocal(local, scratch, state.bindRots[c]);
            // The part transform is a local offset applied inside the bone's own frame, so
            // it is the rotation alone - no translation, no scale.
            state.deltaScratch[c].identity().rotate(local);
            importInto(state.deltas[c], state.deltaScratch[c]);
            for (int ordinal : parts) {
                mesh.setRuntimeTransformAt(ordinal, state.deltas[c]);
            }
        }
    }

    // Scratch, so a per-frame update allocates nothing.
    private static final Quaternionf scratch = new Quaternionf();
    private static final Quaternionf local = new Quaternionf();

    private static boolean isNeutral(Quaternionf q) {
        return Math.abs(q.w() - 1.0F) < 1.0E-4F
                && Math.abs(q.x()) < 1.0E-4F && Math.abs(q.y()) < 1.0E-4F && Math.abs(q.z()) < 1.0E-4F;
    }

    /**
     * Copy a JOML matrix into Epic Fight's, without allocating.
     *
     * <p>Both address their fields column-first ({@code mRC} is column c, row r, and the
     * translation lives in m30/m31/m32), so this is a field copy rather than a transpose.
     */
    private static void importInto(OpenMatrix4f out, org.joml.Matrix4f m) {
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

    /**
     * The bone's average hang offset in its own bind frame: the mean of the bind-space
     * offsets from the bone to every bone under it, turned back into the bone's frame.
     *
     * <p>Bind-local rather than model-space because that is the frame the live pose
     * carries: {@code pose x offset} then lands on the same point of the model that the
     * bind pose put the piece's tip on, without the pose's scale having to be undone.
     */
    private static Vector3f bindHangOffset(YSMRuntimeModel model, YsmPhysicsChains.Chain chain) {
        YSMRuntimeModel.BoneRt[] bones = model.bones;
        YSMRuntimeModel.BoneRt root = bones[chain.boneIndex()];
        float sx = 0.0F, sy = 0.0F, sz = 0.0F;
        int found = 0;
        for (int i = 0; i < bones.length; i++) {
            if (i == chain.boneIndex() || bones[i] == null || !isUnder(bones, i, chain.boneIndex())) {
                continue;
            }
            sx += bones[i].bindWorld.m30() - root.bindWorld.m30();
            sy += bones[i].bindWorld.m31() - root.bindWorld.m31();
            sz += bones[i].bindWorld.m32() - root.bindWorld.m32();
            found++;
        }
        if (found == 0) {
            return new Vector3f();
        }
        Vector3f worldOffset = new Vector3f(sx / found, sy / found, sz / found);
        if (worldOffset.lengthSquared() < 1.0E-8F) {
            return worldOffset;
        }
        Quaternionf rotation = new Quaternionf();
        YsmPhysicsSimulator.rotationOf(root.bindWorld, rotation);
        rotation.conjugate();
        return rotation.transform(worldOffset);
    }

    /** Whether {@code bone} hangs anywhere under {@code ancestor}. */
    private static boolean isUnder(YSMRuntimeModel.BoneRt[] bones, int bone, int ancestor) {
        int guard = 0;
        for (int i = bones[bone].parent; i >= 0 && guard++ <= bones.length; i = bones[i].parent) {
            if (i == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * The mesh parts carrying a bone's geometry, as ordinals.
     *
     * <p>Ordinals rather than names: this runs per drawn frame, and resolving a name
     * through a hash map every time is the kind of per-frame cost this feature has to be
     * cheap enough for a phone to pay. Computed on first use per chain and kept.
     */
    private static int[] partsOf(YSMMesh mesh, YSMRuntimeModel model, int boneIndex) {
        String prefix = EFMeshJsonWriter.BONE_PART_PREFIX;
        List<Integer> ordinals = new ArrayList<>();
        int ordinal = 0;
        for (Map.Entry<String, ?> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (partName.startsWith(prefix)) {
                Integer idx = model.boneIndex.get(partName.substring(prefix.length()));
                if (idx != null && idx == boneIndex) {
                    ordinals.add(ordinal);
                }
            }
            ordinal++;
        }
        int[] result = new int[ordinals.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = ordinals.get(i);
        }
        return result;
    }

    private static String names(State state) {
        StringBuilder names = new StringBuilder();
        for (YsmPhysicsChains.Chain c : state.chains) {
            names.append(names.length() == 0 ? "" : ", ").append(c.boneName());
        }
        return names.toString();
    }
}
