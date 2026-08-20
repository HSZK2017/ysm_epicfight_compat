package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.animation.YsmRoamingState;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.renderer.YsmWheelAnimationState;
import com.ysmef.compat.ysm.script.Molang;
import com.ysmef.compat.ysm.script.ScriptAnim;
import com.ysmef.compat.ysm.script.ScriptJson;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import yesman.epicfight.api.client.model.MeshPart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime half of a converted YSM model: the bone table (hierarchy, bind transforms,
 * Epic Fight joint binding) plus the compiled molang animations that drive YSM's
 * model-changing behavior (variant visibility, secondary-bone motion).
 *
 * Loaded lazily from the ysm_runtime JSON written next to each generated mesh
 * (see EFMeshJsonWriter). One instance per model id; per-player animation state
 * lives in {@link YSMPlayerAnimator}.
 */
public final class YSMRuntimeModel {

    // ------------------------------------------------------------------
    // Bone table
    // ------------------------------------------------------------------

    public static final class BoneRt {
        public String name;
        public int parent = -1;
        public float px, py, pz;
        public float rx, ry, rz;
        public int joint;
        public boolean mapped;
        public final Matrix4f bindWorld = new Matrix4f();
        public final Matrix4f bindLocal = new Matrix4f();
        public final Matrix4f bindLocalInv = new Matrix4f();
        public final Matrix4f bindWorldInv = new Matrix4f();
    }

    public final String modelId;
    final BoneRt[] bones;
    final Map<String, Integer> boneIndex;
    final List<CompiledAnim> parallels;
    final Map<String, CompiledAnim> states;
    final Map<String, CompiledAnim> conditionAnims;
    /** All v.roaming.<name> variable names referenced by this model's scripts. */
    final Set<String> roamingNames;
    /** Number of compiled keyframe channels; used to size per-animator cursor arrays. */
    final int channelCount;
    /**
     * The RealCamera bind target solved at conversion time (front face = the
     * plane the "Eyes" element lies in for position + forward, the adjacent
     * right side face for upward, roll 90 degrees), or null when the model has
     * no usable eyes/head geometry.
     */
    public final CameraTarget cameraTarget;

    /** RealCamera bind-target UVs (texture space) + roll offset + bind-space head data, from the runtime JSON. */
    public static final class CameraTarget {
        public final float posU, posV, forwardU, forwardV, upwardU, upwardV, roll;
        /** Bind-space position of the front plane (the eyes plate center). */
        public final float eyesX, eyesY, eyesZ;
        /** Bind-space normal of the front plane. */
        public final float normalX, normalY, normalZ;
        /** Bind-space normal of the upward side face. */
        public final float upX, upY, upZ;

        public CameraTarget(float posU, float posV, float forwardU, float forwardV,
                            float upwardU, float upwardV, float roll,
                            float eyesX, float eyesY, float eyesZ,
                            float normalX, float normalY, float normalZ,
                            float upX, float upY, float upZ) {
            this.posU = posU;
            this.posV = posV;
            this.forwardU = forwardU;
            this.forwardV = forwardV;
            this.upwardU = upwardU;
            this.upwardV = upwardV;
            this.roll = roll;
            this.eyesX = eyesX;
            this.eyesY = eyesY;
            this.eyesZ = eyesZ;
            this.normalX = normalX;
            this.normalY = normalY;
            this.normalZ = normalZ;
            this.upX = upX;
            this.upY = upY;
            this.upZ = upZ;
        }
    }

    private final Map<UUID, YSMPlayerAnimator> animators = new ConcurrentHashMap<>();

    /**
     * Last tick each animator was used (entity.tickCount), used to sweep
     * animators of players that left the world / stopped being rendered, so a
     * big model's per-player evaluator state (hundreds of KB for large models)
     * does not accumulate for every player that ever used it (ModernYSM keeps
     * per-entity state in weak references; the sweep gives the same liveness).
     */
    private final Map<UUID, Integer> animatorLastTick = new ConcurrentHashMap<>();

    /** Sweep cadence: scan at most every 15 s. */
    private static final int ANIMATOR_SWEEP_INTERVAL_TICKS = 300;
    /** Drop animators unused for more than 60 s. */
    private static final int ANIMATOR_TTL_TICKS = 1200;
    private static volatile int lastSweepTick = -1;
    private static final java.util.concurrent.atomic.AtomicBoolean SWEEP_IN_PROGRESS = new java.util.concurrent.atomic.AtomicBoolean(false);

    private YSMRuntimeModel(String modelId, BoneRt[] bones, Map<String, Integer> boneIndex,
                            List<CompiledAnim> parallels, Map<String, CompiledAnim> states,
                            Map<String, CompiledAnim> conditionAnims, Set<String> roamingNames, int channelCount,
                            CameraTarget cameraTarget) {
        this.modelId = modelId;
        this.bones = bones;
        this.boneIndex = boneIndex;
        this.parallels = parallels;
        this.states = states;
        this.conditionAnims = conditionAnims;
        this.roamingNames = roamingNames;
        this.channelCount = channelCount;
        this.cameraTarget = cameraTarget;
    }

    public YSMPlayerAnimator animatorFor(LivingEntity entity) {
        UUID uuid = entity.getUUID();
        int tick = entity.tickCount;
        animatorLastTick.put(uuid, tick);
        YSMPlayerAnimator animator = animators.get(uuid);
        if (animator == null) {
            animator = new YSMPlayerAnimator(this);
            animators.put(uuid, animator);
        }
        sweepIfDue(tick);
        return animator;
    }

    /** Periodically drop stale per-player animators (see {@link #ANIMATOR_TTL_TICKS}). */
    private static void sweepIfDue(int tick) {
        int last = lastSweepTick;
        if (tick - last < ANIMATOR_SWEEP_INTERVAL_TICKS) {
            return;
        }
        if (!SWEEP_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }
        try {
            lastSweepTick = tick;
            java.util.List<YSMRuntimeModel> models;
            synchronized (CACHE) {
                models = new ArrayList<>(CACHE.values());
            }
            for (YSMRuntimeModel model : models) {
                model.sweepAnimators(tick);
            }
        } finally {
            SWEEP_IN_PROGRESS.set(false);
        }
    }

    private void sweepAnimators(int nowTick) {
        java.util.Iterator<Map.Entry<UUID, Integer>> it = animatorLastTick.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            if (nowTick - entry.getValue() > ANIMATOR_TTL_TICKS) {
                it.remove();
                animators.remove(entry.getKey());
            }
        }
    }

    // ------------------------------------------------------------------
    // Default visibility (battle mode)
    // ------------------------------------------------------------------

    private static final double HIDE_SCALE_EPSILON = 0.01;

    /**
     * Per-bone visibility of the model's default form, computed once from the
     * parallel scripts (pre_parallel* / parallel*) with a frozen, neutral
     * molang environment (no variables set; queries default to full health /
     * standing / idle, everything else 0, see {@link #newDefaultEnv()}).
     * YSM collapses animation-driven variant geometry (weapons, expressions,
     * attachments) to scale 0 in those scripts, so evaluating them statically
     * yields exactly the main model without any animation-related variants.
     */
    private volatile boolean[] defaultHidden;

    /**
     * Roaming-variable visibility cache: wheel animations can toggle persistent
     * v.roaming.* values (gun, key, accessory switches, ...), and the model's
     * parallel visibility scripts use them to show or collapse accessory parts.
     * Epic Fight battle mode never runs YSM's own script evaluator, so this
     * evaluates the visibility scripts statically with the current roaming
     * values whenever the player has any, and caches the resulting part-hidden
     * mask per variable fingerprint.
     */
    private final Map<Long, boolean[]> roamingHiddenCache = new ConcurrentHashMap<>();
    private static final int ROAMING_HIDDEN_CACHE_MAX = 32;

    /**
     * Battle-mode visibility honoring YSM's current persistent roaming variables
     * (accessories toggled by wheel animations). Falls back to the neutral
     * default form when the YSM fork exposes no roaming variables.
     */
    public void applyEntityVisibility(YSMMesh mesh, Player player) {
        ensureDefaultPartMap(mesh);
        java.util.Map<String, Float> ysmRoaming = YsmWheelAnimationState.readRoamingVars(player, roamingNames);
        java.util.Map<String, Float> trackedRoaming = YsmRoamingState.getRoaming(player);
        // YSM's own roaming struct is authoritative when available; the local
        // tracker fills the gap while YSM's evaluator is idle in battle mode.
        java.util.Map<String, Float> roaming = new java.util.TreeMap<>(trackedRoaming);
        roaming.putAll(ysmRoaming);
        boolean[] hidden;
        if (roaming.isEmpty()) {
            if (ROAMING_EMPTY_LOGGED.add(modelId)) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [roaming] no roaming variables exposed for model '{}' (battle-mode accessories fall back to default form)",
                        modelId);
            }
            hidden = defaultHidden();
        } else {
            long fingerprint = roamingFingerprint(roaming);
            hidden = roamingHiddenCache.get(fingerprint);
            if (hidden == null) {
                hidden = computeHiddenWithRoaming(roaming);
                if (roamingHiddenCache.size() >= ROAMING_HIDDEN_CACHE_MAX) {
                    roamingHiddenCache.clear();
                }
                roamingHiddenCache.put(fingerprint, hidden);
            }
            String stateKey = fingerprint + ":" + roaming;
            if (ROAMING_STATE_LOGGED.add(stateKey)) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [roaming] model='{}' vars={} -> {} hidden bone parts",
                        modelId, roaming, countHidden(hidden));
            }
        }
        for (int i = 0; i < defaultBoneParts.size(); i++) {
            int boneIdx = defaultPartBoneIdx[i];
            defaultBoneParts.get(i).setHidden(boneIdx >= 0 && boneIdx < hidden.length && hidden[boneIdx]);
        }
    }

    private static final java.util.Set<String> ROAMING_EMPTY_LOGGED = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> ROAMING_STATE_LOGGED = ConcurrentHashMap.newKeySet();

    private static int countHidden(boolean[] hidden) {
        int count = 0;
        for (boolean b : hidden) {
            if (b) {
                count++;
            }
        }
        return count;
    }

    private static long roamingFingerprint(java.util.Map<String, Float> roaming) {
        long hash = 1125899906842597L;
        for (java.util.Map.Entry<String, Float> entry : roaming.entrySet()) {
            hash = hash * 31L + entry.getKey().hashCode();
            hash = hash * 31L + Float.floatToIntBits(entry.getValue());
        }
        return hash;
    }

    private boolean[] computeHiddenWithRoaming(java.util.Map<String, Float> roaming) {
        Molang.Env env = newDefaultEnv();
        for (java.util.Map.Entry<String, Float> entry : roaming.entrySet()) {
            env.setVar("v.roaming." + entry.getKey(), entry.getValue());
        }
        return computeHidden(env);
    }

    /**
     * Apply the default-form visibility to every per-bone part of the mesh.
     * Used in Epic Fight battle mode, where no script animation may run.
     * The part -> bone-index mapping is captured once per mesh instance
     * (see ensureDefaultPartMap): this runs every frame per drawn player, and
     * the previous per-part substring + map lookup allocated a String per part
     * per frame for meshes with hundreds of bone parts.
     */
    public void applyDefaultVisibility(YSMMesh mesh) {
        ensureDefaultPartMap(mesh);
        boolean[] hidden = defaultHidden();
        for (int i = 0; i < defaultBoneParts.size(); i++) {
            int boneIdx = defaultPartBoneIdx[i];
            // -1 = the part's bone is not in the runtime bone table: keep it
            // visible (same semantics as before, only without the per-frame
            // substring/lookup work).
            defaultBoneParts.get(i).setHidden(boneIdx >= 0 && boneIdx < hidden.length && hidden[boneIdx]);
        }
    }

    private YSMMesh defaultPartMapMesh;
    private List<MeshPart> defaultBoneParts;
    private int[] defaultPartBoneIdx;

    private void ensureDefaultPartMap(YSMMesh mesh) {
        if (defaultBoneParts != null && defaultPartMapMesh == mesh) {
            return;
        }
        defaultPartMapMesh = mesh;
        List<MeshPart> parts = new ArrayList<>();
        List<Integer> idxs = new ArrayList<>();
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            String boneName = partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length());
            Integer boneIdx = boneIndex.get(boneName);
            parts.add(entry.getValue());
            idxs.add(boneIdx != null ? boneIdx : -1);
        }
        defaultBoneParts = parts;
        defaultPartBoneIdx = new int[idxs.size()];
        for (int i = 0; i < idxs.size(); i++) {
            defaultPartBoneIdx[i] = idxs.get(i);
        }
    }

    private boolean[] defaultHidden() {
        boolean[] result = defaultHidden;
        if (result == null) {
            synchronized (this) {
                result = defaultHidden;
                if (result == null) {
                    result = computeDefaultHidden();
                    defaultHidden = result;
                }
            }
        }
        return result;
    }

    private boolean[] computeDefaultHidden() {
        return computeHidden(newDefaultEnv());
    }

    private boolean[] computeHidden(Molang.Env env) {
        int n = bones.length;
        // evaluate each parallel anim's t=0 scale channels; later anims override
        // earlier ones per bone, mirroring the animator's shared scratch arrays
        float[][] scales = new float[n][];
        for (CompiledAnim anim : parallels) {
            for (CompiledTimeline timeline : anim.timelines) {
                if (timeline.time <= 0) {
                    for (Molang.Expr expr : timeline.code) {
                        expr.eval(env);
                    }
                }
            }
            for (Map.Entry<Integer, CompiledChannels> entry : anim.bones.entrySet()) {
                CompiledChannel channel = entry.getValue().scale;
                if (channel != null) {
                    scales[entry.getKey()] = evalChannelAtZero(channel, env);
                }
            }
        }
        boolean[] hidden = new boolean[n];
        boolean[] done = new boolean[n];
        float[] eff = new float[n];
        for (int i = 0; i < n; i++) {
            hidden[i] = effectiveScale(i, scales, done, eff) < HIDE_SCALE_EPSILON;
        }
        return hidden;
    }

    private float effectiveScale(int boneIdx, float[][] scales, boolean[] done, float[] eff) {
        if (done[boneIdx]) {
            return eff[boneIdx];
        }
        float own = 1.0f;
        if (scales[boneIdx] != null) {
            own = Math.min(scales[boneIdx][0], Math.min(scales[boneIdx][1], scales[boneIdx][2]));
        }
        float parent = bones[boneIdx].parent >= 0
                ? effectiveScale(bones[boneIdx].parent, scales, done, eff)
                : 1.0f;
        eff[boneIdx] = parent * own;
        done[boneIdx] = true;
        return eff[boneIdx];
    }

    private static float[] evalChannelAtZero(CompiledChannel channel, Molang.Env env) {
        int idx = 0;
        for (int i = 0; i < channel.times.length; i++) {
            if (channel.times[i] <= 0) {
                idx = i;
            }
        }
        Molang.Expr[] axes = channel.post[idx];
        return new float[]{
                (float) axes[0].eval(env),
                (float) axes[1].eval(env),
                (float) axes[2].eval(env)};
    }

    private static Molang.Env newDefaultEnv() {
        return new Molang.Env() {
            private final java.util.Map<Integer, Double> vars = new HashMap<>();
            private final java.util.Set<Integer> varSet = new HashSet<>();

            @Override
            public double getVarById(int id) {
                return varSet.contains(id) ? vars.getOrDefault(id, 0.0) : 0.0;
            }

            @Override
            public boolean hasVarById(int id) {
                return varSet.contains(id);
            }

            @Override
            public void setVarById(int id, double value) {
                vars.put(id, value);
                varSet.add(id);
            }

            @Override
            public double getQueryById(int id) {
                // Neutral query defaults for the model's default form: full health
                // (so damage-driven variants like low-HP bodies collapse away),
                // standing on the ground, idle state, everything else unset.
                // All-zero defaults would evaluate health conditions as "dead"
                // (query.health = 0), leaving low-HP variant geometry visible in
                // the default form rendered during Epic Fight combat animations.
                if (id == Q_HEALTH || id == Q_MAX_HEALTH) {
                    return 20.0;
                }
                if (id == Q_ON_GROUND || id == Q_ALIVE || id == Q_IDLE) {
                    return 1.0;
                }
                return 0.0;
            }

            @Override
            public double callFunction(String name, double[] args) {
                // The default-form visibility evaluation must honor math.* calls
                // in scale channels (e.g. math.clamp driving an eye plate's
                // blink scale); returning 0 for every call collapsed those bones
                // and hid the geometry (the sta model's "missing eyes").
                return evalMathFunction(name, args);
            }

            @Override
            public double callStringFunction(String name, String[] args) {
                return 0.0;
            }
        };
    }

    /**
     * The math.* function set available to the default-form visibility
     * evaluation (mirrors the per-frame animator env in YSMPlayerAnimator).
     * Unknown functions evaluate to 0.
     */
    private static double evalMathFunction(String name, double[] args) {
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
            case "math.pi":
                return Math.PI;
            case "math.sign":
                return Math.signum(args[0]);
            default:
                return 0.0;
        }
    }

    private static final int Q_HEALTH = Molang.idOf("query.health");
    private static final int Q_MAX_HEALTH = Molang.idOf("query.max_health");
    private static final int Q_ON_GROUND = Molang.idOf("query.is_on_ground");
    private static final int Q_ALIVE = Molang.idOf("query.is_alive");
    private static final int Q_IDLE = Molang.idOf("ctrl.idle");

    // ------------------------------------------------------------------
    // Loading / compilation
    // ------------------------------------------------------------------

    private static final Map<String, YSMRuntimeModel> CACHE = new HashMap<>();

    /** Models whose runtime JSON is being compiled on a background thread. */
    private static final java.util.Set<String> PRELOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Incremented on invalidateAll: stale background compiles drop their results. */
    private static final java.util.concurrent.atomic.AtomicInteger RELOAD_GENERATION = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Get the compiled runtime model for a YSM model id, or null if unavailable.
     *
     * No per-call disk stat: staleness is handled explicitly by the caller.
     * YSMMeshLibrary calls {@link #invalidate(String)} after (re)converting a
     * model, and the reload paths call {@link #invalidateAll()}; without those
     * the compiled model is cached for the session. (Previously the file mtime
     * was re-read on every call - a disk stat per player per frame.)
     *
     * The compile normally runs on a background thread ({@link #preload(String)},
     * started when the mesh is registered); while it is in flight this returns
     * null so the render thread never blocks on the compile, and the caller
     * renders the un-evaluated fallback for a few frames instead of hitching.
     */
    public static YSMRuntimeModel get(String modelId) {
        synchronized (CACHE) {
            if (CACHE.containsKey(modelId)) {
                return CACHE.get(modelId);
            }
        }
        if (PRELOADING.contains(modelId)) {
            return null;
        }
        return loadAndCache(modelId);
    }

    /**
     * Background preload: compile the runtime model off the render thread.
     * Called from the conversion pool right after the runtime JSON was written,
     * and (submitted) when a model is restored from the verified on-disk cache,
     * so the first draw finds the compiled model instead of compiling inline.
     *
     * Deduplicated via {@link #PRELOADING}; the result is only cached when the
     * task is still the current one (reloads/re-conversions drop stale results,
     * the next {@link #get} then compiles synchronously as the fallback).
     */
    public static void preload(String modelId) {
        synchronized (CACHE) {
            if (CACHE.containsKey(modelId)) {
                return;
            }
        }
        if (!PRELOADING.add(modelId)) {
            return;
        }
        int generation = RELOAD_GENERATION.get();
        try {
            YSMRuntimeModel model = loadAndCompile(modelId);
            if (PRELOADING.remove(modelId) && generation == RELOAD_GENERATION.get()) {
                synchronized (CACHE) {
                    CACHE.put(modelId, model);
                }
            }
        } catch (Throwable t) {
            PRELOADING.remove(modelId);
        }
    }

    private static YSMRuntimeModel loadAndCache(String modelId) {
        synchronized (CACHE) {
            if (CACHE.containsKey(modelId)) {
                return CACHE.get(modelId);
            }
            YSMRuntimeModel model = loadAndCompile(modelId);
            CACHE.put(modelId, model);
            return model;
        }
    }

    private static YSMRuntimeModel loadAndCompile(String modelId) {
        Path file = YSMMeshLibrary.getRuntimeFile(YSMMeshLibrary.meshIdOf(modelId));
        try {
            String json = Files.readString(file);
            return compile(modelId, JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to load runtime model '{}': {}", modelId, e.toString());
            return null;
        }
    }

    /** Forget one cached runtime model (called after its mesh was (re)converted). */
    public static void invalidate(String modelId) {
        synchronized (CACHE) {
            CACHE.remove(modelId);
        }
        PRELOADING.remove(modelId);
    }

    /** Forget all cached runtime models (called when meshes are regenerated). */
    public static void invalidateAll() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        PRELOADING.clear();
        RELOAD_GENERATION.incrementAndGet();
    }

    private static YSMRuntimeModel compile(String modelId, JsonObject root) {
        // bones
        JsonArray bonesArr = root.getAsJsonArray("bones");
        Map<String, BoneRt> byName = new LinkedHashMap<>();
        Map<String, String> parentNames = new HashMap<>();
        if (bonesArr != null) {
            for (JsonElement el : bonesArr) {
                JsonObject obj = el.getAsJsonObject();
                BoneRt bone = new BoneRt();
                bone.name = obj.get("name").getAsString();
                JsonArray pivot = obj.getAsJsonArray("pivot");
                bone.px = pivot.get(0).getAsFloat();
                bone.py = pivot.get(1).getAsFloat();
                bone.pz = pivot.get(2).getAsFloat();
                JsonArray rot = obj.getAsJsonArray("rot");
                bone.rx = rot.get(0).getAsFloat();
                bone.ry = rot.get(1).getAsFloat();
                bone.rz = rot.get(2).getAsFloat();
                bone.joint = obj.get("joint").getAsInt();
                bone.mapped = obj.has("mapped") && obj.get("mapped").getAsBoolean();
                parentNames.put(bone.name, obj.has("parent") ? obj.get("parent").getAsString() : "");
                byName.put(bone.name, bone);
            }
        }
        // resolve parents and compute bind transforms
        Map<String, Integer> boneIndex = new HashMap<>();
        List<BoneRt> boneList = new ArrayList<>(byName.values());
        Map<String, Integer> nameToListIdx = new HashMap<>();
        for (int i = 0; i < boneList.size(); i++) {
            nameToListIdx.put(boneList.get(i).name, i);
        }
        for (int i = 0; i < boneList.size(); i++) {
            BoneRt bone = boneList.get(i);
            String parentName = parentNames.getOrDefault(bone.name, "");
            Integer parentIdx = parentName.isEmpty() ? null : nameToListIdx.get(parentName);
            bone.parent = parentIdx != null ? parentIdx : -1;
            boneIndex.put(bone.name, i);
            computeBindLocal(bone);
        }
        BoneRt[] bones = boneList.toArray(new BoneRt[0]);
        for (int i = 0; i < bones.length; i++) {
            computeBindWorld(bones, i);
            bones[i].bindWorldInv.set(bones[i].bindWorld).invert();
        }

        // animations
        List<CompiledAnim> parallels = new ArrayList<>();
        Map<String, CompiledAnim> states = new HashMap<>();
        Map<String, CompiledAnim> conditions = new HashMap<>();
        Set<String> brokenAnims = new HashSet<>();
        JsonObject anims = root.has("animations") ? root.getAsJsonObject("animations") : null;
        int nextChannelId = 0;
        if (anims != null) {
            for (Map.Entry<String, JsonElement> entry : anims.entrySet()) {
                String name = entry.getKey();
                try {
                    CompiledAnim anim = compileAnim(ScriptJson.animationsFromJson(name, entry.getValue().getAsJsonObject()), boneIndex);
                    if (name.startsWith("pre_parallel") || name.startsWith("parallel")) {
                        parallels.add(anim);
                    } else if (isConditionAnim(name)) {
                        conditions.put(name, anim);
                    } else {
                        states.put(name, anim);
                    }
                    nextChannelId = assignChannelIds(anim, nextChannelId);
                } catch (Exception e) {
                    // One broken molang animation must not disable variant visibility
                    // for the whole model (that would render every variant at once).
                    if (brokenAnims.add(name)) {
                        YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: skipped broken runtime animation '{}': {}", name, e.toString());
                    }
                }
            }
        }
        // pre_parallel* first, then parallel*, each in numeric order
        parallels.sort(Comparator.comparing((CompiledAnim a) -> a.name.startsWith("pre_parallel") ? 0 : 1)
                .thenComparing(a -> a.name));
        CameraTarget cameraTarget = parseCameraTarget(root);
        YSMRuntimeModel model = new YSMRuntimeModel(modelId, bones, boneIndex, parallels, states, conditions,
                collectRoamingNames(root), nextChannelId, cameraTarget);
        if (cameraTarget != null) {
            com.ysmef.compat.realcamera.YsmRealCameraBridge.onRuntimeModelLoaded(modelId, cameraTarget);
        }
        return model;
    }

    /** The optional "camera" section written by EFMeshJsonWriter (RealCamera bind target). */
    private static CameraTarget parseCameraTarget(JsonObject root) {
        if (!root.has("camera") || !root.get("camera").isJsonObject()) {
            return null;
        }
        try {
            JsonObject camera = root.getAsJsonObject("camera");
            return new CameraTarget(
                    camera.get("posU").getAsFloat(), camera.get("posV").getAsFloat(),
                    camera.get("forwardU").getAsFloat(), camera.get("forwardV").getAsFloat(),
                    camera.get("upwardU").getAsFloat(), camera.get("upwardV").getAsFloat(),
                    camera.has("roll") ? camera.get("roll").getAsFloat() : 90.0f,
                    camera.has("eyesX") ? camera.get("eyesX").getAsFloat() : 0.0f,
                    camera.has("eyesY") ? camera.get("eyesY").getAsFloat() : 0.0f,
                    camera.has("eyesZ") ? camera.get("eyesZ").getAsFloat() : 0.0f,
                    camera.has("normalX") ? camera.get("normalX").getAsFloat() : 0.0f,
                    camera.has("normalY") ? camera.get("normalY").getAsFloat() : 0.0f,
                    camera.has("normalZ") ? camera.get("normalZ").getAsFloat() : -1.0f,
                    camera.has("upX") ? camera.get("upX").getAsFloat() : 0.0f,
                    camera.has("upY") ? camera.get("upY").getAsFloat() : 1.0f,
                    camera.has("upZ") ? camera.get("upZ").getAsFloat() : 0.0f);
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: skipped broken camera section of runtime model: {}", e.toString());
            return null;
        }
    }

    /** Extract every distinct v.roaming.<name> variable referenced anywhere in the runtime JSON. */
    private static Set<String> collectRoamingNames(JsonObject root) {
        Set<String> names = new HashSet<>();
        java.util.ArrayDeque<JsonElement> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("v\\.roaming\\.([A-Za-z_][A-Za-z0-9_]*)");
        while (!queue.isEmpty()) {
            JsonElement element = queue.poll();
            if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    queue.add(entry.getValue());
                }
            } else if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    queue.add(child);
                }
            } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                java.util.regex.Matcher matcher = pattern.matcher(element.getAsString());
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        return names;
    }

    /**
     * Assign sequential channel ids to every keyframe channel of an animation
     * (rot/pos/scale per animated bone). Ids are unique per model and index the
     * per-animator incremental keyframe cursors (see YSMPlayerAnimator).
     */
    private static int assignChannelIds(CompiledAnim anim, int nextId) {
        for (CompiledChannels channels : anim.bones.values()) {
            if (channels.rot != null) {
                channels.rot.channelId = nextId++;
            }
            if (channels.pos != null) {
                channels.pos.channelId = nextId++;
            }
            if (channels.scale != null) {
                channels.scale.channelId = nextId++;
            }
        }
        return nextId;
    }

    private static boolean isConditionAnim(String name) {
        return name.startsWith("hold_mainhand:") || name.startsWith("hold_offhand:")
                || name.startsWith("use_mainhand:") || name.startsWith("use_offhand:")
                || name.startsWith("vehicle$");
    }

    private static void computeBindLocal(BoneRt bone) {
        bone.bindLocal.translation(bone.px, bone.py, bone.pz)
                .rotateZ(bone.rz).rotateY(bone.ry).rotateX(bone.rx)
                .translate(-bone.px, -bone.py, -bone.pz);
        bone.bindLocalInv.set(bone.bindLocal).invert();
    }

    private static void computeBindWorld(BoneRt[] bones, int i) {
        BoneRt bone = bones[i];
        if (bone.parent >= 0) {
            computeBindWorld(bones, bone.parent);
            bone.bindWorld.set(bones[bone.parent].bindWorld).mul(bone.bindLocal);
        } else {
            bone.bindWorld.set(bone.bindLocal);
        }
    }

    // ------------------------------------------------------------------
    // Animation compilation
    // ------------------------------------------------------------------

    public static final class CompiledAnim {
        public String name;
        public int loop;
        public float length;
        public Map<Integer, CompiledChannels> bones = new HashMap<>();
        public CompiledTimeline[] timelines = new CompiledTimeline[0];
    }

    public static final class CompiledChannels {
        public CompiledChannel rot, pos, scale;
    }

    public static final class CompiledChannel {
        public int channelId;
        public float[] times;
        public int[] lerps;
        public Molang.Expr[][] post;   // [key][axis]
        public Molang.Expr[][] pre;    // [key][axis], nullable per key
    }

    public static final class CompiledTimeline {
        public float time;
        public Molang.Expr[] code;
    }

    private static CompiledAnim compileAnim(ScriptAnim src, Map<String, Integer> boneIndex) {
        CompiledAnim anim = new CompiledAnim();
        anim.name = src.name;
        anim.loop = src.loop;
        anim.length = src.length;
        for (Map.Entry<String, ScriptAnim.BoneChannels> entry : src.bones.entrySet()) {
            Integer idx = boneIndex.get(entry.getKey());
            if (idx == null) {
                continue;
            }
            CompiledChannels ch = new CompiledChannels();
            ch.rot = compileChannel(entry.getValue().rotation);
            ch.pos = compileChannel(entry.getValue().position);
            ch.scale = compileChannel(entry.getValue().scale);
            anim.bones.put(idx, ch);
        }
        if (!src.timelines.isEmpty()) {
            anim.timelines = new CompiledTimeline[src.timelines.size()];
            for (int i = 0; i < src.timelines.size(); i++) {
                ScriptAnim.Timeline tl = src.timelines.get(i);
                CompiledTimeline ct = new CompiledTimeline();
                ct.time = tl.time;
                ct.code = new Molang.Expr[tl.code.length];
                for (int j = 0; j < tl.code.length; j++) {
                    ct.code[j] = Molang.compile(tl.code[j]);
                }
                anim.timelines[i] = ct;
            }
        }
        return anim;
    }

    private static CompiledChannel compileChannel(ScriptAnim.Channel src) {
        if (src == null || src.keys.isEmpty()) {
            return null;
        }
        CompiledChannel ch = new CompiledChannel();
        int n = src.keys.size();
        ch.times = new float[n];
        ch.lerps = new int[n];
        ch.post = new Molang.Expr[n][];
        ch.pre = new Molang.Expr[n][];
        for (int i = 0; i < n; i++) {
            ScriptAnim.Key key = src.keys.get(i);
            ch.times[i] = key.time;
            ch.lerps[i] = key.lerp;
            ch.post[i] = compileValue(key.post);
            ch.pre[i] = key.pre != null ? compileValue(key.pre) : null;
        }
        return ch;
    }

    private static Molang.Expr[] compileValue(ScriptAnim.Value value) {
        Molang.Expr[] axes = new Molang.Expr[3];
        for (int i = 0; i < 3; i++) {
            if (value.expr[i] != null) {
                axes[i] = Molang.compile(value.expr[i]);
            } else {
                double n = value.num[i];
                axes[i] = env -> n;
            }
        }
        return axes;
    }
}
