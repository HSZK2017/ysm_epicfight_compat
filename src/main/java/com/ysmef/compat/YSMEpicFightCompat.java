package com.ysmef.compat;

import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.network.NetworkHandler;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(YSMEpicFightCompat.MODID)
public class YSMEpicFightCompat {

    public static final String MODID = "ysm_epicfight_compat";
    public static final Logger LOGGER = LogManager.getLogger("YSM-EF Compat");

    public YSMEpicFightCompat() {
        YSMCompatConfig.register();
        NetworkHandler.init();
        LOGGER.info("YSM-EF Compat: Initialized successfully");
    }
}
