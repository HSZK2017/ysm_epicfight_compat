package com.ysmef.compat.mixin;

import com.ysmef.compat.animation.YsmRoamingState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Captures the clothing/accessory config expressions YSM's roulette executes.
 *
 * YSM's own script processor may be idle while its renderer is replaced by Epic
 * Fight in battle mode, so these expressions never reach the roaming struct our
 * mesh-visibility mirror reads. Replaying the same expression locally keeps the
 * converted mesh's accessory parts in sync with the roulette choices.
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.gui.AnimationRouletteScreen", remap = false)
public abstract class YsmRouletteConfigExpressionMixin {

    @Inject(method = "executeExpression(Ljava/lang/String;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), require = 0)
    private void ysmef$captureConfigExpression(String expression, Consumer<String> callback, CallbackInfo ci) {
        try {
            YsmRoamingState.onConfigExpression(Minecraft.getInstance().player, expression);
        } catch (Throwable ignored) {
        }
    }
}
