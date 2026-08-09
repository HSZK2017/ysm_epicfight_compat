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
 * Fallback for fully-obfuscated YSM builds (not the official release): adds the
 * "YSM-EF Compat: GPU Rendering" checkbox to the obfuscated config screen
 * ("YSM Config GUI", opened from the model selection screen), whose layout is
 * identical to the un-obfuscated one (22px checkbox rows at y+45..y+243, the
 * loading_state_position button at y+264). The mixin moves that button down
 * 22px and appends a checkbox row bound to this mod's enableGpuRender config.
 *
 * The target only exists in fully-obfuscated YSM jars; for the official
 * release / OpenYSM the un-obfuscated OpenYsmConfigScreenMixin applies instead.
 */
@Mixin(value = com.elfmcys.yesstevemodel.O0o000o0oOoOoOoOOO0oOOoO.class, remap = false)
public abstract class YsmLegacyConfigScreenMixin {

    /** y-offset of the loading_state_position button: 264 -> 286, freeing row 265. */
    @ModifyConstant(method = "m_7856_", constant = @Constant(intValue = 264), remap = false, require = 0)
    private int ysmef$makeRoomForGpuRenderRow(int value) {
        return value + 22;
    }

    @Inject(method = "m_7856_", at = @At("TAIL"), require = 0)
    private void ysmef$addGpuRenderCheckbox(CallbackInfo ci) {
        YsmConfigScreenHook.addGpuRenderCheckbox((Screen) (Object) this, 265);
    }
}
