package com.ysmef.compat.animation;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.ysm.YsmModelPackage;
import com.ysmef.compat.ysm.script.Molang;
import com.ysmef.compat.ysm.script.ScriptAnim;
import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side shadow of YSM's persistent v.roaming.* variables for battle mode.
 *
 * YSM's own script evaluator is not advanced while its renderer is replaced by
 * Epic Fight in battle mode, so wheel-animation timelines that toggle
 * v.roaming.* values (gun/key/accessory switches such as "toggle key" or
 * "switch personality") never reach the runtime evaluator our mesh visibility
 * uses. Instead, whenever a wheel animation starts, this tracker replays its
 * timeline code once against the previous roaming values, exactly like YSM's
 * persistent roaming struct would, and keeps the resulting values per player.
 */
public final class YsmRoamingState {

    private static final Map<UUID, Map<String, Float>> PLAYER_VARS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * Roaming evaluations run on this single-threaded daemon pool: the wheel
     * path used to load the whole model package (file read + decrypt + parse)
     * synchronously on the client tick thread, hitching the tick. The
     * evaluation result is applied on the main thread (see applyRoaming), so
     * readers of PLAYER_VARS never race a clear+putAll.
     */
    private static final java.util.concurrent.ExecutorService ROAMING_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ysm-ef-roaming");
                thread.setDaemon(true);
                return thread;
            });

    private YsmRoamingState() {}

    /**
     * Apply one wheel animation's roaming-timeline code for the player. Called
     * on every wheel-animation start transition, including repeated clicks of
     * the same animation (toggles flip again). The model package load and the
     * molang evaluation run on the background pool; only the variable map
     * update happens on the main thread.
     */
    public static void onWheelAnimationStarted(Player player, String modelId, String animationName) {
        if (player == null || modelId == null || animationName == null || animationName.isEmpty()) {
            return;
        }
        UUID uuid = player.getUUID();
        String label = "wheel animation '" + animationName + "' of model '" + modelId + "'";
        onRoamingExpressionAsync(uuid, label, current -> {
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
            return collectRoaming(evaluatedVars);
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
        UUID uuid = player.getUUID();
        String label = "roulette config expression '" + expression + "'";
        onRoamingExpressionAsync(uuid, label, current -> {
            Map<Integer, Double> evaluatedVars = new TreeMap<>();
            Molang.Env env = newEnv(current, evaluatedVars);
            String[] parts = expression.split(";");
            for (String part : parts) {
                if (part == null || part.isBlank()) {
                    continue;
                }
                Molang.compile(part.trim()).eval(env);
            }
            return collectRoaming(evaluatedVars);
        });
    }

    /**
     * Evaluate one roaming expression on the background pool and apply the
     * result on the main thread. {@code compute} returns null to skip the
     * update entirely (e.g. no such animation), or the new roaming map.
     */
    private static void onRoamingExpressionAsync(UUID uuid, String label,
                                                 java.util.function.Function<Map<String, Float>, Map<String, Float>> compute) {
        ROAMING_POOL.execute(() -> {
            try {
                Map<String, Float> current = PLAYER_VARS.get(uuid);
                Map<String, Float> updated = compute.apply(current == null ? java.util.Collections.emptyMap() : current);
                if (updated == null) {
                    return;
                }
                net.minecraft.client.Minecraft.getInstance().execute(() -> applyRoaming(uuid, updated, label));
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to track roaming variables for {}", label, t);
            }
        });
    }

    /** Main thread: replace the player's roaming map and log the change once. */
    private static void applyRoaming(UUID uuid, Map<String, Float> updated, String label) {
        Map<String, Float> current = PLAYER_VARS.computeIfAbsent(uuid, k -> new TreeMap<>());
        synchronized (current) {
            current.clear();
            current.putAll(updated);
        }
        String logKey = uuid + ":" + label + "->" + updated;
        if (LOGGED.add(logKey)) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [roaming] {} updated roaming vars to {}", label, updated);
        }
    }

    /** The tracked roaming values for one player, or an empty map. */
    public static Map<String, Float> getRoaming(Player player) {
        if (player == null) {
            return Collections.emptyMap();
        }
        Map<String, Float> vars = PLAYER_VARS.get(player.getUUID());
        return vars == null ? Collections.emptyMap() : vars;
    }

    /** Forget all tracked roaming state (world leave). */
    public static void clear() {
        PLAYER_VARS.clear();
        LOGGED.clear();
    }

    // ------------------------------------------------------------------
    // Minimal Molang environment
    // ------------------------------------------------------------------

    private static Map<String, Float> collectRoaming(Map<Integer, Double> evaluatedVars) {
        Map<String, Float> updated = new TreeMap<>();
        for (Map.Entry<Integer, Double> entry : evaluatedVars.entrySet()) {
            String name = Molang.nameOf(entry.getKey());
            if (name == null || !name.startsWith("v.roaming.")) {
                continue;
            }
            updated.put(name.substring("v.roaming.".length()), (float) (double) entry.getValue());
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
            outVars.put(Molang.idOf("v.roaming." + entry.getKey()), (double) entry.getValue());
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
                    case "math.abs":
                        return Math.abs(args[0]);
                    case "math.floor":
                        return Math.floor(args[0]);
                    case "math.ceil":
                        return Math.ceil(args[0]);
                    case "math.round":
                        return Math.round(args[0]);
                    case "math.clamp":
                        return Math.max(args[1], Math.min(args[2], args[0]));
                    case "math.max":
                        return Math.max(args[0], args.length > 1 ? args[1] : args[0]);
                    case "math.min":
                        return Math.min(args[0], args.length > 1 ? args[1] : args[0]);
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
