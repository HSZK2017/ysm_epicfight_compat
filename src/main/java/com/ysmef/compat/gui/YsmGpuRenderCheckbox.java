package com.ysmef.compat.gui;

import com.ysmef.compat.config.YSMCompatConfig;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;

/**
 * The "YSM-EF Compat: GPU Rendering" checkbox shown in YSM's config screen
 * (mirrors OpenYSM's ConfigCheckBoxForge: a vanilla Checkbox bound to a
 * ForgeConfigSpec.BooleanValue). Toggling it writes this mod's
 * enableGpuRender client config, which YsmGpuRenderEnable reads every frame.
 */
public class YsmGpuRenderCheckbox extends Checkbox {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 20;

    public YsmGpuRenderCheckbox(int x, int y, Component label) {
        super(x, y, WIDTH, HEIGHT, label, YSMCompatConfig.ENABLE_GPU_RENDER.get());
    }

    @Override
    public void onPress() {
        super.onPress();
        YSMCompatConfig.ENABLE_GPU_RENDER.set(!YSMCompatConfig.ENABLE_GPU_RENDER.get());
    }
}
