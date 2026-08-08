package com.ysmef.compat.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class YSMCompatConfig {

    public static final ForgeConfigSpec CLIENT_SPEC;

    /**
     * ModernYSM-style GPU skinning: draw converted YSM meshes with a bone SSBO
     * + custom skinning shader (one glDrawArrays per model, vertex skinning on
     * the GPU), instead of Epic Fight's per-frame compute dispatch. Falls back
     * to Epic Fight's compute path automatically when unavailable.
     */
    public static final ForgeConfigSpec.BooleanValue ENABLE_GPU_RENDER;

    /**
     * ModernYSM-style lazy model cache: maximum number of YSM models kept loaded
     * in memory. Least-recently-used models are evicted (GPU buffers + textures
     * released) and re-registered from the verified on-disk cache on next use.
     */
    public static final ForgeConfigSpec.IntValue LAZY_MODEL_CACHE_SIZE;

    /**
     * Evaluate YSM molang scripts on a background thread for entities other than
     * the local player (double-buffered result state, ModernYSM-style), so the
     * script evaluation no longer runs on the render thread every frame.
     */
    public static final ForgeConfigSpec.BooleanValue ENABLE_SCRIPT_ASYNC_EVAL;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("YSM Epic Fight Compat - Client Configuration").push("client");

        ENABLE_GPU_RENDER = builder
                .comment("Render YSM meshes with the GPU skinning path (bone SSBO + skinning shader, ported from ModernYSM/OpenYSM).",
                        "The vertex skinning moves fully to the GPU (one draw call per model) instead of Epic Fight's per-frame compute dispatch.",
                        "Falls back to Epic Fight's compute-shader path automatically when the GPU path is unavailable.",
                        "Disable only when it causes rendering problems on your system.")
                .define("enableGpuRender", true);

        LAZY_MODEL_CACHE_SIZE = builder
                .comment("Maximum number of YSM models kept loaded in memory (ModernYSM-style LRU cache).",
                        "Models beyond this limit are evicted least-recently-used first: their GPU buffers, textures and",
                        "compiled scripts are released, and they are re-registered from the verified on-disk cache on next use.",
                        "Lower values save VRAM/RAM at the cost of a re-load when a model becomes visible again.")
                .defineInRange("lazyModelCacheSize", 64, 8, 512);

        ENABLE_SCRIPT_ASYNC_EVAL = builder
                .comment("Evaluate YSM molang scripts on a background thread for entities other than the local player",
                        "(double-buffered result state, ModernYSM-style). The local player and battle-mode default forms",
                        "always evaluate on the render thread; falls back automatically if a script fails off-thread.")
                .define("scriptAsyncEval", true);

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
