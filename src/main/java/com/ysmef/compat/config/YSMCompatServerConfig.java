package com.ysmef.compat.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Settings the hosting side reads: {@code config/ysm_epicfight_compat-common.toml}.
 *
 * <p>COMMON rather than SERVER on purpose: SERVER configs live per world under {@code serverconfig/},
 * and the person who decides whether a dedicated server hands its model files out should find the
 * switch next to the server's other config files, once, not in every world folder.
 */
public final class YSMCompatServerConfig {

    public static final ForgeConfigSpec COMMON_SPEC;

    /** Send model packages to clients that are missing them or hold a different version. */
    public static final ForgeConfigSpec.BooleanValue MODEL_TRANSFER_ENABLED;
    /** Also send packages from YSM's auth folder. */
    public static final ForgeConfigSpec.BooleanValue MODEL_TRANSFER_SHARE_AUTH;
    /** Largest package (file content) the server sends, in MiB. */
    public static final ForgeConfigSpec.IntValue MODEL_TRANSFER_MAX_MODEL_MIB;
    /** Upload budget per receiving player per server tick, in KiB. */
    public static final ForgeConfigSpec.IntValue MODEL_TRANSFER_KIB_PER_TICK;
    /** Packages held in memory for sending at the same time, across all players. */
    public static final ForgeConfigSpec.IntValue MODEL_TRANSFER_MAX_CONCURRENT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("YSM Epic Fight Compat - settings read by the hosting side (dedicated server or LAN host)")
                .push("modelTransfer");

        MODEL_TRANSFER_ENABLED = builder
                .comment("Send YSM model packages from this server's config/yes_steve_model to clients running this mod",
                        "that do not have the model, or have a different version of it.",
                        "This mod converts the model files themselves into Epic Fight meshes, and YSM's own model sync",
                        "keeps its copies encrypted for YSM alone, so without this a player whose client lacks a",
                        "model sees anyone wearing it as the Epic Fight default model in battle mode.",
                        "Clients store the files in config/ysm_epicfight_compat/server_models/, never in their own",
                        "config/yes_steve_model, and only use them while connected to this server.")
                .define("enabled", true);

        MODEL_TRANSFER_SHARE_AUTH = builder
                .comment("Also send models from the auth folder.",
                        "YSM restricts auth models to authorized players and ships them to clients encrypted. This",
                        "transfer sends the files as they are on disk, so enabling it hands every connected player a",
                        "copy they can keep. Leave it off unless you have the right to distribute those models.")
                .define("shareAuthModels", false);

        MODEL_TRANSFER_MAX_MODEL_MIB = builder
                .comment("Largest model package (total file size) that is sent, in MiB.")
                .defineInRange("maxModelSizeMiB", 64, 1, 512);

        MODEL_TRANSFER_KIB_PER_TICK = builder
                .comment("Upload rate per receiving player, in KiB per server tick (20 ticks per second).",
                        "128 is about 2.5 MiB/s per player.")
                .defineInRange("kibPerTickPerPlayer", 128, 8, 4096);

        MODEL_TRANSFER_MAX_CONCURRENT = builder
                .comment("How many model packages may be held in memory for sending at once, across all players.",
                        "Further downloads wait their turn. Bounds the memory a burst of joining players can cost.")
                .defineInRange("maxConcurrentTransfers", 3, 1, 32);

        builder.pop();
        COMMON_SPEC = builder.build();
    }

    private YSMCompatServerConfig() {}
}
