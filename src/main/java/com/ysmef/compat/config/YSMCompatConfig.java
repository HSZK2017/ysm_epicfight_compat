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
     * How strongly a swinging piece is pulled back to the animated pose, 1/s^2.
     *
     * <p>Read on every simulation step rather than captured once, so the shape
     * settings below can be tuned in the config file and felt without restarting.
     */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_STIFFNESS;
    /** How fast a swinging piece loses its own velocity, 1/s. */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_DAMPING;
    /** Downward acceleration on every swinging piece, blocks/s^2. */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_GRAVITY;
    /** The same quantity for the pendulum dynamics, under its own key (see the comment). */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_GRAVITY_ACCELERATION;
    /** How strongly the air pushes a swinging piece, 1/(blocks/s). */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_AIR_DRAG;
    /** Whether swinging pieces are kept out of the model's own body volumes. */
    public static final ForgeConfigSpec.BooleanValue SECONDARY_MOTION_COLLISION;
    /** Ceiling on how far a segment may bend from its animated pose, degrees. */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_MAX_ANGLE_DEGREES;
    /** The same ceiling for the top of a hanging piece, degrees. */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_MAX_ANGLE_ROOT_DEGREES;
    /** How many bones of one model may swing at once. */
    public static final ForgeConfigSpec.IntValue SECONDARY_MOTION_MAX_CHAINS;
    /**
     * How much of the world's downward direction the spring of a hanging piece pulls toward,
     * as a fraction of each piece's own category weight.
     */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_GRAVITY_FOLLOW;
    /** How many cloth particles one model may simulate. */
    public static final ForgeConfigSpec.IntValue SECONDARY_MOTION_MAX_PARTICLES;
    /** Constraint-relaxation passes per cloth substep. */
    public static final ForgeConfigSpec.IntValue SECONDARY_MOTION_ITERATIONS;
    /** Radius of the body volume the position-based cloth keeps its particles out of, blocks. */
    public static final ForgeConfigSpec.DoubleValue SECONDARY_MOTION_BODY_RADIUS;

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
                        "Allowed values: 0 (unlimited), or 1-240. Lower values reduce render-thread cost at the price of less",
                        "smooth model updates: frames between evaluations replay the last evaluated pose.",
                        "This caps the built-in distance LOD (near players every frame, far ones at 30/10 Hz), so the",
                        "effective cadence is the slower of the two. The local player is never rate-limited.")
                .defineInRange("animationEvaluationRateLimitHz", 0, 0, 240);

        ENABLE_SECONDARY_MOTION = builder
                .comment("Let the hanging parts of a converted model - hair, tails, skirts, capes - swing after the body",
                        "instead of being glued to it.",
                        "Which bones swing is decided from the model author's own declaration first: the animations the",
                        "model's animation controllers play, plus YSM's bundled default controller set (which is what",
                        "plays 'Hair_Physics'), plus the parallel* family. A candidate animation is only accepted when",
                        "it actually drives a bone this model has geometry for - a rig that matches nothing is rejected",
                        "with a log line rather than silently freezing the model. Only a model that declares nothing",
                        "usable falls back to classifying bone names, which is where a wrong bone can still be picked -",
                        "judge such a model by looking.",
                        "Every bone of a piece is integrated as a pendulum about its own pivot with a moment arm, mass,",
                        "gravity, the author's spring, air drag and collision against the model's own body volumes, so a",
                        "piece curls and drapes instead of rotating as one rigid card. The author's physics animation is",
                        "never replayed as keyframes: it supplies the bone list and each bone's spring tuning, and the",
                        "motion itself is always integrated.",
                        "The converted mesh is what Epic Fight draws in battle mode, so that is where this is visible;",
                        "each model reports its segments once under the '[physics]' log tag, which is what tells 'no",
                        "motion' apart from 'nothing classified'. The settings below shape it and are read live.",
                        "Changing any of this takes effect on the NEXT conversion: delete the cached runtime models",
                        "under config/ysm_epicfight_compat/resourcepack/assets/ysm_epicfight_compat/ysm_runtime/ and",
                        "restart, or the old cached JSON keeps being drawn.")
                .define("enableSecondaryMotion", true);

        // The shape of the swing is a matter of taste, and taste is not something a
        // default can settle: these are the whole of it, separated so a model that looks
        // wrong can be corrected without a rebuild.
        SECONDARY_MOTION_STIFFNESS = builder
                .comment("Default spring stiffness for a piece the model did not tune itself, in 1/s^2.",
                        "Converted to the pendulum's natural frequency as sqrt(stiffness) / 2pi Hz, so the default",
                        "220 is about 2.4 Hz. A model whose physics animation calls ysm.second_order overrides this",
                        "per bone with the frequency its author wrote.")
                .defineInRange("secondaryMotionStiffness", 220.0, 10.0, 2000.0);

        SECONDARY_MOTION_DAMPING = builder
                .comment("Default damping ratio for a piece the model did not tune itself.",
                        "Converted as damping / (2 * sqrt(stiffness)), so the default 24 against stiffness 220 is a",
                        "ratio of 0.81 - a swing that settles in about a second. 1.0 is critically damped and cannot",
                        "overshoot; the authored coefficient of ysm.second_order overrides this per bone.")
                .defineInRange("secondaryMotionDamping", 24.0, 0.0, 200.0);

        SECONDARY_MOTION_GRAVITY = builder
                .comment("Gravity for the cloth solver (the position-based one, which drives garments as a grid of",
                        "particles and keeps them out of the body's own volumes), in blocks/s^2.",
                        "This key does NOT affect the pendulum solver - hair, tails and the chain-based skirts -",
                        "which reads secondaryMotionGravityAcceleration instead, because the number means something",
                        "different there (a restoring acceleration on a joint) and reusing one key for both would",
                        "silently apply a value tuned for one under the other's meaning.",
                        "Note that the cloth solver is currently built but not driven by the render path, so today",
                        "this key changes nothing visible; it is kept, and kept honest, for when that is wired up.")
                .defineInRange("secondaryMotionGravity", 8.0, 0.0, 64.0);

        SECONDARY_MOTION_GRAVITY_ACCELERATION = builder
                .comment("Downward acceleration acting on every swinging piece, in blocks/s^2.",
                        "Real gravity: this is what makes hair hang instead of sticking out where the pose left it.",
                        "Minecraft's own entity gravity is 32; a light, damped piece of hair reads better a little",
                        "below that, and a skirt a little above. Zero makes the pieces weightless and they will sit",
                        "wherever the body throws them. Separate from secondaryMotionGravity, which is the cloth",
                        "solver's gravity and not this one - they are different quantities, so neither key is read",
                        "into the other.")
                .defineInRange("secondaryMotionGravityAcceleration", 24.0, 0.0, 64.0);

        SECONDARY_MOTION_AIR_DRAG = builder
                .comment("How strongly the air pushes a swinging piece, in 1/(blocks/s).",
                        "The air in the model's own frame moves backwards at the entity's speed, so this is what",
                        "makes a skirt flare and hair stream while running, and what makes a long tail trail in a",
                        "dash. Zero disables it and leaves only the body's own acceleration to move the pieces.",
                        "This is the first knob to turn if trailing pieces sit pinned against a swing limit while you",
                        "run. Measured on a real skirt panel at 5 blocks/s, with the default root limit of 20 degrees:",
                        "0.9 (the default) drives all 22 panels past that root limit, the worst reaching 54.8 degrees;",
                        "0.45 leaves the worst at 35.3; 0.3 at 25.3; and about 0.2 keeps every panel inside 20 degrees.",
                        "So 0.2 reads as cloth held close to the body and 0.9 as cloth thrown out behind a sprint, and",
                        "the honest default for 'walking should not already pin my hair' is nearer 0.2 than 0.9.")
                .defineInRange("secondaryMotionAirDrag", 0.9, 0.0, 8.0);

        SECONDARY_MOTION_GRAVITY_FOLLOW = builder
                .comment("How strongly a hanging piece is pulled toward the world's downward direction rather than",
                        "toward the direction the animation poses it in, as a fraction of the piece's own weight.",
                        "The pendulum's spring has a target direction, and that target is a blend of the posed rest",
                        "direction and the world's vertical: at 1 the spring points straight down and the piece hangs",
                        "from its pivot with the pose deciding nothing, at 0 the spring follows the pose exactly and",
                        "gravity can only displace the piece from it - which is what the old model did, and why a skirt",
                        "on a body leaning sixty degrees read as part of the body rather than as cloth.",
                        "This key scales the per-category weight: cloth and skirt panels are classified at 0.92, tails",
                        "at 0.80 and hair at 0.60, because a lock of hair hangs from a skull and has its own volume,",
                        "while a skirt panel is expected to hang toward the ground. Set it to 0 to switch the whole",
                        "mechanism off and get the pre-existing behaviour back, bit for bit.")
                .defineInRange("secondaryMotionGravityFollow", 1.0, 0.0, 1.0);

        SECONDARY_MOTION_MAX_PARTICLES = builder
                .comment("How many cloth particles one model may simulate, or 0 for none.",
                        "Counted in particles, not pieces: a skirt panel's cloth is a grid of them, so a long coat",
                        "spends thousands. This is the backstop for a model whose garment would otherwise cost more",
                        "than the frame has to give, and it is deliberately generous - 4000 - because leaving part",
                        "of a garment unsimulated is visible, while a lower number only saves time.")
                .defineInRange("secondaryMotionMaxParticles", 4000, 0, 65536);

        SECONDARY_MOTION_ITERATIONS = builder
                .comment("Constraint-relaxation passes per cloth substep.",
                        "Each pass re-satisfies the links' rest lengths and the body volumes after the integration",
                        "moved the particles; more passes mean stiffer cloth and fewer passes mean stretchier. Eight",
                        "is what a garment needs to stop visibly stretching under its own weight.")
                .defineInRange("secondaryMotionIterations", 8, 1, 32);

        SECONDARY_MOTION_BODY_RADIUS = builder
                .comment("Radius of the body volume the cloth keeps its particles out of, in blocks.",
                        "A single tube around the model's own vertical axis rather than the pendulum solver's shaped",
                        "volumes: cloth is a grid of particles and needs a cheap test. Raise it if a garment passes",
                        "through the torso, lower it if a garment stands off the body.")
                .defineInRange("secondaryMotionBodyRadius", 0.22, 0.0, 2.0);

        SECONDARY_MOTION_COLLISION = builder
                .comment("Keep swinging pieces out of the model's own body (the torso, the hips, the thighs, the",
                        "legs and the head). Each volume is a sphere sized from that joint's own bind geometry, moved",
                        "every frame by the same transform the skinning uses, so it follows exactly what is drawn.",
                        "Without it a skirt passes through the legs and hair through the shoulders - but a model whose",
                        "body geometry is unusual may get volumes that push too hard, which this turns off.",
                        "The arms are deliberately NOT collision volumes. A sphere around an upper arm or a forearm",
                        "sweeps through the space a skirt occupies every time the character moves its arms, so it",
                        "throws panels on contact and lets go on the next frame, which reads as the garment coming",
                        "apart. A skirt therefore collides with the torso and the thighs, which is what it rests on,",
                        "and hair does not collide with the arms that swing through it.")
                .define("secondaryMotionCollision", true);

        SECONDARY_MOTION_MAX_ANGLE_DEGREES = builder
                .comment("How far one joint of a hanging piece may bend from its animated pose, in degrees.",
                        "This is the ceiling on how wild the swing can look; lower it first if a joint swings",
                        "through the body. Real models put the useful range at 30-70.",
                        "It bounds each joint of a chain, not the piece as a whole - see",
                        "secondaryMotionMaxAngleRootDegrees for how the joints of one piece add up, and note that",
                        "the piece total is min(this x joints, max(15 degrees x joints, 120 degrees)).")
                .defineInRange("secondaryMotionMaxAngleDegrees", 60.0, 0.0, 150.0);

        SECONDARY_MOTION_MAX_ANGLE_ROOT_DEGREES = builder
                .comment("The same ceiling for the top of a hanging piece, in degrees.",
                        "A root carries the whole hairdo or skirt, so its own swing is what the rest multiply",
                        "against; this is deliberately much smaller than the per-joint limit above.",
                        "This is the second knob to turn when trailing pieces sit pinned while you run: measured on a",
                        "real skirt panel, the default 20 degrees is reached at only 2.6-3.0 blocks/s of walking, and",
                        "a 40 degree strand limit at 3.9-4.6 blocks/s of jogging. Raise it if the garment reads as",
                        "over-restrained, lower it if it swings through the body.",
                        "How the limits add up along a chain: each joint is held to its own limit above, and the piece",
                        "as a whole is additionally bounded by min(limit x joints, max(15 degrees x joints, 120",
                        "degrees)). So a two-joint piece behaves exactly as it always did (root 20, tip 60), while a",
                        "seven-joint ponytail gets about 17 degrees per joint instead of having the whole budget",
                        "shared out to eight - a long chain bends along its length rather than hinging at the waist.")
                .defineInRange("secondaryMotionMaxAngleRootDegrees", 20.0, 0.0, 90.0);

        SECONDARY_MOTION_MAX_CHAINS = builder
                .comment("How many bones of one model may swing at once.",
                        "-1 (the default) lets the model's own classification decide: it reads every bone, keeps the",
                        "ones that hang as cloth or hair, drops the containers, and lands on the pieces this model",
                        "actually has. That is the answer a single number cannot guess - it is the same number for a",
                        "two-bone fringe and for a garment of forty panels - and a limit that is too small does not",
                        "degrade gracefully: whole pieces are left out, so part of a skirt swings and the rest stays",
                        "bolted to the pose, which reads as the garment coming apart.",
                        "0 disables secondary motion entirely. A positive value is a hard cap, counted in bones",
                        "rather than pieces: every bone of a hairdo swings on its own pivot, so a model with a",
                        "ten-segment braid spends ten. Real models land between 4 and 60; set a cap if a model that",
                        "declares hundreds costs too much on a phone.",
                        "A config file written before -1 existed carries 96 (the default then) or 24 (the default",
                        "before that). 24 is still read as 'unset', because honouring it would truncate real",
                        "garments; 96 is honoured as the cap this key's own description always promised.")
                .defineInRange("secondaryMotionMaxChains", -1, -1, 512);

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
