package com.ysmef.compat;

import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.network.NetworkHandler;
import net.minecraftforge.fml.common.Mod;
import yesman.epicfight.api.animation.AnimationManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(YSMEpicFightCompat.MODID)
public class YSMEpicFightCompat {

    public static final String MODID = "ysm_epicfight_compat";
    public static final Logger LOGGER = LogManager.getLogger("YSM-EF Compat");

    public YSMEpicFightCompat() {
        YSMCompatConfig.register();
        NetworkHandler.init();
        // Align with Epic Fight's own no-warning mechanism: generated wheel
        // templates (ysm_epicfight_compat:public/pub_*) are runtime-only
        // client animations and must never be flagged as missing. 20.14.17
        // only consults this set for datapack reading; the registry
        // consistency check itself is exempted by AnimationManagerValidationMixin.
        AnimationManager.addNoWarningModId(MODID);
        LOGGER.info("YSM-EF Compat: Initialized successfully");
    }
}
