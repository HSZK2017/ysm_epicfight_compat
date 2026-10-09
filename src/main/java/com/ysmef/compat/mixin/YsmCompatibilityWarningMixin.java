package com.ysmef.compat.mixin;

import com.ysmef.compat.ysm.YsmCompatibilityWarning;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Official, obfuscated YSM 2.6.5 client setup warning helper. */
@Mixin(value = com.elfmcys.yesstevemodel.OOoo00oOOoooooooooOooOOo.class, remap = false)
public abstract class YsmCompatibilityWarningMixin {
    @Inject(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Ljava/lang/String;Ljava/lang/String;)V",
            at = @At("HEAD"), cancellable = true)
    private static void ysmef$skipEpicFightWarning(String modId, String displayName, CallbackInfo ci) {
        if (YsmCompatibilityWarning.isEpicFight(modId, displayName)) {
            ci.cancel();
        }
    }
}
