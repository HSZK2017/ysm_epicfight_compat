package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraftforge.client.event.RenderArmEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses YSM's first-person arm rendering while the local player is in Epic
 * Fight battle mode. YSM's handler renders its own arm model and cancels the
 * vanilla arm; skipping the handler entirely lets the vanilla arm render instead.
 *
 * The target is YSM's ReplacePlayerHandRenderEvent#onRenderArm. YSM's release jar
 * is obfuscated, so the obfuscated class/method names are used; they can be
 * re-derived for other YSM versions by scanning the jar for classes referencing
 * Lnet/minecraftforge/client/event/RenderArmEvent;.
 */
@Mixin(value = com.elfmcys.yesstevemodel.ooOOOoOO000oo0o00o00o000.class, remap = false)
public abstract class YsmArmRenderMixin {

    @Inject(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraftforge/client/event/RenderArmEvent;)V",
            at = @At("HEAD"), cancellable = true)
    private static void ysmef$suppressYsmArmInBattleMode(RenderArmEvent event, CallbackInfo ci) {
        if (YSMBattleMode.isBattleMode(event.getPlayer())) {
            ci.cancel();
        }
    }
}
