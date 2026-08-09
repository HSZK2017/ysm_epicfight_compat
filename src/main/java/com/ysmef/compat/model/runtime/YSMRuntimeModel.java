package com.ysmef.compat.model.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.EFMeshJsonWriter;
import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.model.YSMMeshLibrary;
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
    /** Number of compiled keyframe channels; used to size per-animator cursor arrays. */
    final int channelCount;

    /**
     * TLM model-pack entries may forbid the model's own backpack geometry
     * ("show_backpack": false); the tlm.has_backpack query is forced to 0 then.
     * Always true for YSM packages.
     */
    public final boolean tlmShowBackpack;

    private final Map<UUID, YSMPlayerAnimator> animators = new ConcurrentHashMap<>();

    private YSMRuntimeModel(String modelId, BoneRt[] bones, Map<String, Integer> boneIndex,
                            List<CompiledAnim> parallels, Map<String, CompiledAnim> states,
                            Map<String, CompiledAnim> conditionAnims, int channelCount, boolean tlmShowBackpack) {
        this.modelId = modelId;
        this.bones = bones;
        this.boneIndex = boneIndex;
        this.parallels = parallels;
        this.states = states;
        this.conditionAnims = conditionAnims;
        this.channelCount = channelCount;
        this.tlmShowBackpack = tlmShowBackpack;
    }

    public YSMPlayerAnimator animatorFor(LivingEntity entity) {
        return animators.computeIfAbsent(entity.getUUID(), id -> new YSMPlayerAnimator(this));
    }

    // [decommissioned] dead method: never called; per-player animators are kept
    // per UUID on purpose but are only cleared when the runtime model is dropped.
    //
    // public static void clearAnimators() {
    //     synchronized (CACHE) {
    //         for (YSMRuntimeModel model : CACHE.values()) {
    //             model.animators.clear();
    //         }
    //     }
    // }

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
     * Apply the default-form visibility to every per-bone part of the mesh.
     * Used in Epic Fight battle mode, where no script animation may run.
     */
    public void applyDefaultVisibility(YSMMesh mesh) {
        boolean[] hidden = defaultHidden();
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            String boneName = partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length());
            Integer boneIdx = boneIndex.get(boneName);
            entry.getValue().setHidden(boneIdx != null && boneIdx < hidden.length && hidden[boneIdx]);
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
        int n = bones.length;
        Molang.Env env = newDefaultEnv();
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
                return 0.0;
            }

            @Override
            public double callStringFunction(String name, String[] args) {
                return 0.0;
            }
        };
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
        Path file = runtimeFileOf(modelId);
        try {
            String json = Files.readString(file);
            return compile(modelId, JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to load runtime model '{}': {}", modelId, e.toString());
            return null;
        }
    }

    /**
     * Resolves the runtime file of a model id. TLM model-pack meshes keep their
     * runtime scripts under the tlm/ subdir and are named "namespace__path"
     * (tlmMeshIdOf); TLM's extra-texture variants (model_id + "_" + md5(texturePath))
     * share the base model's runtime file. YSM model ids use the plain sanitize
     * name at the top level.
     */
    private static Path runtimeFileOf(String modelId) {
        List<Path> candidates = new ArrayList<>();
        addRuntimeCandidates(candidates, YSMMeshLibrary.meshIdOf(modelId));
        String tlmId = YSMMeshLibrary.tlmMeshIdOf(modelId);
        if (tlmId != null && !tlmId.equals(YSMMeshLibrary.meshIdOf(modelId))) {
            addRuntimeCandidates(candidates, tlmId);
        }
        if (tlmId != null && tlmId.length() > 33 && tlmId.matches(".*_[0-9a-f]{32}")) {
            // TLM extra-texture variant: reuse the base model's runtime file
            addRuntimeCandidates(candidates, tlmId.substring(0, tlmId.length() - 33));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    private static void addRuntimeCandidates(List<Path> out, String meshId) {
        out.add(YSMMeshLibrary.getRuntimeFile(meshId));
        out.add(YSMMeshLibrary.getRuntimeFile("tlm/" + meshId));
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
        boolean tlmShowBackpack = !root.has("tlm_show_backpack") || root.get("tlm_show_backpack").getAsBoolean();
        return new YSMRuntimeModel(modelId, bones, boneIndex, parallels, states, conditions, nextChannelId, tlmShowBackpack);
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
