package com.ysmef.compat.mixin;

import com.ysmef.compat.animation.YsmModelVariableState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OpenYSM/ModernYSM range controls do not call AnimationRouletteScreen.executeExpression. */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.gui.button.AnimationSlider", remap = false)
public abstract class YsmUnobfuscatedSliderExpressionMixin {
    @Shadow @Final private String controllerName;

    @Inject(method = {"applyValue()V", "m_5697_()V"}, at = @At("HEAD"), require = 0)
    private void ysmef$captureSliderExpression(CallbackInfo ci) {
        try {
            Object raw = getClass().getMethod("getValue").invoke(this);
            if (raw instanceof Number value) {
                YsmModelVariableState.onConfigExpression(Minecraft.getInstance().player,
                        controllerName + "=" + value.doubleValue());
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
