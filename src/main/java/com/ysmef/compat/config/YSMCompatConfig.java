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

    /**
     * Whether the hanging parts of a converted model - hair, tails, skirts, capes -
     * swing after the body instead of being glued to it.
     *
     * <p>Off by default, and deliberately so: it is a look, not a fix. The pieces to
     * swing are found by reading the model's bone names, so a model that names its
     * hair unconventionally either gets no motion or gets motion on the wrong bone, and
     * the only way to judge the result is to look at it. A per-model override is the
     * intended answer for the second case.
     *
     * <p>Cost is one damped-spring step per chain per full evaluation, plus one
     * identity check per bone on the compose path; the chain classification itself runs
     * once per model, not per frame.
     */
    public static final ForgeConfigSpec.BooleanValue ENABLE_SECONDARY_MOTION;

    /**
     * How much of one model's hanging geometry may be simulated at once, in particles.
     *
     * <p>The cloth is per-vertex work on a phone, so this is the knob to turn down if a
     * busy model costs too much. Pieces are taken in the classification's order until the
     * budget runs out, and what was dropped is reported under the '[cloth]' tag.
     */
    public static final ForgeConfigSpec.IntValue SECONDARY_MOTION_MAX_PARTICLES;
    /** How strongly the cloth resists stretching: constraint iterations per step. */
    public static final ForgeConfigSpec.IntValue SECONDARY_MOTION_ITERATIONS;
    /** Gravity on the cloth, blocks/s^2. */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_GRAVITY;
    /** Velocity kept between frames. Lower settles sooner, higher flows further. */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_DAMPING;
    /** Radius of the body spheres the cloth is kept out of, blocks. */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_BODY_RADIUS;
    /** How many pieces one model may simulate. */
    public static final ForgeConfigSpec.IntValue SECONDARY_MOTION_MAX_CHAINS;

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

        ENABLE_SECONDARY_MOTION = builder
                .comment("Simulate the hanging parts of a converted model - hair, tails, skirts, capes - as cloth, so they",
                        "swing, bend and settle after the body instead of being glued to it.",
                        "Off by default: which pieces are cloth is inferred from the model's bone names, so a model that",
                        "names its hair unconventionally may get no motion or motion on the wrong piece - judge it by looking.",
                        "The converted mesh is what Epic Fight draws in battle mode, so that is where this is visible; each",
                        "model reports what it simulated once under the '[cloth]' log tag, to tell 'no motion' from",
                        "'nothing classified'. The settings below shape it and are read live.")
                .define("enableSecondaryMotion", false);

        SECONDARY_MOTION_MAX_PARTICLES = builder
                .comment("How many cloth particles one model may simulate at once, or 0 for none.",
                        "This is the cost knob: the solve is per-vertex, and a model whose hair is a few thousand",
                        "vertices will spend the frame on it at high values. Pieces are taken in order until the",
                        "budget runs out, and the ones dropped are reported under the '[cloth]' tag.")
                .defineInRange("secondaryMotionMaxParticles", 4000, 0, 40000);

        SECONDARY_MOTION_ITERATIONS = builder
                .comment("Constraint iterations per step: how strongly the cloth resists stretching.",
                        "More is stiffer and costs linearly. Below about 4 a strand stretches visibly; above about 16",
                        "it stops looking any different.")
                .defineInRange("secondaryMotionIterations", 8, 1, 32);

        SECONDARY_MOTION_GRAVITY = builder
                .comment("Gravity on the cloth, blocks/s^2. Zero makes it float; higher makes it hang and trail heavily.")
                .defineInRange("secondaryMotionGravity", 14.0, 0.0, 64.0);

        SECONDARY_MOTION_DAMPING = builder
                .comment("Velocity kept from one frame to the next, as a fraction.",
                        "1.0 never loses energy and rings forever; lower settles sooner. Around 0.86 reads as cloth;",
                        "below about 0.5 it looks like it is moving through syrup.")
                .defineInRange("secondaryMotionDamping", 0.86, 0.0, 1.0);

        SECONDARY_MOTION_BODY_RADIUS = builder
                .comment("Radius of the body spheres the cloth is pushed out of, in blocks.",
                        "This is what keeps a skirt out of a thigh and hair out of a shoulder. Too large and the cloth",
                        "is held visibly away from the body; too small and it passes through.")
                .defineInRange("secondaryMotionBodyRadius", 0.22, 0.0, 0.6);

        SECONDARY_MOTION_MAX_CHAINS = builder
                .comment("How many hanging pieces of one model may be simulated, or 0 for none.",
                        "The classifier keeps only the top of each piece, so real models land at 4-24; this is the",
                        "backstop for a model whose bones are named pathologically.")
                .defineInRange("secondaryMotionMaxChains", 24, 0, 128);

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
