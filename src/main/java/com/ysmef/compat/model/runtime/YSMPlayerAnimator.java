package com.ysmef.compat.model.runtime;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.ysm.script.Molang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player evaluator for a converted YSM model's molang scripts, replicating
 * YSM's model-changing behavior on top of Epic Fight's skinning.
 *
 * Each frame, in the same spirit as YSM's animation systems:
 * - the molang query context is refreshed from the player (health, speeds,
 *   held items, head rotation, ...)
 * - pre_parallelN/parallelN looped animations are evaluated (variable-driven bone
 *   scales decide which model variant is visible; timeline molang drives the
 *   spring physics used for tails/hair/clothes)
 * - the locomotion state animation (idle/walk/run/fly/swim/...) and hold/use
 *   condition overlays are evaluated for secondary (non-EF-mapped) bones
 *
 * Results are applied per Epic Fight part ("y/<bone>"): parts whose effective
 * scale collapses are hidden; other parts receive a bind-space delta transform
 * (conjugated local animation delta) that composes with Epic Fight's joint pose
 * (final = Pose_ef x Delta_ysm x bindVertex), so YSM bone animation rides on top
 * of Epic Fight's combat animation instead of fighting it.
 */
public final class YSMPlayerAnimator implements Molang.Env {

    private static final double HIDE_SCALE_EPSILON = 0.01;
    private static final float MIN_SPEED = 0.05F;

    private final YSMRuntimeModel model;

    // molang state (id-keyed slots, see Molang#idOf: queries and variables are
    // interned into stable integer ids at compile time, so the hot evaluation
    // path does no String hashing)
    private double[] varsById = new double[32];
    private boolean[] varSetById = new boolean[32];
    private double[] queriesById = new double[256];
    private static final int Q_ANIM_TIME = Molang.idOf("query.anim_time");
    private ItemStack mainHand = ItemStack.EMPTY;
    private ItemStack offHand = ItemStack.EMPTY;
    private volatile String currentState = "";
    private float animTimeCurrent;

    // frame tracking
    private final Map<String, Double> animStart = new HashMap<>();
    private final Map<String, Float> animLastT = new HashMap<>();
    private double lastPosX, lastPosY, lastPosZ;
    private boolean hasLastPos;
    private final double[] posDelta = new double[3];
    private float lastHeadYawDeg;
    private double lastYawSampleTime = -1;
    private double yawSpeedDeg;

    // evaluation scratch (indexed by bone list position)
    private final float[][] animPos;    // converted, blocks
    private final float[][] animRot;    // converted, radians
    private final float[][] animScale;
    private final boolean[] hasPos, hasRot, hasScale;
    private final Matrix4f[] localAnim;
    private final Matrix4f[] deltaModel;
    /** Double-buffered composed chain deltas: the worker evaluates into one buffer while the render thread reads the other. */
    private final Matrix4f[][] chainDeltaBuf;
    /** Double-buffered effective visibility scales (same layout as chainDeltaBuf). */
    private final float[][] effMinScaleBuf;
    /** Buffer index currently written by evaluate(); the read side uses {@link #readyBuffer}. */
    private int writeBuf;
    /** Buffer index of the last completed evaluation, published via the volatile write. */
    private volatile int readyBuffer = -1;
    private final boolean[] composed;
    private final OpenMatrix4f[] partMats;
    private final Matrix4f scratchA = new Matrix4f();
    private final float[] evalL0 = new float[3];
    private final float[] evalR0 = new float[3];
    private final float[] evalP0 = new float[3];
    private final float[] evalP3 = new float[3];

    /** Incremental keyframe lookup cursors, one per compiled channel (amortized O(1)). */
    private final int[] channelCursor;

    /** Per-bone mesh parts captured once per mesh instance (see ensurePartMap). */
    private YSMMesh partMapMesh;
    private List<Map.Entry<String, MeshPart>> partEntries;
    private int[] partBoneIdx;

    // LOD-style evaluation rate limiting (OpenYSM AnimatableEntity#getRefreshRate):
    // entities beyond 40 blocks evaluate at 30 Hz, beyond 64 blocks at 10 Hz,
    // everything else (and the local player) every frame. Skipped frames replay
    // the last evaluated bone state into the mesh instead of re-evaluating molang.
    private static final double LOD_DIST_SQR_NEAR = 40.0 * 40.0;
    private static final double LOD_DIST_SQR_FAR = 64.0 * 64.0;
    private volatile boolean evaluatedOnce = false;

    private final List<YSMRuntimeModel.CompiledAnim> activeAnims = new ArrayList<>();

    /**
     * ModernYSM-style async script evaluation: evaluations for entities other
     * than the local player run on this single-threaded daemon pool; the render
     * thread consumes the last completed result (double-buffered, see
     * chainDeltaBuf/effMinScaleBuf). The first evaluation of each animator stays
     * synchronous so the mesh never starts in an un-evaluated state.
     *
     * Minecraft / gameRenderer / camera state is never read on this pool: those
     * values are captured on the render thread when the evaluation is submitted
     * (see EvalInputs). Only entity state (positions, held items, pose, ...) is
     * read by the worker - at worst one tick stale, which matches ModernYSM's
     * own async evaluator semantics.
     */
    private static final java.util.concurrent.ExecutorService SCRIPT_POOL = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ysm-ef-script");
        thread.setDaemon(true);
        return thread;
    });

    /** Dedupe flag: only one evaluation may be in flight per animator. */
    private final java.util.concurrent.atomic.AtomicBoolean evalPending = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Global switch: disabled permanently after the first off-thread failure. */
    private static volatile boolean ASYNC_EVAL_FAILED = false;

    public YSMPlayerAnimator(YSMRuntimeModel model) {
        this.model = model;
        int n = model.bones.length;
        animPos = new float[n][3];
        animRot = new float[n][3];
        animScale = new float[n][3];
        hasPos = new boolean[n];
        hasRot = new boolean[n];
        hasScale = new boolean[n];
        localAnim = new Matrix4f[n];
        deltaModel = new Matrix4f[n];
        chainDeltaBuf = new Matrix4f[2][n];
        effMinScaleBuf = new float[2][n];
        composed = new boolean[n];
        partMats = new OpenMatrix4f[n];
        for (int i = 0; i < n; i++) {
            localAnim[i] = new Matrix4f();
            deltaModel[i] = new Matrix4f();
            chainDeltaBuf[0][i] = new Matrix4f();
            chainDeltaBuf[1][i] = new Matrix4f();
            partMats[i] = new OpenMatrix4f();
        }
        channelCursor = new int[model.channelCount];
        java.util.Arrays.fill(channelCursor, -1);
    }

    /**
     * Evaluate the scripts for this entity this frame and apply per-part hidden
     * flags and transforms to the mesh about to be drawn.
     *
     * Evaluation is rate-limited by distance (see LOD_DIST_SQR_*): the expensive
     * part (query refresh, molang evaluation, keyframe lookup, matrix composition)
     * runs at most every N ticks for distant entities; every frame the stored bone
     * state from the last full evaluation is replayed into the mesh, so a
     * rate-limited entity keeps rendering its last known pose instead of
     * recomputing it.
     *
     * For entities other than the local player the evaluation itself runs on a
     * background thread (ModernYSM-style, config scriptAsyncEval): the render
     * thread replays the last completed result, so the per-frame cost on the
     * render thread is only the mesh push (O(parts), no molang).
     */
    public void apply(YSMMesh mesh, LivingEntity entity, OpenMatrix4f[] poses, float partialTick) {
        long t0 = com.ysmef.compat.YsmDiag.isEnabled() ? System.nanoTime() : 0L;
        double now = (entity.tickCount + partialTick) / 20.0;
        if (shouldFullEval(entity)) {
            EvalInputs inputs = captureEvalInputs(entity);
            if (!evaluatedOnce || !useAsyncEval(entity)) {
                evaluate(entity, partialTick, now, inputs);
                evaluatedOnce = true;
            } else {
                submitAsyncEval(entity, partialTick, now, inputs);
            }
        }
        pushToMesh(mesh);
        com.ysmef.compat.YsmDiag.addNanos(com.ysmef.compat.YsmDiag.SLOT_SCRIPT_EVAL, System.nanoTime() - t0);
    }

    private boolean useAsyncEval(LivingEntity entity) {
        return YSMCompatConfig.ENABLE_SCRIPT_ASYNC_EVAL.get() && !ASYNC_EVAL_FAILED
                && Minecraft.getInstance().player != entity;
    }

    /**
     * Render-thread-only evaluation inputs, captured when the evaluation is
     * scheduled. The script pool must never read Minecraft / gameRenderer /
     * camera state: those are updated by the render loop every frame and an
     * off-thread read would race with it (the camera entity's position and the
     * frame time are plain non-volatile fields).
     */
    private record EvalInputs(double cameraDistance, float frameTime, boolean firstPerson) {}

    private static EvalInputs captureEvalInputs(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        double cameraDistance = -1.0;
        if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
            cameraDistance = mc.gameRenderer.getMainCamera().getPosition().distanceTo(entity.position());
        }
        return new EvalInputs(cameraDistance, mc.getFrameTime(),
                mc.options.getCameraType() == CameraType.FIRST_PERSON);
    }

    /**
     * Submit one evaluation to the script pool (deduped: at most one in flight
     * per animator). Runs the same evaluate() as the synchronous path; the
     * double-buffered result state is published via the volatile readyBuffer.
     * The inputs were captured on the render thread, so the worker never
     * touches Minecraft/render-system state.
     */
    private void submitAsyncEval(LivingEntity entity, float partialTick, double now, EvalInputs inputs) {
        if (!evalPending.compareAndSet(false, true)) {
            return;
        }
        SCRIPT_POOL.execute(() -> {
            try {
                evaluate(entity, partialTick, now, inputs);
                evaluatedOnce = true;
            } catch (Throwable t) {
                // never leave the render path broken: fall back to synchronous evaluation
                ASYNC_EVAL_FAILED = true;
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: async script evaluation failed for model '{}', falling back to synchronous evaluation", model.modelId, t);
            } finally {
                evalPending.set(false);
            }
        });
    }

    /**
     * Distance-based evaluation gate: the local player (and everyone near the
     * camera) evaluates every frame; entities beyond 40/64 blocks drop to
     * 30/10 Hz. The first evaluation is always full so the mesh starts with
     * correct state.
     */
    private boolean shouldFullEval(LivingEntity entity) {
        if (!evaluatedOnce) {
            return true;
        }
        Player local = Minecraft.getInstance().player;
        if (local == null || entity == local) {
            return true;
        }
        double distSqr = entity.distanceToSqr(local);
        int tick = entity.tickCount;
        if (distSqr > LOD_DIST_SQR_FAR) {
            return tick % 6 == 0;
        }
        if (distSqr > LOD_DIST_SQR_NEAR) {
            return tick % 2 == 0;
        }
        return true;
    }

    /**
     * Full evaluation: refresh the query context, resolve the state machine and
     * evaluate all active animations into the scratch arrays, then compose the
     * per-bone chain deltas and effective visibility scales into the currently
     * unused buffer and publish it (volatile readyBuffer). Entity reads
     * (held items, position delta, ...) happen here, so the whole evaluation is
     * self-contained and can run on the script pool; render-thread-only state
     * (camera distance, frame time, perspective) arrives pre-captured via
     * {@link EvalInputs}.
     */
    private void evaluate(LivingEntity entity, float partialTick, double now, EvalInputs inputs) {
        writeBuf = readyBuffer < 0 ? 0 : 1 - readyBuffer;
        mainHand = entity.getItemInHand(InteractionHand.MAIN_HAND);
        offHand = entity.getItemInHand(InteractionHand.OFF_HAND);
        updatePosDelta(entity);
        String state = resolveState(entity, inputs.frameTime());
        if (!state.equals(currentState)) {
            currentState = state;
            animStart.put(state, now);
            animLastT.remove(state);
        }
        fillQueries(entity, partialTick, now, inputs);

        activeAnims.clear();
        activeAnims.addAll(model.parallels);
        YSMRuntimeModel.CompiledAnim stateAnim = model.states.get(state);
        if (stateAnim != null) {
            activeAnims.add(stateAnim);
        }
        YSMRuntimeModel.CompiledAnim overlay = resolveOverlay(entity, state);
        if (overlay != null) {
            activeAnims.add(overlay);
        }

        resetScratch();
        for (YSMRuntimeModel.CompiledAnim anim : activeAnims) {
            evalAnim(anim, now);
        }

        compose();
        readyBuffer = writeBuf;
    }

    // ------------------------------------------------------------------
    // Animation evaluation
    // ------------------------------------------------------------------

    private void resetScratch() {
        int n = model.bones.length;
        for (int i = 0; i < n; i++) {
            hasPos[i] = false;
            hasRot[i] = false;
            hasScale[i] = false;
            animPos[i][0] = 0;
            animPos[i][1] = 0;
            animPos[i][2] = 0;
            animRot[i][0] = 0;
            animRot[i][1] = 0;
            animRot[i][2] = 0;
            animScale[i][0] = 1;
            animScale[i][1] = 1;
            animScale[i][2] = 1;
        }
    }

    private void evalAnim(YSMRuntimeModel.CompiledAnim anim, double now) {
        double start = animStart.computeIfAbsent(anim.name, k -> now);
        double t = Math.max(0, now - start);
        double tEval = t;
        if (anim.loop == ScriptAnimLoop.REPEAT && anim.length > 1e-4) {
            tEval = t % anim.length;
        } else if (anim.loop == ScriptAnimLoop.HOLD && anim.length > 1e-4) {
            tEval = Math.min(t, anim.length);
        }

        // timelines fire when the (looped) animation time passes them
        animTimeCurrent = (float) tEval;
        float lastT = animLastT.getOrDefault(anim.name, -1f);
        fireTimelines(anim, lastT, (float) tEval);
        animLastT.put(anim.name, (float) tEval);

        for (Map.Entry<Integer, YSMRuntimeModel.CompiledChannels> entry : anim.bones.entrySet()) {
            int boneIdx = entry.getKey();
            YSMRuntimeModel.CompiledChannels channels = entry.getValue();
            if (channels.rot != null) {
                evalChannel(channels.rot, (float) tEval, animRot[boneIdx]);
                // bedrock degrees -> radians, x/y negated (YSM convention)
                animRot[boneIdx][0] = (float) Math.toRadians(-animRot[boneIdx][0]);
                animRot[boneIdx][1] = (float) Math.toRadians(-animRot[boneIdx][1]);
                animRot[boneIdx][2] = (float) Math.toRadians(animRot[boneIdx][2]);
                hasRot[boneIdx] = true;
            }
            if (channels.pos != null) {
                evalChannel(channels.pos, (float) tEval, animPos[boneIdx]);
                // bedrock pixels -> blocks, x negated (YSM convention)
                animPos[boneIdx][0] = -animPos[boneIdx][0] / 16.0f;
                animPos[boneIdx][1] = animPos[boneIdx][1] / 16.0f;
                animPos[boneIdx][2] = animPos[boneIdx][2] / 16.0f;
                hasPos[boneIdx] = true;
            }
            if (channels.scale != null) {
                evalChannel(channels.scale, (float) tEval, animScale[boneIdx]);
                hasScale[boneIdx] = true;
            }
        }
    }

    private void fireTimelines(YSMRuntimeModel.CompiledAnim anim, float lastT, float nowT) {
        if (anim.timelines.length == 0) {
            return;
        }
        if (lastT < 0) {
            // first evaluation: fire entries at t=0
            for (YSMRuntimeModel.CompiledTimeline entry : anim.timelines) {
                if (entry.time <= nowT) {
                    runTimeline(entry);
                }
            }
            return;
        }
        if (nowT >= lastT) {
            for (YSMRuntimeModel.CompiledTimeline entry : anim.timelines) {
                if (entry.time > lastT && entry.time <= nowT) {
                    runTimeline(entry);
                }
            }
        } else {
            // looped past the end: fire the tail entries, then the wrapped ones
            for (YSMRuntimeModel.CompiledTimeline entry : anim.timelines) {
                if (entry.time > lastT) {
                    runTimeline(entry);
                }
            }
            for (YSMRuntimeModel.CompiledTimeline entry : anim.timelines) {
                if (entry.time <= nowT) {
                    runTimeline(entry);
                }
            }
        }
    }

    private void runTimeline(YSMRuntimeModel.CompiledTimeline entry) {
        for (Molang.Expr expr : entry.code) {
            expr.eval(this);
        }
    }

    /**
     * Evaluate a keyframe channel at time t into out. The search window is
     * carried between frames per channel (OpenYSM InterpolationLookup): while
     * the animation time advances monotonically the lookup starts from the
     * previous window, making the scan amortized O(1); a backwards jump (loop
     * wrap / animation restart) resets the cursor.
     */
    private void evalChannel(YSMRuntimeModel.CompiledChannel channel, float t, float[] out) {
        float[] times = channel.times;
        int n = times.length;
        int cursor = channelCursor[channel.channelId];
        int right = (cursor < 0 || t < times[Math.min(cursor, n - 1)]) ? 1 : cursor;
        while (right < n && times[right] <= t) {
            right++;
        }
        channelCursor[channel.channelId] = right;
        if (right >= n) {
            evalValue(channel.post[n - 1], out);
            return;
        }
        if (right == 0) {
            evalValue(channel.post[0], out);
            return;
        }
        int left = right - 1;
        float t0 = times[left];
        float t1 = times[right];
        int lerp = channel.lerps[right];
        if (lerp == ScriptAnimKeyLerp.STEP || t1 <= t0) {
            evalValue(channel.post[left], out);
            return;
        }
        // clamp into the segment: right after a loop wrap t can briefly fall
        // before the first keyframe (the cursor reset starts at the second
        // key), which would otherwise extrapolate backwards below the first
        // keyframe and produce a visible pop on looping animations
        float alpha = Math.min(1.0f, Math.max(0.0f, (t - t0) / (t1 - t0)));
        evalValue(channel.post[left], evalL0);
        evalValue(channel.pre[right] != null ? channel.pre[right] : channel.post[right], evalR0);
        if (lerp == ScriptAnimKeyLerp.CATMULLROM && n >= 2) {
            evalValue(channel.post[Math.max(0, left - 1)], evalP0);
            evalValue(channel.post[Math.min(n - 1, right + 1)], evalP3);
            for (int i = 0; i < 3; i++) {
                out[i] = catmullRom(evalP0[i], evalL0[i], evalR0[i], evalP3[i], alpha);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                out[i] = evalL0[i] + (evalR0[i] - evalL0[i]) * alpha;
            }
        }
    }

    private void evalValue(Molang.Expr[] axes, float[] out) {
        out[0] = (float) axes[0].eval(this);
        out[1] = (float) axes[1].eval(this);
        out[2] = (float) axes[2].eval(this);
    }

    private static float catmullRom(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    // ------------------------------------------------------------------
    // Composition & application
    // ------------------------------------------------------------------

    /**
     * Compose the per-bone chain deltas and effective visibility scales into the
     * current write buffer. Runs only on full-evaluation frames.
     */
    private void compose() {
        java.util.Arrays.fill(composed, false);
        int n = model.bones.length;
        for (int i = 0; i < n; i++) {
            composeBone(i);
        }
    }

    /**
     * Push the stored per-bone state (hidden flags + chain deltas) into the mesh
     * parts. Runs every frame - also on rate-limited frames, replaying the state
     * from the last full evaluation (read side of the double buffer) so the
     * shared mesh always reflects the entity currently being drawn.
     */
    private void pushToMesh(YSMMesh mesh) {
        ensurePartMap(mesh);
        int ready = readyBuffer;
        if (ready < 0) {
            return;
        }
        float[] eff = effMinScaleBuf[ready];
        Matrix4f[] chain = chainDeltaBuf[ready];
        for (int i = 0; i < partEntries.size(); i++) {
            int boneIdx = partBoneIdx[i];
            MeshPart part = partEntries.get(i).getValue();
            boolean hidden = eff[boneIdx] < HIDE_SCALE_EPSILON;
            part.setHidden(hidden);
            if (!hidden && !isIdentity(chain[boneIdx])) {
                importInto(partMats[boneIdx], chain[boneIdx]);
                mesh.setRuntimeTransform(partEntries.get(i).getKey(), partMats[boneIdx]);
            }
        }
        logAnimatorDiagOnce(chain);
    }

    /** Once per animator: bound of the pushed chain deltas, to spot a runaway transform. */
    private boolean animatorDiagLogged = false;

    private void logAnimatorDiagOnce(Matrix4f[] chain) {
        if (animatorDiagLogged || !com.ysmef.compat.YsmDiag.isEnabled()) {
            return;
        }
        animatorDiagLogged = true;
        float maxT = 0.0f;
        float maxScale = 0.0f;
        int nonIdentity = 0;
        for (Matrix4f m : chain) {
            if (m == null) {
                continue;
            }
            if (!isIdentity(m)) {
                nonIdentity++;
            }
            maxT = Math.max(maxT, Math.max(Math.abs(m.m30()), Math.max(Math.abs(m.m31()), Math.abs(m.m32()))));
            maxScale = Math.max(maxScale, Math.max(Math.abs(m.m00()), Math.max(Math.abs(m.m11()), Math.abs(m.m22()))));
        }
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] animator push: model={} bones={} nonIdentityDeltas={} maxTranslation={} maxScale={}",
                model.modelId, chain.length, nonIdentity, maxT, maxScale);
    }

    /**
     * Capture the per-bone mesh parts (entry + bone index) once per mesh instance
     * (the mesh is shared and re-created only on regeneration, so the capture is
     * cached per mesh reference). Removes the per-frame substring + hash lookups
     * from the push loop.
     */
    private void ensurePartMap(YSMMesh mesh) {
        if (partEntries != null && partMapMesh == mesh) {
            return;
        }
        partMapMesh = mesh;
        String prefix = EFMeshJsonWriter.BONE_PART_PREFIX;
        List<Map.Entry<String, MeshPart>> entries = new ArrayList<>();
        List<Integer> boneIdxList = new ArrayList<>();
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (!partName.startsWith(prefix)) {
                continue;
            }
            Integer boneIdx = model.boneIndex.get(partName.substring(prefix.length()));
            if (boneIdx != null) {
                entries.add(entry);
                boneIdxList.add(boneIdx);
            }
        }
        partEntries = entries;
        partBoneIdx = new int[boneIdxList.size()];
        for (int i = 0; i < boneIdxList.size(); i++) {
            partBoneIdx[i] = boneIdxList.get(i);
        }
    }

    /**
     * OpenMatrix4f.importFromMojangMatrix without allocation. Both JOML and
     * Epic Fight's OpenMatrix4f address fields column-first (mRC = column c,
     * row r; the translation is m30/m31/m32), so the conversion is a direct
     * field copy. JOML Matrix4f.get(FloatBuffer) writes column-major order and
     * OpenMatrix4f.load reads that same order.
     */
    private static void importInto(OpenMatrix4f om, Matrix4f m) {
        om.m00 = m.m00();
        om.m01 = m.m01();
        om.m02 = m.m02();
        om.m03 = m.m03();
        om.m10 = m.m10();
        om.m11 = m.m11();
        om.m12 = m.m12();
        om.m13 = m.m13();
        om.m20 = m.m20();
        om.m21 = m.m21();
        om.m22 = m.m22();
        om.m23 = m.m23();
        om.m30 = m.m30();
        om.m31 = m.m31();
        om.m32 = m.m32();
        om.m33 = m.m33();
    }

    private static boolean isIdentity(Matrix4f m) {
        return Math.abs(m.m00() - 1) < 1e-5f && Math.abs(m.m11() - 1) < 1e-5f
                && Math.abs(m.m22() - 1) < 1e-5f && Math.abs(m.m33() - 1) < 1e-5f
                && Math.abs(m.m01()) < 1e-5f && Math.abs(m.m02()) < 1e-5f && Math.abs(m.m03()) < 1e-5f
                && Math.abs(m.m10()) < 1e-5f && Math.abs(m.m12()) < 1e-5f && Math.abs(m.m13()) < 1e-5f
                && Math.abs(m.m20()) < 1e-5f && Math.abs(m.m21()) < 1e-5f && Math.abs(m.m23()) < 1e-5f
                && Math.abs(m.m30()) < 1e-5f && Math.abs(m.m31()) < 1e-5f && Math.abs(m.m32()) < 1e-5f;
    }

    /**
     * Computes the bone's animated local transform, its model-space animation
     * delta (conjugated into bind space) and the composed delta of the whole
     * chain up to the nearest Epic-Fight-driven ancestor, plus the effective
     * visibility scale along the chain. Allocation-free: all matrices are
     * persistent scratch (bindWorldInv is precomputed at model load).
     */
    private void composeBone(int i) {
        if (composed[i]) {
            return;
        }
        YSMRuntimeModel.BoneRt bone = model.bones[i];
        if (bone.parent >= 0) {
            composeBone(bone.parent);
        }
        Matrix4f[] chain = chainDeltaBuf[writeBuf];
        float[] eff = effMinScaleBuf[writeBuf];

        float sx = hasScale[i] ? animScale[i][0] : 1f;
        float sy = hasScale[i] ? animScale[i][1] : 1f;
        float sz = hasScale[i] ? animScale[i][2] : 1f;

        if (bone.mapped) {
            // Epic Fight owns the gross pose of mapped bones; only scale channels
            // (variant visibility / player size scripts) are honored.
            localAnim[i].translation(bone.px, bone.py, bone.pz)
                    .rotateZ(bone.rz).rotateY(bone.ry).rotateX(bone.rx)
                    .scale(sx, sy, sz)
                    .translate(-bone.px, -bone.py, -bone.pz);
        } else {
            float ox = bone.px + (hasPos[i] ? animPos[i][0] : 0);
            float oy = bone.py + (hasPos[i] ? animPos[i][1] : 0);
            float oz = bone.pz + (hasPos[i] ? animPos[i][2] : 0);
            float rx = hasRot[i] ? animRot[i][0] : bone.rx;
            float ry = hasRot[i] ? animRot[i][1] : bone.ry;
            float rz = hasRot[i] ? animRot[i][2] : bone.rz;
            localAnim[i].translation(ox, oy, oz)
                    .rotateZ(rz).rotateY(ry).rotateX(rx)
                    .scale(sx, sy, sz)
                    .translate(-bone.px, -bone.py, -bone.pz);
        }

        // delta of this bone's animated local vs its bind local, conjugated into
        // model bind space so it can pre-multiply the Epic Fight joint pose
        scratchA.set(localAnim[i]).mul(bone.bindLocalInv);
        if (isIdentity(scratchA)) {
            deltaModel[i].identity();
        } else {
            deltaModel[i].set(bone.bindWorld).mul(scratchA).mul(bone.bindWorldInv);
        }

        float parentMinScale = 1f;
        if (bone.parent >= 0) {
            parentMinScale = eff[bone.parent];
            chain[i].set(chain[bone.parent]).mul(deltaModel[i]);
        } else {
            chain[i].set(deltaModel[i]);
        }
        eff[i] = parentMinScale * Math.min(sx, Math.min(sy, sz));
        composed[i] = true;
    }

    // ------------------------------------------------------------------
    // State machine (mirrors YSM's AnimationRegister predicates)
    // ------------------------------------------------------------------

    private String resolveState(LivingEntity entity, float frameTime) {
        if (entity.isDeadOrDying()) {
            return "death";
        }
        if (entity.getPose() == Pose.SLEEPING) {
            return "sleep";
        }
        if (entity.isSwimming()) {
            return "swim";
        }
        if (entity.getPose() == Pose.SWIMMING && isMoving(entity, frameTime)) {
            return "climb";
        }
        if (entity.getPose() == Pose.SWIMMING) {
            return "climbing";
        }
        if (entity.isPassenger()) {
            // resolve the vehicle condition anim for the CURRENT vehicle only:
            // a previous ride's anim must never survive a vehicle change
            ResourceLocation vehicleId = ForgeRegistries.ENTITY_TYPES.getKey(entity.getVehicle().getType());
            String vehicleAnim = vehicleId != null ? "vehicle$" + vehicleId : null;
            currentVehicleAnim = vehicleAnim != null && model.conditionAnims.containsKey(vehicleAnim)
                    ? vehicleAnim : null;
            if (entity.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat) {
                return "boat";
            }
            return model.states.containsKey("ride") ? "ride" : "sit";
        }
        currentVehicleAnim = null;
        if (entity instanceof Player player && player.getAbilities().flying) {
            return "fly";
        }
        if (entity.getPose() == Pose.FALL_FLYING && entity.isFallFlying()) {
            return "elytra_fly";
        }
        if (entity.isInWater()) {
            return "swim_stand";
        }
        if (entity.onGround() && entity.getPose() == Pose.CROUCHING && isMoving(entity, frameTime)) {
            return "sneak";
        }
        if (entity.onGround() && entity.getPose() == Pose.CROUCHING) {
            return "sneaking";
        }
        if (entity.onGround() && entity.isSprinting()) {
            return "run";
        }
        if (entity.onGround() && isMoving(entity, frameTime)) {
            return "walk";
        }
        if (model.states.containsKey("idle")) {
            return "idle";
        }
        return "new_idle_empty";
    }

    private String currentVehicleAnim = null;

    private boolean isMoving(LivingEntity entity, float frameTime) {
        return Math.abs(entity.walkAnimation.speed(frameTime)) > MIN_SPEED;
    }

    /**
     * Hold/use condition overlay: played alongside the locomotion state by YSM
     * (arm/overlay layer), affecting secondary bones (magic circles, props).
     */
    private YSMRuntimeModel.CompiledAnim resolveOverlay(LivingEntity entity, String state) {
        if (entity.isUsingItem()) {
            InteractionHand hand = entity.getUsedItemHand();
            String prefix = hand == InteractionHand.MAIN_HAND ? "use_mainhand:" : "use_offhand:";
            YSMRuntimeModel.CompiledAnim anim = findConditionAnim(prefix, entity.getUseItem());
            if (anim != null) {
                return anim;
            }
            String generic = hand == InteractionHand.MAIN_HAND ? "use_mainhand" : "use_offhand";
            return model.states.get(generic);
        }
        if (!entity.swinging) {
            if (!mainHand.isEmpty()) {
                YSMRuntimeModel.CompiledAnim anim = findConditionAnim("hold_mainhand:", mainHand);
                if (anim != null) {
                    return anim;
                }
            } else {
                YSMRuntimeModel.CompiledAnim anim = model.conditionAnims.get("hold_mainhand:empty");
                if (anim != null) {
                    return anim;
                }
            }
        }
        if (currentVehicleAnim != null && entity.isPassenger()) {
            return model.conditionAnims.get(currentVehicleAnim);
        }
        return null;
    }

    private YSMRuntimeModel.CompiledAnim findConditionAnim(String prefix, ItemStack stack) {
        YSMRuntimeModel.CompiledAnim best = null;
        int bestLen = -1;
        for (Map.Entry<String, YSMRuntimeModel.CompiledAnim> entry : model.conditionAnims.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String tag = name.substring(prefix.length());
            if (tag.equals("empty")) {
                if (stack.isEmpty() && tag.length() > bestLen) {
                    best = entry.getValue();
                    bestLen = tag.length();
                }
                continue;
            }
            if (itemMatches(stack, tag) && tag.length() > bestLen) {
                best = entry.getValue();
                bestLen = tag.length();
            }
        }
        return best;
    }

    /** YSM condition-tag matching: "$modid:item" exact, ":tag"/"tag" substring of the item id. */
    private static boolean itemMatches(ItemStack stack, String tag) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        if (tag.startsWith("$")) {
            return id.toString().equals(tag.substring(1));
        }
        String t = tag.startsWith(":") ? tag.substring(1) : tag;
        return id.getPath().contains(t) || id.toString().equals(t);
    }

    // ------------------------------------------------------------------
    // Molang Env
    // ------------------------------------------------------------------

    @Override
    public double getVar(String path) {
        return getVarById(Molang.idOf(path));
    }

    @Override
    public boolean hasVar(String path) {
        return hasVarById(Molang.idOf(path));
    }

    @Override
    public void setVar(String path, double value) {
        setVarById(Molang.idOf(path), value);
    }

    @Override
    public double getQuery(String path) {
        return getQueryById(Molang.queryIdOf(path));
    }

    @Override
    public double getVarById(int id) {
        return id < varsById.length && varSetById[id] ? varsById[id] : 0.0;
    }

    @Override
    public boolean hasVarById(int id) {
        return id < varsById.length && varSetById[id];
    }

    @Override
    public void setVarById(int id, double value) {
        if (id >= varsById.length) {
            int newLen = Math.max(id + 1, varsById.length * 2);
            varsById = java.util.Arrays.copyOf(varsById, newLen);
            varSetById = java.util.Arrays.copyOf(varSetById, newLen);
        }
        varsById[id] = value;
        varSetById[id] = true;
    }

    @Override
    public double getQueryById(int id) {
        if (id == Q_ANIM_TIME) {
            return animTimeCurrent;
        }
        return id < queriesById.length ? queriesById[id] : 0.0;
    }

    @Override
    public double callFunction(String name, double[] args) {
        switch (name) {
            case "math.sin":
                return Math.sin(Math.toRadians(args[0]));
            case "math.cos":
                return Math.cos(Math.toRadians(args[0]));
            case "math.tan":
                return Math.tan(Math.toRadians(args[0]));
            case "math.asin":
                return Math.toDegrees(Math.asin(args[0]));
            case "math.acos":
                return Math.toDegrees(Math.acos(args[0]));
            case "math.atan":
                return Math.toDegrees(Math.atan(args[0]));
            case "math.atan2":
                return Math.toDegrees(Math.atan2(args[0], args[1]));
            case "math.abs":
                return Math.abs(args[0]);
            case "math.floor":
                return Math.floor(args[0]);
            case "math.ceil":
                return Math.ceil(args[0]);
            case "math.round":
                return Math.round(args[0]);
            case "math.trunc":
                return (long) (args[0] >= 0 ? Math.floor(args[0]) : Math.ceil(args[0]));
            case "math.sqrt":
                return args[0] < 0 ? 0 : Math.sqrt(args[0]);
            case "math.pow":
                return Math.pow(args[0], args[1]);
            case "math.exp":
                return Math.exp(args[0]);
            case "math.ln":
                return args[0] <= 0 ? 0 : Math.log(args[0]);
            case "math.log":
                return args[0] <= 0 ? 0 : Math.log(args[0]);
            case "math.lerp":
                return args[0] + (args[1] - args[0]) * args[2];
            case "math.min":
                return Math.min(args[0], args[1]);
            case "math.max":
                return Math.max(args[0], args[1]);
            case "math.clamp":
                return Math.max(args[1], Math.min(args[2], args[0]));
            case "math.mod":
                return args[1] == 0 ? 0 : args[0] % args[1];
            case "math.random":
                return args[0] + Math.random() * (args[1] - args[0]);
            case "math.pi":
                return Math.PI;
            case "math.sign":
                return Math.signum(args[0]);
            case "query.position_delta":
                int axis = (int) args[0];
                return axis >= 0 && axis < 3 ? posDelta[axis] : 0;
            default:
                return 0;
        }
    }

    @Override
    public double callStringFunction(String name, String[] args) {
        if (name.equals("ctrl.hold") && args.length >= 2 && args[0] != null && args[1] != null) {
            ItemStack stack = args[0].toLowerCase().startsWith("off") ? offHand : mainHand;
            return itemMatchesLoose(stack, args[1]) ? 1.0 : 0.0;
        }
        return 0;
    }

    private static boolean itemMatchesLoose(ItemStack stack, String pattern) {
        if (pattern.startsWith("$")) {
            if (stack.isEmpty()) {
                return false;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return id != null && id.toString().equals(pattern.substring(1));
        }
        if (stack.isEmpty()) {
            return pattern.equals(":empty") || pattern.equals("empty");
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String t = pattern.startsWith(":") ? pattern.substring(1) : pattern;
        return id.getPath().contains(t) || id.getNamespace().equals(t);
    }

    // ------------------------------------------------------------------
    // Query context
    // ------------------------------------------------------------------

    private void updatePosDelta(LivingEntity entity) {
        if (hasLastPos) {
            posDelta[0] = entity.getX() - lastPosX;
            posDelta[1] = entity.getY() - lastPosY;
            posDelta[2] = entity.getZ() - lastPosZ;
        } else {
            hasLastPos = true;
        }
        lastPosX = entity.getX();
        lastPosY = entity.getY();
        lastPosZ = entity.getZ();
    }

    /** Write one query slot by its interned id (grows the slot array on demand). */
    private void setQuery(String path, double value) {
        int id = Molang.queryIdOf(path);
        if (id >= queriesById.length) {
            queriesById = java.util.Arrays.copyOf(queriesById, Math.max(id + 1, queriesById.length * 2));
        }
        queriesById[id] = value;
    }

    private void fillQueries(LivingEntity entity, float partialTick, double now, EvalInputs inputs) {
        float headYaw = entity.yHeadRotO + (entity.yHeadRot - entity.yHeadRotO) * partialTick;
        float bodyYaw = entity.yBodyRotO + (entity.yBodyRot - entity.yBodyRotO) * partialTick;
        float netHeadYaw = net.minecraft.util.Mth.wrapDegrees(headYaw - bodyYaw);
        float headPitch = entity.getViewXRot(partialTick);

        if (lastYawSampleTime >= 0 && now > lastYawSampleTime) {
            yawSpeedDeg = (netHeadYaw - lastHeadYawDeg) / (now - lastYawSampleTime);
        }
        lastHeadYawDeg = netHeadYaw;
        lastYawSampleTime = now;

        double dx = entity.getX() - entity.xo;
        double dz = entity.getZ() - entity.zo;
        double groundSpeed = 20.0 * Math.sqrt(dx * dx + dz * dz);
        double verticalSpeed = 20.0 * (entity.getY() - entity.yo);

        boolean onGround = entity.onGround();
        boolean crouching = entity.getPose() == Pose.CROUCHING;

        setQuery("query.life_time", now);
        setQuery("query.health", (double) entity.getHealth());
        setQuery("query.max_health", (double) entity.getMaxHealth());
        setQuery("query.hurt_time", (double) entity.hurtTime);
        setQuery("query.vertical_speed", verticalSpeed);
        setQuery("query.ground_speed", groundSpeed);
        setQuery("query.yaw_speed", yawSpeedDeg);
        setQuery("query.is_sneaking", onGround && crouching ? 1.0 : 0.0);
        setQuery("query.is_swimming", entity.isSwimming() ? 1.0 : 0.0);
        setQuery("query.is_sprinting", entity.isSprinting() ? 1.0 : 0.0);
        setQuery("query.is_on_ground", onGround ? 1.0 : 0.0);
        setQuery("query.is_jumping", !isCreativeFlying(entity) && !entity.isPassenger() && !onGround && !entity.isInWater() ? 1.0 : 0.0);
        setQuery("query.is_riding", entity.isPassenger() ? 1.0 : 0.0);
        setQuery("query.is_sleeping", entity.isSleeping() ? 1.0 : 0.0);
        setQuery("query.is_in_water", entity.isInWater() ? 1.0 : 0.0);
        setQuery("query.is_in_water_or_rain", entity.isInWaterRainOrBubble() ? 1.0 : 0.0);
        setQuery("query.is_gliding", entity.isFallFlying() ? 1.0 : 0.0);
        setQuery("query.is_on_fire", entity.isOnFire() ? 1.0 : 0.0);
        setQuery("query.is_playing_dead", entity.isDeadOrDying() ? 1.0 : 0.0);
        setQuery("query.is_spectator", entity instanceof Player player && player.isSpectator() ? 1.0 : 0.0);
        setQuery("query.is_using_item", entity.isUsingItem() ? 1.0 : 0.0);
        setQuery("query.is_eating", entity.getUseItem().getUseAnimation() == net.minecraft.world.item.UseAnim.EAT ? 1.0 : 0.0);
        setQuery("query.is_first_person", inputs.firstPerson() ? 1.0 : 0.0);
        setQuery("query.item_in_use_duration", entity.getTicksUsingItem() / 20.0);
        setQuery("query.item_max_use_duration", entity.getUseItem().getUseDuration() / 20.0);
        setQuery("query.item_remaining_use_duration", entity.getUseItemRemainingTicks() / 20.0);
        setQuery("query.walk_distance", (double) entity.moveDist);
        setQuery("query.modified_distance_moved", (double) entity.walkDist);
        setQuery("query.body_x_rotation", (double) entity.getXRot());
        setQuery("query.body_y_rotation", (double) net.minecraft.util.Mth.wrapDegrees(entity.getYRot()));
        setQuery("query.head_x_rotation", (double) netHeadYaw);
        setQuery("query.head_y_rotation", (double) headPitch);
        setQuery("query.cardinal_facing_2d", (double) entity.getDirection().get3DDataValue());
        setQuery("query.time_of_day", (entity.level().getDayTime() % 24000L) / 24000.0);
        setQuery("query.time_stamp", (double) entity.level().getDayTime());
        setQuery("query.moon_phase", (double) entity.level().getMoonPhase());
        setQuery("query.player_level", (double) (entity instanceof Player player ? player.experienceLevel : 0));
        setQuery("query.has_rider", entity.isVehicle() ? 1.0 : 0.0);
        setQuery("query.actor_count", 0.0);
        if (inputs.cameraDistance() >= 0) {
            setQuery("query.distance_from_camera", inputs.cameraDistance());
        }

        setQuery("ysm.head_yaw", (double) netHeadYaw);
        setQuery("ysm.head_pitch", (double) headPitch);
        setQuery("ysm.has_mainhand", mainHand.isEmpty() ? 0.0 : 1.0);
        setQuery("ysm.has_offhand", offHand.isEmpty() ? 0.0 : 1.0);
        setQuery("ysm.has_helmet", entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty() ? 0.0 : 1.0);
        setQuery("ysm.has_chest_plate", entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty() ? 0.0 : 1.0);
        setQuery("ysm.has_leggings", entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).isEmpty() ? 0.0 : 1.0);
        setQuery("ysm.has_boots", entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).isEmpty() ? 0.0 : 1.0);
        setQuery("ysm.has_elytra", entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA) ? 1.0 : 0.0);
        setQuery("ysm.is_sleep", entity.getPose() == Pose.SLEEPING ? 1.0 : 0.0);
        setQuery("ysm.is_sneak", onGround && crouching ? 1.0 : 0.0);
        setQuery("ysm.is_passenger", entity.isPassenger() ? 1.0 : 0.0);
        setQuery("ysm.is_riptide", entity.isAutoSpinAttack() ? 1.0 : 0.0);
        setQuery("ysm.armor_value", (double) entity.getArmorValue());
        setQuery("ysm.hurt_time", (double) entity.hurtTime);
        setQuery("ysm.food_level", (double) (entity instanceof Player player ? player.getFoodData().getFoodLevel() : 20));

        setQuery("ctrl.idle", currentState.equals("idle") || currentState.equals("new_idle_empty") ? 1.0 : 0.0);
        setQuery("ctrl.run", currentState.equals("run") ? 1.0 : 0.0);
        setQuery("ctrl.walk", currentState.equals("walk") ? 1.0 : 0.0);
        setQuery("ctrl.playing_extra_animation", 0.0);
    }

    private static boolean isCreativeFlying(LivingEntity entity) {
        return entity instanceof Player player && player.getAbilities().flying;
    }

    // loop-mode constants mirrored from ScriptAnim to keep switch sites readable
    private static final class ScriptAnimLoop {
        static final int REPEAT = com.ysmef.compat.ysm.script.ScriptAnim.LOOP_REPEAT;
        static final int HOLD = com.ysmef.compat.ysm.script.ScriptAnim.LOOP_HOLD;
    }

    private static final class ScriptAnimKeyLerp {
        static final int STEP = com.ysmef.compat.ysm.script.ScriptAnim.Key.LERP_STEP;
        static final int CATMULLROM = com.ysmef.compat.ysm.script.ScriptAnim.Key.LERP_CATMULLROM;
    }
}
