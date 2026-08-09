package com.ysmef.compat.mixin;

import com.ysmef.compat.gui.YsmConfigScreenHook;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the "YSM-EF Compat: GPU Rendering" checkbox to YSM's config screen
 * (com.elfmcys.yesstevemodel.client.gui.ExtraPlayerConfigScreen, "YSM Config
 * GUI" - the config screen of the model selection GUI, present in the official
 * YSM 2.6.5 release and OpenYSM with the same un-obfuscated class layout).
 *
 * The screen lays out 22px checkbox rows at y+45..y+243 and the
 * loading_state_position button at y+264. The mixin:
 * - moves that button down 22px (ModifyConstant of the 264 constant in
 *   m_7856_/init), freeing the y+265 row;
 * - appends a checkbox row bound to this mod's enableGpuRender config.
 *
 * All injections are non-critical (require = 0): the ModernYSM fork ships a
 * different GUI (OptionScreen with its own UseGpuRenderer row), where these
 * injections simply do not apply and nothing is added.
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.gui.ExtraPlayerConfigScreen", remap = false)
public abstract class OpenYsmConfigScreenMixin {

    /** y-offset of the loading_state_position button: 264 -> 286, freeing row 265. */
    @ModifyConstant(method = "m_7856_", constant = @Constant(intValue = 264), remap = false, require = 0)
    private int ysmef$makeRoomForGpuRenderRow(int value) {
        return value + 22;
    }

    /** Append the GPU render checkbox below the last config row. */
    @Inject(method = "m_7856_", at = @At("TAIL"), require = 0)
    private void ysmef$addGpuRenderCheckbox(CallbackInfo ci) {
        YsmConfigScreenHook.addGpuRenderCheckbox((Screen) (Object) this, 265);
    }

    /**
     * ModernYSM-style OptionScreen variant: appends a BooleanOptionRow to the
     * "performance" group (only when the fork is not ModernYSM, which already
     * ships its own UseGpuRenderer checkbox).
     */
    @Inject(method = "registerGroups", at = @At("TAIL"), require = 0)
    private void ysmef$addGpuRenderOption(CallbackInfo ci) {
        YsmConfigScreenHook.addOpenYsmGpuRenderOption((Screen) (Object) this);
    }
}
