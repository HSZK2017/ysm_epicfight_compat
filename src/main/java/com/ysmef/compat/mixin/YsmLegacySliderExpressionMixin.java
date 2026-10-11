package com.ysmef.compat.mixin;

import com.ysmef.compat.animation.YsmModelVariableState;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.gui.widget.ForgeSlider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures range controls, which bypass the roulette's expression method. */
@Mixin(targets = "com.elfmcys.yesstevemodel.oo0ooOoOOOO0OOoO0OOoOOo0", remap = false)
public abstract class YsmLegacySliderExpressionMixin {
    @Shadow @Final private String oOOOo0OOO0ooooo0O00OO0o0;

    @Inject(method = "m_5697_()V", at = @At("HEAD"), require = 0)
    private void ysmef$captureSliderExpression(CallbackInfo ci) {
        try {
            double value = ((ForgeSlider) (Object) this).getValue();
            YsmModelVariableState.onConfigExpression(Minecraft.getInstance().player,
                    oOOOo0OOO0ooooo0O00OO0o0 + "=" + value);
        } catch (Throwable ignored) {
            // The optional UI bridge must not break the original slider.
        }
    }
}
