package com.ysmef.compat.realcamera;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.model.runtime.YSMRuntimeModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge to the Real Camera mod (present as "realcamera"): registers one
 * default bind target per converted YSM model so the first-person camera binds
 * to the model's head - target plane and forward vector on the front face (the
 * plane the "Eyes" element lies in), upward vector on the head's west side
 * face (the side across from the front face's right side), roll 90 degrees
 * (all solved at conversion time, see YsmCameraTargetSolver and the runtime
 * JSON "camera" section).
 *
 * Two binding paths exist:
 * - battle mode: Real Camera's probe passes (its own YSMCompat tetrahedral
 *   sampler and the RealCameraCore fallback) render the entity into a
 *   MultiVertexCatcher and match the captured vertices against the bind
 *   targets in ModConfig's TARGET list, so the target must be registered
 *   there (NOT via ModConfig#putBindTarget - that method diverts the target
 *   into the FIXED list whenever a same-named fixed entry exists, which would
 *   leave the probe's target list without the entry; and the probe paths only
 *   ever read the target list). Registration therefore replaces/adds the
 *   entry directly in the target list and persists it with ConfigFile#save
 *   (an F6 toggle reloads the config from disk - without the save the
 *   in-memory registration would be lost for the rest of the session).
 * - non-battle mode: the YSM renderer draws the model itself (not our
 *   converted mesh), so the probe/texture-matching targets never see it.
 *   Instead an API bind function (RealCameraAPI, priority -50, ahead of
 *   RealCamera's own YSMCompat at -100) computes the bind result directly
 *   from the runtime JSON "camera" section (bind-pose eyes position and face
 *   normals, rotated by the entity's interpolated body yaw - exactly
 *   LivingEntityRenderer's body rotation). It uses its own cached BindTarget
 *   instance (full XYZ position binding, rotation left to the player input -
 *   the bind pose cannot track the mouse-driven head, so binding rotation
 *   would lock the view), never touching RealCamera's config lists. Known
 *   limitation: the non-battle binding follows the bind pose, not YSM's
 *   per-frame animation.
 *
 * Everything RealCamera-side is reflective: the mod is an optional runtime
 * neighbor, not a compile-time dependency. Registrations are queued from any
 * thread and drained on the render thread (YSMRenderHook) because RealCamera's
 * config lists are not thread-safe.
 *
 * Also exposes the MultiVertexCatcher check used by YSMMesh: RealCamera's probe
 * passes and first-person body render only see vertices written into their
 * buffer source, so the direct-GL paths must step aside for those draws.
 */
public final class YsmRealCameraBridge {

    private YsmRealCameraBridge() {}

    // ------------------------------------------------------------------
    // Reflective handles (null when Real Camera is absent)
    // ------------------------------------------------------------------

    private static final boolean PRESENT = ModList.get().isLoaded("realcamera");
    private static final Object MOD_CONFIG = findModConfig();
    private static final Method GET_BIND_TARGET_LIST = findMethod("getBindTargetList");
    private static final Method GET_FIXED_TARGET_LIST = findMethod("getFixedTargetList");
    private static final Method TARGET_NAME = findTargetMethod("name");
    private static final Method TARGET_PRIORITY = findTargetMethod("priority");
    private static final Method BIND_TARGET_TARGET_CONFIG = findTargetMethod("targetConfig");
    private static final Method BIND_TARGET_BIND_CONFIG = findTargetMethod("bindConfig");
    private static final Method BIND_TARGET_OFFSETS = findTargetMethod("offsets");
    private static final Method BIND_TARGET_DISABLE_CONFIGS = findTargetMethod("disableConfigs");
    private static final Constructor<?> TARGET_CONFIG_CTOR = findTargetConfigCtor();
    private static final Constructor<?> BIND_CONFIG_CTOR = findBindConfigCtor();
    private static final Constructor<?> OFFSET_CONFIG_CTOR = findOffsetConfigCtor();
    private static final java.lang.reflect.Field OFFSET_ROLL = findOffsetRoll();
    private static final Constructor<?> BIND_TARGET_CTOR = findBindTargetCtor();
    private static final Method CONFIG_SAVE = findConfigSave();
    private static final Class<?> VERTEX_CATCHER_CLASS = findVertexCatcherClass();

    private static final boolean AVAILABLE = PRESENT && MOD_CONFIG != null
            && GET_BIND_TARGET_LIST != null && TARGET_NAME != null && TARGET_PRIORITY != null
            && TARGET_CONFIG_CTOR != null && BIND_CONFIG_CTOR != null
            && OFFSET_CONFIG_CTOR != null && OFFSET_ROLL != null && BIND_TARGET_CTOR != null;

    private static Class<?> findBindTargetClass() {
        try {
            return Class.forName("com.xtracr.realcamera.config.BindTarget");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object findModConfig() {
        try {
            if (!PRESENT) {
                return null;
            }
            Class<?> configFile = Class.forName("com.xtracr.realcamera.config.ConfigFile");
            return configFile.getMethod("config").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(String name, Class<?>... paramTypes) {
        try {
            return MOD_CONFIG == null ? null : MOD_CONFIG.getClass().getMethod(name, paramTypes);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findTargetMethod(String name) {
        try {
            Class<?> target = findBindTargetClass();
            return target == null ? null : target.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Constructor<?> findTargetConfigCtor() {
        try {
            return Class.forName("com.xtracr.realcamera.config.BindTarget$TargetConfig")
                    .getConstructor(float.class, float.class, float.class, float.class, float.class, float.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Constructor<?> findBindConfigCtor() {
        try {
            return Class.forName("com.xtracr.realcamera.config.BindTarget$BindConfig")
                    .getConstructor(boolean.class, boolean.class, boolean.class, boolean.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Constructor<?> findOffsetConfigCtor() {
        try {
            return Class.forName("com.xtracr.realcamera.config.OffsetConfig").getConstructor();
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Field findOffsetRoll() {
        try {
            return Class.forName("com.xtracr.realcamera.config.OffsetConfig").getField("roll");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Constructor<?> findBindTargetCtor() {
        try {
            Class<?> target = findBindTargetClass();
            if (target == null) {
                return null;
            }
            return target.getConstructor(String.class, String.class, int.class, float.class,
                    Class.forName("com.xtracr.realcamera.config.BindTarget$TargetConfig"),
                    Class.forName("com.xtracr.realcamera.config.BindTarget$BindConfig"),
                    Class.forName("com.xtracr.realcamera.config.OffsetConfig"),
                    List.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** ConfigFile#save (persists the in-memory lists; null = keep in-memory only). */
    private static Method findConfigSave() {
        try {
            if (!PRESENT) {
                return null;
            }
            return Class.forName("com.xtracr.realcamera.config.ConfigFile").getMethod("save");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> findVertexCatcherClass() {
        try {
            return PRESENT ? Class.forName("com.xtracr.realcamera.renderer.MultiVertexCatcher") : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // RealCameraAPI bind function (non-battle mode)
    // ------------------------------------------------------------------
    //
    // The function's BindResult carries our OWN cached BindTarget instance
    // (created reflectively, never registered in RealCamera's config lists):
    // BindResult#getOrCreate would otherwise auto-create a blank fixed target
    // (bindConfig = bindY only) which we could never upgrade in place, and the
    // same-named fixed entry would make ModConfig#putBindTarget divert the
    // probe-path registration away from the target list (the battle-mode
    // regression this design had). The cached instance keeps per-session
    // keyboard offset tweaks working (RealCameraCore mutates currentTarget()).

    private static final Method API_REGISTER = findApiRegister();
    private static final Constructor<?> BIND_RESULT_CTOR = findBindResultCtor();
    private static final Method BIND_RESULT_SET_POSITION = findBindResultMethod("setPosition", net.minecraft.world.phys.Vec3.class);
    private static final Method BIND_RESULT_SET_FORWARD = findBindResultMethod("setForward", net.minecraft.world.phys.Vec3.class);
    private static final Method BIND_RESULT_SET_UPWARD = findBindResultMethod("setUpward", net.minecraft.world.phys.Vec3.class);
    private static final Object BIND_RESULT_EMPTY = findBindResultEmpty();
    private static final boolean API_AVAILABLE = AVAILABLE && API_REGISTER != null
            && BIND_RESULT_CTOR != null && BIND_RESULT_SET_POSITION != null
            && BIND_RESULT_SET_FORWARD != null && BIND_RESULT_SET_UPWARD != null && BIND_RESULT_EMPTY != null;
    private static volatile boolean apiRegistered = false;

    /** target name -> our cached API-path BindTarget (never registered in the config lists). */
    private static final Map<String, Object> API_TARGETS = new ConcurrentHashMap<>();

    private static Class<?> findBindResultClass() {
        try {
            return Class.forName("com.xtracr.realcamera.api.BindResult");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Constructor<?> findBindResultCtor() {
        try {
            Class<?> bindResult = findBindResultClass();
            return bindResult == null ? null : bindResult.getConstructor(findBindTargetClass());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findApiRegister() {
        try {
            return Class.forName("com.xtracr.realcamera.api.RealCameraAPI")
                    .getMethod("registerFunction", int.class, java.util.function.BiFunction.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findBindResultMethod(String name, Class<?>... paramTypes) {
        try {
            Class<?> bindResult = findBindResultClass();
            return bindResult == null ? null : bindResult.getMethod(name, paramTypes);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object findBindResultEmpty() {
        try {
            Class<?> bindResult = findBindResultClass();
            return bindResult == null ? null : bindResult.getField("EMPTY").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Register the API bind function once (RealCamera's YSMCompat runs at
     * -100; we run before it). MUST be called from client setup
     * (FMLClientSetupEvent), never from a render path: registering while
     * RealCamera is iterating its function list (its probe functions render
     * the entity, which re-enters our render hooks) crashes the game with a
     * ConcurrentModificationException in RealCameraAPI.computeBindResult.
     */
    public static void initApiFunction() {
        if (!API_AVAILABLE || apiRegistered) {
            return;
        }
        apiRegistered = true;
        try {
            API_REGISTER.invoke(null, -50, (java.util.function.BiFunction<net.minecraft.client.Minecraft, Float, Object>)
                    YsmRealCameraBridge::computeBindResult);
            YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: registered the Real Camera API bind function (non-battle mode)");
        } catch (Throwable t) {
            apiRegistered = false;
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to register the Real Camera API bind function", t);
        }
    }

    private static Object computeBindResult(net.minecraft.client.Minecraft client, float deltaTick) {
        try {
            net.minecraft.world.entity.Entity entity = client.getCameraEntity();
            if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
                return BIND_RESULT_EMPTY;
            }
            // battle mode is served by the probe/capture path (it tracks combat animations)
            if (com.ysmef.compat.renderer.YSMBattleMode.isBattleMode(player)) {
                return BIND_RESULT_EMPTY;
            }
            com.ysmef.compat.renderer.YSMModelAccess.YSMModelRef modelRef =
                    com.ysmef.compat.renderer.YSMModelAccess.getCurrentModel(player);
            if (modelRef == null) {
                return BIND_RESULT_EMPTY;
            }
            YSMRuntimeModel model = YSMRuntimeModel.get(modelRef.modelId());
            if (model == null || model.cameraTarget == null) {
                // Not converted/loaded yet: kick off the lazy conversion (or the
                // verified-cache restore) so the next frames can bind. Cheap:
                // ensureModel dedups pending/failed models and never blocks.
                YSMMeshLibrary.ensureModel(modelRef.modelId());
                return BIND_RESULT_EMPTY;
            }
            YSMRuntimeModel.CameraTarget camera = model.cameraTarget;
            String name = "ysmef_head_" + YSMMeshLibrary.meshIdOf(modelRef.modelId());
            Object target = API_TARGETS.computeIfAbsent(name, key -> createApiTarget(key, camera.roll));
            if (target == null) {
                return BIND_RESULT_EMPTY;
            }
            Object result = BIND_RESULT_CTOR.newInstance(target);

            // The same rotation LivingEntityRenderer applies to the body
            // (180 - interpolated body yaw) around +Y.
            float bodyYaw = net.minecraft.util.Mth.rotLerp(deltaTick, player.yBodyRotO, player.yBodyRot);
            float yawRad = (float) Math.toRadians(180.0f - bodyYaw);
            net.minecraft.world.phys.Vec3 position = new net.minecraft.world.phys.Vec3(
                    camera.eyesX, camera.eyesY, camera.eyesZ).yRot(yawRad);
            net.minecraft.world.phys.Vec3 forward = new net.minecraft.world.phys.Vec3(
                    camera.normalX, camera.normalY, camera.normalZ).yRot(yawRad);
            net.minecraft.world.phys.Vec3 upward = new net.minecraft.world.phys.Vec3(
                    camera.upX, camera.upY, camera.upZ).yRot(yawRad);
            BIND_RESULT_SET_POSITION.invoke(result, position);
            BIND_RESULT_SET_FORWARD.invoke(result, forward);
            BIND_RESULT_SET_UPWARD.invoke(result, upward);
            return result;
        } catch (Throwable t) {
            return BIND_RESULT_EMPTY;
        }
    }

    /**
     * Create the API path's own BindTarget: full XYZ position binding, roll
     * from the solver (90 degrees - applied to the bind rotation used by
     * RealCamera's first-person body render), rotation NOT bound (the bind
     * pose cannot track the mouse-driven head, so the view stays under the
     * player's control). Never registered in RealCamera's config lists.
     */
    private static Object createApiTarget(String name, float roll) {
        try {
            Object targetConfig = TARGET_CONFIG_CTOR.newInstance(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            Object bindConfig = BIND_CONFIG_CTOR.newInstance(true, true, true, false);
            Object offsets = OFFSET_CONFIG_CTOR.newInstance();
            OFFSET_ROLL.setFloat(offsets, roll);
            return BIND_TARGET_CTOR.newInstance(name, "", 0, 0.2f, targetConfig, bindConfig, offsets, List.of());
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to create the Real Camera API bind target '{}'", name, t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Registration queue (any thread -> render thread drain)
    // ------------------------------------------------------------------

    /** Queued registration: target name + texture matcher + camera UVs. */
    private record PendingTarget(String name, String textureId, YSMRuntimeModel.CameraTarget camera) {}

    /** target name -> queued registration, waiting for the render-thread drain. */
    private static final Map<String, PendingTarget> PENDING = new ConcurrentHashMap<>();
    /** target names already registered (or already present in the config). */
    private static final java.util.Set<String> REGISTERED = ConcurrentHashMap.newKeySet();
    private static volatile boolean unavailableLogged = false;
    private static volatile boolean scanned = false;
    private static volatile boolean staleFixedTargetsCleaned = false;

    /** Queues the model's default bind target (called when its runtime model finishes loading). */
    public static void onRuntimeModelLoaded(String modelId, YSMRuntimeModel.CameraTarget cameraTarget) {
        if (!AVAILABLE || modelId == null || cameraTarget == null) {
            return;
        }
        String meshId = YSMMeshLibrary.meshIdOf(modelId);
        enqueue("ysmef_head_" + meshId, YSMMeshLibrary.textureIdPrefixOf(modelId), cameraTarget);
    }

    private static void enqueue(String name, String textureId, YSMRuntimeModel.CameraTarget cameraTarget) {
        if (REGISTERED.contains(name)) {
            return;
        }
        PENDING.putIfAbsent(name, new PendingTarget(name, textureId, cameraTarget));
    }

    /**
     * Registers every queued bind target. Must run on the render thread
     * (RealCamera's config lists are plain ArrayLists read by its probe passes).
     * The first drain also scans every converted model's runtime JSON, so
     * models converted before they are first drawn get their target too, and
     * removes the stale same-named FIXED-list entries earlier builds created
     * through BindResult#getOrCreate (they diverted the probe registration
     * away from the target list and are unused by the current API path).
     */
    public static void drainPending() {
        if (!AVAILABLE) {
            return;
        }
        if (!scanned) {
            scanned = true;
            scanConvertedModels();
        }
        if (!staleFixedTargetsCleaned) {
            staleFixedTargetsCleaned = true;
            cleanStaleFixedTargets();
        }
        if (PENDING.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<String, PendingTarget> entry : PENDING.entrySet()) {
            PendingTarget pending = entry.getValue();
            PENDING.remove(entry.getKey());
            try {
                changed |= registerTarget(pending);
            } catch (Throwable t) {
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to register the Real Camera bind target '{}'", pending.name(), t);
            }
        }
        if (changed) {
            saveConfig();
        }
    }

    /** Persist the config after registrations, so F6 (toggle reloads from disk) keeps them. */
    private static void saveConfig() {
        if (CONFIG_SAVE == null) {
            return;
        }
        try {
            CONFIG_SAVE.invoke(null);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: failed to save the Real Camera config", t);
        }
    }

    /**
     * One-time migration: drop the fixed-list entries earlier builds created
     * for our targets (auto-created blanks / diverted probe registrations).
     * The current API path uses its own cached target and the probe path reads
     * the target list, so those entries serve no purpose; leaving them would
     * only confuse the model-view GUI.
     */
    @SuppressWarnings("unchecked")
    private static void cleanStaleFixedTargets() {
        if (GET_FIXED_TARGET_LIST == null) {
            return;
        }
        try {
            List<Object> fixedList = (List<Object>) GET_FIXED_TARGET_LIST.invoke(MOD_CONFIG);
            boolean removed = fixedList.removeIf(target -> {
                try {
                    Object name = TARGET_NAME.invoke(target);
                    return name != null && name.toString().startsWith("ysmef_head_");
                } catch (Throwable t) {
                    return false;
                }
            });
            if (removed) {
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: removed the stale fixed-list Real Camera bind target(s) created by earlier builds");
                saveConfig();
            }
        } catch (Throwable t) {
            if (!unavailableLogged) {
                unavailableLogged = true;
                YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: Real Camera fixed-target list cleanup failed", t);
            }
        }
    }

    /**
     * Scan the generated runtime JSONs (one per converted model) for "camera"
     * sections and queue their bind targets. Runs once per session on the
     * render thread; a model whose runtime JSON is (re)written later notifies
     * the bridge itself (onRuntimeModelLoaded).
     */
    private static void scanConvertedModels() {
        java.nio.file.Path runtimeDir = YSMMeshLibrary.getRuntimeEntityDir();
        if (runtimeDir == null || !java.nio.file.Files.isDirectory(runtimeDir)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(runtimeDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(YsmRealCameraBridge::scanRuntimeFile);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: Real Camera bind-target scan failed", t);
        }
    }

    private static void scanRuntimeFile(java.nio.file.Path file) {
        try {
            String meshId = YSMMeshLibrary.getRuntimeEntityDir().relativize(file).toString()
                    .replace('\\', '/');
            meshId = meshId.substring(0, meshId.length() - ".json".length());
            com.google.gson.JsonObject root = com.google.gson.JsonParser
                    .parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
            if (!root.has("camera") || !root.get("camera").isJsonObject()) {
                return;
            }
            com.google.gson.JsonObject camera = root.getAsJsonObject("camera");
            YSMRuntimeModel.CameraTarget target = new YSMRuntimeModel.CameraTarget(
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
            enqueue("ysmef_head_" + meshId, "textures/" + meshId + "/", target);
        } catch (Throwable ignored) {
            // one broken runtime JSON must not block the other models
        }
    }

    /**
     * Register the model's bind target in RealCamera's TARGET list (the list
     * the probe passes scan). Returns true when the config changed. Never
     * touches the fixed target list.
     */
    private static boolean registerTarget(PendingTarget pending) throws Exception {
        if (!REGISTERED.add(pending.name())) {
            return false;
        }
        YSMRuntimeModel.CameraTarget camera = pending.camera();
        List<Object> targetList = targetList();
        if (targetList == null) {
            REGISTERED.remove(pending.name());
            return false;
        }
        Object existing = findTarget(targetList, pending.name());
        if (existing != null) {
            // Already registered with the current solver's UVs: nothing to do.
            // Stale UVs (older solver output) are refreshed below - the offsets
            // (position/rotation tweaks the user made in RealCamera's GUI) are
            // carried over, only the solver-owned UVs are replaced.
            Object currentConfig = BIND_TARGET_TARGET_CONFIG.invoke(existing);
            if (sameUv(currentConfig, camera)) {
                return false;
            }
            Object offsets = BIND_TARGET_OFFSETS.invoke(existing);
            Object disableConfigs = BIND_TARGET_DISABLE_CONFIGS.invoke(existing);
            Object targetConfig = TARGET_CONFIG_CTOR.newInstance(
                    camera.forwardU, camera.forwardV, camera.upwardU, camera.upwardV, camera.posU, camera.posV);
            Object bindConfig = BIND_TARGET_BIND_CONFIG.invoke(existing);
            Object replacement = BIND_TARGET_CTOR.newInstance(
                    pending.name(), pending.textureId(), TARGET_PRIORITY.invoke(existing) instanceof Integer p ? p : 10,
                    0.2f, targetConfig, bindConfig, offsets, disableConfigs);
            int index = indexOf(targetList, pending.name());
            if (index >= 0) {
                targetList.set(index, replacement);
            } else {
                targetList.add(replacement);
                sortTargetList(targetList);
            }
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: refreshed the Real Camera bind target '{}' with the current solver UVs",
                    pending.name());
            return true;
        }
        Object targetConfig = TARGET_CONFIG_CTOR.newInstance(
                camera.forwardU, camera.forwardV, camera.upwardU, camera.upwardV, camera.posU, camera.posV);
        Object bindConfig = BIND_CONFIG_CTOR.newInstance(true, true, true, true);
        Object offsets = OFFSET_CONFIG_CTOR.newInstance();
        OFFSET_ROLL.setFloat(offsets, camera.roll);
        Object target = BIND_TARGET_CTOR.newInstance(
                pending.name(), pending.textureId(), 10, 0.2f,
                targetConfig, bindConfig, offsets, List.of());
        targetList.add(target);
        sortTargetList(targetList);
        YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: registered the Real Camera bind target '{}' "
                        + "(front face = eyes plane for position + forward, west side face for upward, roll {} deg)",
                pending.name(), camera.roll);
        return true;
    }

    /** RealCamera's modifiable bind-target list (ModConfig#getBindTargetList), or null. */
    @SuppressWarnings("unchecked")
    private static List<Object> targetList() {
        try {
            return (List<Object>) GET_BIND_TARGET_LIST.invoke(MOD_CONFIG);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The index of the same-named target in the given list, or -1. */
    private static int indexOf(List<Object> targetList, String name) {
        for (int i = 0; i < targetList.size(); i++) {
            try {
                if (name.equals(TARGET_NAME.invoke(targetList.get(i)))) {
                    return i;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static Object findTarget(List<Object> targetList, String name) {
        int index = indexOf(targetList, name);
        return index >= 0 ? targetList.get(index) : null;
    }

    /** Keep the target list sorted by descending priority (ModConfig#putBindTarget's behavior). */
    private static void sortTargetList(List<Object> targetList) {
        targetList.sort((a, b) -> {
            try {
                return Integer.compare((Integer) TARGET_PRIORITY.invoke(b), (Integer) TARGET_PRIORITY.invoke(a));
            } catch (Throwable t) {
                return 0;
            }
        });
    }

    /** Whether the existing targetConfig already carries the solver's current UVs. */
    private static boolean sameUv(Object targetConfig, YSMRuntimeModel.CameraTarget camera) {
        try {
            return uv(targetConfig, "posU") == camera.posU && uv(targetConfig, "posV") == camera.posV
                    && uv(targetConfig, "forwardU") == camera.forwardU && uv(targetConfig, "forwardV") == camera.forwardV
                    && uv(targetConfig, "upwardU") == camera.upwardU && uv(targetConfig, "upwardV") == camera.upwardV;
        } catch (Throwable t) {
            return false;
        }
    }

    private static float uv(Object targetConfig, String accessor) throws Exception {
        return (Float) targetConfig.getClass().getMethod(accessor).invoke(targetConfig);
    }

    // ------------------------------------------------------------------
    // Vertex-catcher detection (YSMMesh render routing)
    // ------------------------------------------------------------------

    /**
     * Whether the buffer source is Real Camera's MultiVertexCatcher (its
     * tetrahedral binding probes and its first-person body render). Those
     * passes only see vertices written into their own buffers, so YSMMesh must
     * route them to the buffer-writing drawPosed path instead of the direct-GL
     * paths (GPU skinning / CPU skinning / compute shaders).
     */
    public static boolean isCameraCapture(MultiBufferSource bufferSources) {
        return VERTEX_CATCHER_CLASS != null && VERTEX_CATCHER_CLASS.isInstance(bufferSources);
    }
}
