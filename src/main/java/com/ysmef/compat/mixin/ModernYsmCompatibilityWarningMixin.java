package com.ysmef.compat.mixin;

import com.ysmef.compat.ysm.YsmCompatibilityWarning;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ModernYSM moved the Forge warning helper into ForgeClientSetupHooks. */
@Mixin(targets = "com.elfmcys.yesstevemodel.forge.ForgeClientSetupHooks", remap = false)
public abstract class ModernYsmCompatibilityWarningMixin {
    @Inject(method = "showInCompatibleMod(Ljava/lang/String;Ljava/lang/String;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$skipEpicFightWarning(String modId, String displayName, CallbackInfo ci) {
        if (YsmCompatibilityWarning.isEpicFight(modId, displayName)) {
            ci.cancel();
        }
    }
}
