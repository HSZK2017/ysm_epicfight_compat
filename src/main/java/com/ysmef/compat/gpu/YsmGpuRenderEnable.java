package com.ysmef.compat.gpu;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import net.minecraftforge.fml.ModList;

/**
 * Gates the compat mod's GPU skinning path depending on which YSM fork is loaded:
 *
 * - ModernYSM (the open-source fork with its own GPU renderer): the compat GPU
 *   path is linked to ModernYSM's own render toggles (GeneralConfig
 *   USE_GPU_RENDERER and USE_COMPATIBILITY_RENDERER, read reflectively since
 *   ModernYSM is not a compile-time dependency). When ModernYSM's GPU rendering
 *   is turned off (config screen, config file, or its own auto-disable), the
 *   compat GPU path turns off with it, and vice versa - both mods stay in sync.
 * - OpenYSM / LegacyYSM: the compat GPU path follows the mod's own client
 *   config (enableGpuRender), mirroring ModernYSM's UseGpuRenderer toggle.
 *   Like ModernYSM, the toggle is auto-disabled when the GPU path proves
 *   unavailable at runtime (see disableIfOwned).
 */
public final class YsmGpuRenderEnable {

    public enum YsmFork { MODERN_YSM, OPEN_YSM, LEGACY_YSM, NONE }

    private static final String YSM_MOD_ID = "yes_steve_model";
    private static final String MODERN_GENERAL_CONFIG = "com.elfmcys.yesstevemodel.config.GeneralConfig";

    private static volatile YsmFork fork = null;

    private YsmGpuRenderEnable() {}

    /**
     * Which YSM fork is installed. Detected once via ModList + class presence:
     * ModernYSM is identified by its GPU package (rip.ysm.gpu.*), OpenYSM by its
     * un-obfuscated render event class, everything else (obfuscated classes) is
     * LegacyYSM.
     */
    public static YsmFork fork() {
        YsmFork f = fork;
        if (f != null) {
            return f;
        }
        synchronized (YsmGpuRenderEnable.class) {
            f = fork;
            if (f == null) {
                f = detect();
                fork = f;
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: detected YSM fork '{}' - GPU render toggle: {}",
                        f, f == YsmFork.MODERN_YSM
                                ? "linked to ModernYSM UseGpuRenderer/UseCompatibilityRenderer"
                                : "own enableGpuRender config");
            }
        }
        return f;
    }

    /**
     * Whether the compat GPU skinning path may render right now:
     * - ModernYSM: its own GPU toggles decide (USE_GPU_RENDERER on and
     *   USE_COMPATIBILITY_RENDERER off, the exact same gate ModernYSM uses for
     *   its own GpuRenderPath);
     * - OpenYSM / LegacyYSM: the mod's own enableGpuRender config.
     */
    public static boolean isEnabled() {
        if (fork() == YsmFork.MODERN_YSM) {
            return modernGpuEnabled();
        }
        return YSMCompatConfig.ENABLE_GPU_RENDER.get();
    }

    /**
     * Auto-disable the own GPU toggle when the GPU path is unavailable, mirroring
     * ModernYSM's behavior (it calls USE_GPU_RENDERER.set(false) when the GPU
     * capability check fails). Only touches the own config - with ModernYSM the
     * toggle is owned by ModernYSM itself.
     */
    public static void disableIfOwned() {
        if (fork() == YsmFork.MODERN_YSM) {
            return;
        }
        try {
            if (YSMCompatConfig.ENABLE_GPU_RENDER.get()) {
                YSMCompatConfig.ENABLE_GPU_RENDER.set(false);
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: GPU skinning path unavailable, disabled 'enableGpuRender' (mirrors ModernYSM's auto-disable)");
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    private static YsmFork detect() {
        ModList modList = ModList.get();
        if (modList == null || !modList.isLoaded(YSM_MOD_ID)) {
            return YsmFork.NONE;
        }
        if (classExists("rip.ysm.gpu.GpuCapability") || classExists("rip.ysm.gpu.GpuRenderPath")) {
            return YsmFork.MODERN_YSM;
        }
        if (classExists("com.elfmcys.yesstevemodel.client.event.ReplacePlayerRenderEvent")) {
            return YsmFork.OPEN_YSM;
        }
        return YsmFork.LEGACY_YSM;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, YsmGpuRenderEnable.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // ModernYSM toggle linkage (reflective)
    // ------------------------------------------------------------------

    /** Cached reflective handles (resolved once) + a short TTL for the boolean
     * result: the old implementation called getField()/get() per drawn player
     * per frame, allocating a Field/Method object every call. The toggles only
     * change via ModernYSM's config screen, so a 250 ms TTL (mirroring the
     * Iris shader-pack check) makes the per-frame cost a volatile read. */
    private static volatile java.lang.reflect.Field MODERN_GPU_FIELD;
    private static volatile java.lang.reflect.Field MODERN_COMPAT_FIELD;
    private static volatile java.lang.reflect.Method BOOLEAN_VALUE_GET;
    private static volatile boolean modernLookupFailed = false;
    private static long modernToggleCheckedAtNanos = 0;
    private static boolean modernGpuEnabledCache = true;

    private static boolean modernGpuEnabled() {
        long now = System.nanoTime();
        if (now - modernToggleCheckedAtNanos < 250_000_000L) {
            return modernGpuEnabledCache;
        }
        modernToggleCheckedAtNanos = now;
        modernGpuEnabledCache = readModernGpuEnabled();
        return modernGpuEnabledCache;
    }

    private static boolean readModernGpuEnabled() {
        java.lang.reflect.Field gpuField = MODERN_GPU_FIELD;
        java.lang.reflect.Field compatField = MODERN_COMPAT_FIELD;
        java.lang.reflect.Method getMethod = BOOLEAN_VALUE_GET;
        if (modernLookupFailed) {
            return true;
        }
        if (gpuField == null || compatField == null || getMethod == null) {
            synchronized (YsmGpuRenderEnable.class) {
                if (modernLookupFailed) {
                    return true;
                }
                gpuField = MODERN_GPU_FIELD;
                compatField = MODERN_COMPAT_FIELD;
                getMethod = BOOLEAN_VALUE_GET;
                if (gpuField == null || compatField == null || getMethod == null) {
                    try {
                        Class<?> configClass = Class.forName(MODERN_GENERAL_CONFIG, false, YsmGpuRenderEnable.class.getClassLoader());
                        gpuField = configClass.getField("USE_GPU_RENDERER");
                        compatField = configClass.getField("USE_COMPATIBILITY_RENDERER");
                        getMethod = gpuField.get(null).getClass().getMethod("get");
                        MODERN_GPU_FIELD = gpuField;
                        MODERN_COMPAT_FIELD = compatField;
                        BOOLEAN_VALUE_GET = getMethod;
                    } catch (Throwable t) {
                        modernLookupFailed = true;
                        YSMEpicFightCompat.LOGGER.warn(
                                "YSM-EF Compat: cannot read ModernYSM's GPU renderer toggles, assuming GPU rendering enabled", t);
                        return true;
                    }
                }
            }
        }
        boolean gpuOn = boolOf(fieldValue(gpuField, null), true);
        boolean compatOn = boolOf(fieldValue(compatField, null), false);
        return gpuOn && !compatOn;
    }

    /** Read a static toggle field's value; null on failure (boolOf applies the fallback). */
    private static Object fieldValue(java.lang.reflect.Field field, Object fallback) {
        try {
            Object value = field.get(null);
            return value == null ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Read a ForgeConfigSpec.BooleanValue via its get() method; fall back to the default on failure. */
    private static boolean boolOf(Object toggle, boolean fallback) {
        if (toggle == null) {
            return fallback;
        }
        try {
            java.lang.reflect.Method get = BOOLEAN_VALUE_GET != null
                    ? BOOLEAN_VALUE_GET
                    : toggle.getClass().getMethod("get");
            Object value = get.invoke(toggle);
            return value instanceof Boolean b ? b : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
