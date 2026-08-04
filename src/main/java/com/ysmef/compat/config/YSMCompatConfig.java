package com.ysmef.compat.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class YSMCompatConfig {

    public static final ForgeConfigSpec CLIENT_SPEC;

    // [decommissioned] dead config: never read anywhere in the codebase.
    // public static final ForgeConfigSpec.BooleanValue DEBUG_LOG_CONVERSION;
    // public static final ForgeConfigSpec.BooleanValue USE_STANDARD_BIPED_ONLY;
    // public static final ForgeConfigSpec.IntValue MESH_CACHE_MAX_SIZE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("YSM Epic Fight Compat - Client Configuration").push("client");

        // DEBUG_LOG_CONVERSION = builder
        //         .comment("Log detailed conversion info to console (for debugging)")
        //         .define("debugLogConversion", false);
        //
        // USE_STANDARD_BIPED_ONLY = builder
        //         .comment("Only use the standard biped armature (ignores YSM extra bones like ears/tails)")
        //         .define("useStandardBipedOnly", true);
        //
        // MESH_CACHE_MAX_SIZE = builder
        //         .comment("Maximum number of converted meshes to cache in memory")
        //         .defineInRange("meshCacheMaxSize", 16, 1, 64);

        builder.pop();

        CLIENT_SPEC = builder.build();
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
