package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import com.ysmef.compat.renderer.YSMModelAccess;
import net.minecraftforge.client.event.RenderArmEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses YSM's first-person arm rendering while the local player is in Epic
 * Fight battle mode, and whenever the player selected one of YSM's built-in
 * vanilla player models.
 *
 * YSM's handler renders its own arm model and cancels the vanilla arm; skipping
 * the handler entirely lets the vanilla arm render instead - which is exactly
 * what the built-in vanilla player models (misc/2_steve, misc/1_alex) are, the
 * vanilla rig skinned with the player's own skin.
 *
 * The target is YSM's ReplacePlayerHandRenderEvent#onRenderArm. YSM's release jar
 * is obfuscated, so the obfuscated class/method names are used; they can be
 * re-derived for other YSM versions by scanning the jar for classes referencing
 * Lnet/minecraftforge/client/event/RenderArmEvent;.
 */
@Mixin(value = com.elfmcys.yesstevemodel.ooOOOoOO000oo0o00o00o000.class, remap = false)
public abstract class YsmArmRenderMixin {

    @Inject(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraftforge/client/event/RenderArmEvent;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressYsmArmInBattleMode(RenderArmEvent event, CallbackInfo ci) {
        if (YSMBattleMode.isBattleMode(event.getPlayer())
                || !YSMModelAccess.isYsmDriven(event.getPlayer())) {
            ci.cancel();
        }
    }
}
