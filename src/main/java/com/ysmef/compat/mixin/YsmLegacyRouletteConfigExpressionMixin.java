package com.ysmef.compat.mixin;

import com.ysmef.compat.animation.YsmModelVariableState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** The obfuscated YSM 2.6.5 roulette's expression entry point. */
@Mixin(targets = "com.elfmcys.yesstevemodel.oooOO0o0ooo0O000Oo0ooOoo", remap = false)
public abstract class YsmLegacyRouletteConfigExpressionMixin {
    @Inject(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Ljava/lang/String;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), require = 0)
    private void ysmef$captureConfigExpression(String expression, Consumer<String> callback, CallbackInfo ci) {
        try {
            YsmModelVariableState.onConfigExpression(Minecraft.getInstance().player, expression);
        } catch (Throwable ignored) {
            // The optional UI bridge must not break YSM's own control screen.
        }
    }
}
