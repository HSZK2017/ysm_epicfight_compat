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

    /**
     * Suppress YSM's extra player render (the corner paperdoll overlay that
     * mirrors the player's actions in real time) while the local player is in
     * Epic Fight battle mode.
     *
     * The overlay renders the player through the entity render dispatcher every
     * frame. In battle mode that dispatches to this mod's patched Epic Fight
     * renderer, i.e. a SECOND full EF render pipeline per frame (armature pose,
     * layers, mesh draw) on top of the in-world one - measured 20-30 FPS with
     * the overlay enabled vs 100+ FPS disabled. In battle mode the player model
     * is already visible in-world, so the overlay is suppressed by default.
     */
    public static final ForgeConfigSpec.BooleanValue DISABLE_EXTRA_PLAYER_IN_BATTLE_MODE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("YSM Epic Fight Compat - Client Configuration").push("client");

        ENABLE_GPU_RENDER = builder
                .comment("Render YSM meshes with the GPU skinning path (bone SSBO + skinning shader, ported from ModernYSM/OpenYSM).",
                        "When ModernYSM is installed, this option is ignored: the toggle is linked to ModernYSM's own",
                        "'UseGpuRenderer' / 'UseCompatibilityRenderer' client config, so both mods enable and disable",
                        "their GPU rendering together (including ModernYSM's runtime auto-disable).",
                        "With OpenYSM or LegacyYSM this option mirrors ModernYSM's UseGpuRenderer toggle and, like",
                        "ModernYSM, is auto-disabled when the GPU path is unavailable at runtime.",
                        "The Epic Fight compute-shader path is used as the fallback automatically.")
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

        DISABLE_EXTRA_PLAYER_IN_BATTLE_MODE = builder
                .comment("Suppress YSM's extra player render (the corner paperdoll that mirrors the player's actions)",
                        "while in Epic Fight battle mode. The paperdoll renders the player through the entity render",
                        "dispatcher every frame, which in battle mode runs a second full Epic Fight render pipeline",
                        "per frame (measured 20-30 FPS with the paperdoll enabled vs 100+ FPS disabled).",
                        "The player model is already visible in-world during battle, so the paperdoll is off by default.")
                .define("disableExtraPlayerInBattleMode", true);

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
