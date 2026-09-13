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

    /**
     * Upper bound on how often a non-local player's YSM script/animation state is
     * evaluated, in Hz; 0 means unlimited (every frame the distance LOD allows).
     *
     * <p>Every evaluation runs the model's molang: query refresh, state machine,
     * keyframe lookup and matrix composition. Frames between evaluations replay the
     * last published pose, so this trades update smoothness for render-thread time -
     * the knob that matters on a phone or a weak GPU.
     *
     * <p>This multiplies with the built-in distance LOD (near players every frame,
     * far ones at 30/10 Hz already), so the effective cadence is the slower of the
     * two. The local player is never limited: its animation is what the player is
     * looking at.
     *
     * <p>Default 0 keeps the previous behaviour exactly. The rate is honoured by
     * carrying the deadline phase rather than re-scheduling from each render frame,
     * because the latter makes the achieved rate sag below the target whenever the
     * frame rate exceeds it (see {@link com.ysmef.compat.animation.EvaluationRateLimiter}).
     */
    public static final ForgeConfigSpec.IntValue ANIMATION_EVAL_RATE_LIMIT_HZ;

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

        ANIMATION_EVAL_RATE_LIMIT_HZ = builder
                .comment("Upper bound on how often a NON-local player's YSM script/animation state is evaluated, in Hz.",
                        "0 = unlimited (every frame the distance LOD allows), which is the previous behaviour.",
                        "Allowed values: 0, or 30-240. Lower values reduce render-thread cost at the price of less",
                        "smooth model updates: frames between evaluations replay the last evaluated pose.",
                        "This caps the built-in distance LOD (near players every frame, far ones at 30/10 Hz), so the",
                        "effective cadence is the slower of the two. The local player is never rate-limited.")
                .defineInRange("animationEvaluationRateLimitHz", 0, 0, 240);

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
