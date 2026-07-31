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
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import yesman.epicfight.api.client.model.MeshPart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    }

    public final String modelId;
    final BoneRt[] bones;
    final Map<String, Integer> boneIndex;
    final List<CompiledAnim> parallels;
    final Map<String, CompiledAnim> states;
    final Map<String, CompiledAnim> conditionAnims;

    private final Map<UUID, YSMPlayerAnimator> animators = new ConcurrentHashMap<>();

    private YSMRuntimeModel(String modelId, BoneRt[] bones, Map<String, Integer> boneIndex,
                            List<CompiledAnim> parallels, Map<String, CompiledAnim> states,
                            Map<String, CompiledAnim> conditionAnims) {
        this.modelId = modelId;
        this.bones = bones;
        this.boneIndex = boneIndex;
        this.parallels = parallels;
        this.states = states;
        this.conditionAnims = conditionAnims;
    }

    public YSMPlayerAnimator animatorFor(Player player) {
        return animators.computeIfAbsent(player.getUUID(), id -> new YSMPlayerAnimator(this));
    }

    public static void clearAnimators() {
        synchronized (CACHE) {
            for (YSMRuntimeModel model : CACHE.values()) {
                model.animators.clear();
            }
        }
    }

    // ------------------------------------------------------------------
    // Default visibility (battle mode)
    // ------------------------------------------------------------------

    private static final double HIDE_SCALE_EPSILON = 0.01;

    /**
     * Per-bone visibility of the model's default form, computed once from the
     * parallel scripts (pre_parallel* / parallel*) with a frozen, all-default
     * molang environment (no variables set, every query/function returning 0).
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
            private final Map<String, Double> vars = new HashMap<>();

            @Override
            public double getVar(String path) {
                return vars.getOrDefault(path, 0.0);
            }

            @Override
            public boolean hasVar(String path) {
                return vars.containsKey(path);
            }

            @Override
            public void setVar(String path, double value) {
                vars.put(path, value);
            }

            @Override
            public double getQuery(String path) {
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

    // ------------------------------------------------------------------
    // Loading / compilation
    // ------------------------------------------------------------------

    private static final Map<String, YSMRuntimeModel> CACHE = new HashMap<>();
    private static final Map<String, Long> CACHE_MTIME = new HashMap<>();

    /** Get the compiled runtime model for a YSM model id, or null if unavailable. */
    public static YSMRuntimeModel get(String modelId) {
        Path file = YSMMeshLibrary.getRuntimeFile(YSMMeshLibrary.meshIdOf(modelId));
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return null;
        }
        synchronized (CACHE) {
            Long cachedMtime = CACHE_MTIME.get(modelId);
            if (cachedMtime != null && cachedMtime == mtime) {
                return CACHE.get(modelId);
            }
            try {
                String json = Files.readString(file);
                YSMRuntimeModel model = compile(modelId, JsonParser.parseString(json).getAsJsonObject());
                CACHE.put(modelId, model);
                CACHE_MTIME.put(modelId, mtime);
                return model;
            } catch (Exception e) {
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to load runtime model '{}': {}", modelId, e.toString());
                CACHE.put(modelId, null);
                CACHE_MTIME.put(modelId, mtime);
                return null;
            }
        }
    }

    /** Forget all cached runtime models (called when meshes are regenerated). */
    public static void invalidateAll() {
        synchronized (CACHE) {
            CACHE.clear();
            CACHE_MTIME.clear();
        }
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
        }

        // animations
        List<CompiledAnim> parallels = new ArrayList<>();
        Map<String, CompiledAnim> states = new HashMap<>();
        Map<String, CompiledAnim> conditions = new HashMap<>();
        JsonObject anims = root.has("animations") ? root.getAsJsonObject("animations") : null;
        if (anims != null) {
            for (Map.Entry<String, JsonElement> entry : anims.entrySet()) {
                String name = entry.getKey();
                CompiledAnim anim = compileAnim(ScriptJson.animationsFromJson(name, entry.getValue().getAsJsonObject()), boneIndex);
                if (name.startsWith("pre_parallel") || name.startsWith("parallel")) {
                    parallels.add(anim);
                } else if (isConditionAnim(name)) {
                    conditions.put(name, anim);
                } else {
                    states.put(name, anim);
                }
            }
        }
        // pre_parallel* first, then parallel*, each in numeric order
        parallels.sort(Comparator.comparing((CompiledAnim a) -> a.name.startsWith("pre_parallel") ? 0 : 1)
                .thenComparing(a -> a.name));
        return new YSMRuntimeModel(modelId, bones, boneIndex, parallels, states, conditions);
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
