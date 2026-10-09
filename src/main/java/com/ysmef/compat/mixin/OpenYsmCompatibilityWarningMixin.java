package com.ysmef.compat.mixin;

import com.ysmef.compat.ysm.YsmCompatibilityWarning;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OpenYSM keeps the same warning helper under a readable class name. */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.ClientSetupEvent", remap = false)
public abstract class OpenYsmCompatibilityWarningMixin {
    @Inject(method = "showInCompatibleMod(Ljava/lang/String;Ljava/lang/String;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$skipEpicFightWarning(String modId, String displayName, CallbackInfo ci) {
        if (YsmCompatibilityWarning.isEpicFight(modId, displayName)) {
            ci.cancel();
        }
    }
}
