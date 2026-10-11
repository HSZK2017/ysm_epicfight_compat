package com.ysmef.compat.animation;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.renderer.YSMModelAccess;
import com.ysmef.compat.renderer.YsmModelVariableReader;
import com.ysmef.compat.ysm.YsmModelPackage;
import com.ysmef.compat.ysm.script.Molang;
import com.ysmef.compat.ysm.script.ScriptAnim;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Client-side shadow of model variables changed by YSM's wheel and configuration UI.
 *
 * YSM's own script evaluator is not advanced while its renderer is replaced by
 * Epic Fight in battle mode. Wheel timelines and roulette controls may assign
 * both v.roaming.* and ordinary v.* values without those assignments reaching
 * YSM's animation processor. This tracker replays the assignments in order and
 * keeps one model-scoped variable snapshot per player.
 */
public final class YsmModelVariableState {

    /**
     * Immutable snapshots only: every published value is an unmodifiable map, so
     * the render thread can iterate it while the pool publishes a replacement.
     */
    private record ModelVars(String modelId, Map<String, Float> values) {}

    private static final Map<UUID, ModelVars> PLAYER_VARS = new ConcurrentHashMap<>();
    private static final Pattern VARIABLE_NAME = Pattern.compile(
            "\\b(?:v|variable)\\.[A-Za-z_][A-Za-z0-9_.]*", Pattern.CASE_INSENSITIVE);
    /** Bumped on world leave; in-flight tasks of the old world discard their results. */
    private static final java.util.concurrent.atomic.AtomicLong GENERATION =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Variable evaluations run on this single-threaded daemon pool: the wheel
     * path used to load the whole model package (file read + decrypt + parse)
     * synchronously on the client tick thread, hitching the tick. The
     * single-threaded executor also serializes all per-player updates in
     * submission order, so rapid wheel clicks can no longer compute two
     * updates from the same stale base state (each task reads the snapshot
     * published by the previous task). Results are published as immutable
     * snapshots directly from the pool; readers never race a clear+putAll.
     */
    private static final java.util.concurrent.ExecutorService VARIABLE_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ysm-ef-variables");
                thread.setDaemon(true);
                return thread;
            });

    private YsmModelVariableState() {}

    /**
     * Apply one wheel animation's variable-timeline code for the player. Called
     * on every wheel-animation start transition, including repeated clicks of
     * the same animation (toggles flip again). The model package load and the
     * molang evaluation run on the background pool; results are published as
     * immutable snapshots, serialized in submission order.
     */
    public static void onWheelAnimationStarted(Player player, String modelId, String animationName) {
        if (player == null || modelId == null || animationName == null || animationName.isEmpty()) {
            return;
        }
        UUID uuid = player.getUUID();
        String label = "wheel animation '" + animationName + "' of model '" + modelId + "'";
        onExpressionAsync(uuid, modelId, label, Map.of(), current -> {
            YsmModelPackage pkg = YsmModelPackage.load(modelId);
            ScriptAnim anim = pkg == null ? null : pkg.wheelAnim(animationName);
            if (anim == null) {
                return null;
            }
            Map<Integer, Double> evaluatedVars = new TreeMap<>();
            Molang.Env env = newEnv(current, evaluatedVars);
            for (ScriptAnim.Timeline timeline : anim.timelines) {
                if (timeline.code == null) {
                    continue;
                }
                for (String code : timeline.code) {
                    if (code == null || code.isEmpty()) {
                        continue;
                    }
                    Molang.compile(code).eval(env);
                }
            }
            return collectVariables(evaluatedVars);
        });
    }

    /**
     * Capture a config-driven expression executed by YSM's animation roulette
     * (clothing/headwear/accessory switches), evaluated off-thread.
     */
    public static void onConfigExpression(Player player, String expression) {
        if (player == null || expression == null || expression.isBlank()) {
            return;
        }
        YSMModelAccess.YSMModelRef model = YSMModelAccess.getCurrentModel(player);
        if (model == null) {
            return;
        }
        UUID uuid = player.getUUID();
        String label = "roulette config expression '" + expression + "'";
        java.util.Set<String> names = new java.util.HashSet<>();
        var matcher = VARIABLE_NAME.matcher(expression);
        while (matcher.find()) names.add(matcher.group());
        // A control can use the previous setting (for example v.hat=1-v.hat).
        // Seed the first local evaluation from YSM's actual value rather than 0.
        Map<String, Float> observed = YsmModelVariableReader.read(player, names);
        onExpressionAsync(uuid, model.modelId(), label, observed,
                current -> evaluateConfigExpression(current, expression));
    }

    /** Pure expression step shared by the UI bridge and its regression tests. */
    static Map<String, Float> evaluateConfigExpression(Map<String, Float> current, String expression) {
        Map<Integer, Double> evaluatedVars = new TreeMap<>();
        Molang.Env env = newEnv(current, evaluatedVars);
        for (String part : expression.split(";")) {
            if (!part.isBlank()) {
                Molang.compile(part.trim()).eval(env);
            }
        }
        return collectVariables(evaluatedVars);
    }

    /**
     * Evaluate one model-variable expression on the single-threaded background pool and
     * publish the result as an immutable snapshot. {@code compute} returns null
     * to skip the update entirely (e.g. no such animation), or the new variable
     * map. Because the pool is single-threaded, the next task always reads the
     * snapshot published by the previous task - no lost toggles on rapid clicks.
     */
    private static void onExpressionAsync(UUID uuid, String modelId, String label,
                                                 Map<String, Float> observed,
                                                 java.util.function.Function<Map<String, Float>, Map<String, Float>> compute) {
        long generation = GENERATION.get();
        VARIABLE_POOL.execute(() -> {
            try {
                ModelVars state = PLAYER_VARS.get(uuid);
                Map<String, Float> current = new TreeMap<>(observed);
                if (state != null && state.modelId.equals(modelId)) current.putAll(state.values);
                Map<String, Float> updated = compute.apply(current);
                if (updated == null) {
                    return;
                }
                if (generation != GENERATION.get()) {
                    // world was left while the expression evaluated; drop the stale state
                    return;
                }
                publishVariables(uuid, modelId, updated, generation);
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to track model variables for {}", label, t);
            }
        });
    }

    /** Publish an immutable snapshot from the evaluation pool. */
    private static void publishVariables(UUID uuid, String modelId, Map<String, Float> updated, long generation) {
        if (generation != GENERATION.get()) {
            return;
        }
        ModelVars snapshot = new ModelVars(modelId, Collections.unmodifiableMap(new TreeMap<>(updated)));
        PLAYER_VARS.put(uuid, snapshot);
        // Close the clear() race: if the world was left between the pre-check and
        // the put, remove the just-published stale snapshot.
        if (generation != GENERATION.get()) {
            PLAYER_VARS.remove(uuid, snapshot);
            return;
        }
    }

    /** The tracked model variables for one player and selected model, or an empty map. */
    public static Map<String, Float> getVariables(Player player, String modelId) {
        if (player == null) {
            return Collections.emptyMap();
        }
        UUID uuid = player.getUUID();
        ModelVars vars = PLAYER_VARS.get(uuid);
        if (vars == null) return Collections.emptyMap();
        if (!vars.modelId.equals(modelId)) {
            // YSM reinitializes its variables on model selection. Retaining a
            // previous model's shadow could resurrect that outfit on return.
            PLAYER_VARS.remove(uuid, vars);
            return Collections.emptyMap();
        }
        return vars.values;
    }

    /** Forget all tracked model state (world leave); stale in-flight tasks discard themselves. */
    public static void clear() {
        GENERATION.incrementAndGet();
        PLAYER_VARS.clear();
    }

    // ------------------------------------------------------------------
    // Minimal Molang environment
    // ------------------------------------------------------------------

    private static Map<String, Float> collectVariables(Map<Integer, Double> evaluatedVars) {
        Map<String, Float> updated = new TreeMap<>();
        for (Map.Entry<Integer, Double> entry : evaluatedVars.entrySet()) {
            String name = Molang.nameOf(entry.getKey());
            if (name == null || !(name.startsWith("v.") || name.startsWith("variable."))) {
                continue;
            }
            updated.put(name, (float) (double) entry.getValue());
        }
        return updated;
    }

    private static final int Q_HEALTH = Molang.idOf("query.health");
    private static final int Q_MAX_HEALTH = Molang.idOf("query.max_health");
    private static final int Q_ON_GROUND = Molang.idOf("query.is_on_ground");
    private static final int Q_ALIVE = Molang.idOf("query.is_alive");
    private static final int Q_IDLE = Molang.idOf("ctrl.idle");
    private static final int Q_ANIM_TIME = Molang.idOf("anim_time");

    private static Molang.Env newEnv(Map<String, Float> initial, Map<Integer, Double> outVars) {
        for (Map.Entry<String, Float> entry : initial.entrySet()) {
            outVars.put(Molang.idOf(entry.getKey()), (double) entry.getValue());
        }
        return new Molang.Env() {
            @Override
            public double getVarById(int id) {
                Double value = outVars.get(id);
                return value == null ? 0.0 : value;
            }

            @Override
            public boolean hasVarById(int id) {
                return outVars.containsKey(id);
            }

            @Override
            public void setVarById(int id, double value) {
                outVars.put(id, value);
            }

            @Override
            public double getQueryById(int id) {
                if (id == Q_HEALTH || id == Q_MAX_HEALTH) {
                    return 20.0;
                }
                if (id == Q_ON_GROUND || id == Q_ALIVE || id == Q_IDLE) {
                    return 1.0;
                }
                return 0.0;
            }

            @Override
            public double callFunction(String name, double[] args, int argCount) {
                switch (name) {
                    case "math.sin":
                        return argCount < 1 ? 0.0 : Math.sin(Math.toRadians(args[0]));
                    case "math.cos":
                        return argCount < 1 ? 0.0 : Math.cos(Math.toRadians(args[0]));
                    case "math.tan":
                        return argCount < 1 ? 0.0 : Math.tan(Math.toRadians(args[0]));
                    case "math.asin":
                        return argCount < 1 ? 0.0 : Math.toDegrees(Math.asin(args[0]));
                    case "math.acos":
                        return argCount < 1 ? 0.0 : Math.toDegrees(Math.acos(args[0]));
                    case "math.atan":
                        return argCount < 1 ? 0.0 : Math.toDegrees(Math.atan(args[0]));
                    case "math.abs":
                        return argCount < 1 ? 0.0 : Math.abs(args[0]);
                    case "math.floor":
                        return argCount < 1 ? 0.0 : Math.floor(args[0]);
                    case "math.ceil":
                        return argCount < 1 ? 0.0 : Math.ceil(args[0]);
                    case "math.round":
                        return argCount < 1 ? 0.0 : Math.round(args[0]);
                    case "math.clamp":
                        return argCount < 3 ? 0.0 : Math.max(args[1], Math.min(args[2], args[0]));
                    case "math.max":
                        return argCount < 1 ? 0.0 : argCount < 2 ? args[0] : Math.max(args[0], args[1]);
                    case "math.min":
                        return argCount < 1 ? 0.0 : argCount < 2 ? args[0] : Math.min(args[0], args[1]);
                    default:
                        return 0.0;
                }
            }

            @Override
            public double callStringFunction(String name, String[] args) {
                return 0.0;
            }
        };
    }
}
