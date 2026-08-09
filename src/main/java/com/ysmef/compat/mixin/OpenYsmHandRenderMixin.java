package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraftforge.client.event.RenderArmEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses YSM's first-person arm rendering while the local player is in Epic
 * Fight battle mode (un-obfuscated target: the official YSM 2.6.5 release and
 * OpenYSM ship ReplacePlayerHandRenderEvent#onRenderArm(RenderArmEvent) with
 * this signature). Cancelling the handler lets the vanilla arm (with Epic
 * Fight's patched weapon rendering) render instead.
 *
 * The obfuscated-build counterpart is YsmArmRenderMixin; the ModernYSM fork
 * uses a different signature (see ModernYsmHandRenderMixin). This injection is
 * non-critical (require = 0).
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.ReplacePlayerHandRenderEvent", remap = false)
public abstract class OpenYsmHandRenderMixin {

    @Inject(method = "onRenderArm(Lnet/minecraftforge/client/event/RenderArmEvent;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressYsmHandInBattleMode(RenderArmEvent event, CallbackInfo ci) {
        if (YSMBattleMode.isBattleMode(event.getPlayer())) {
            ci.cancel();
        }
    }
}
