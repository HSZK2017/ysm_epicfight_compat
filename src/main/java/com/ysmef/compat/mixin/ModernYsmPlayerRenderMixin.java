package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import com.ysmef.compat.renderer.YSMModelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ModernYSM compatibility: the fork refactored ReplacePlayerRenderEvent's hook
 * from a Forge event handler into a helper returning a boolean
 * (onRenderPlayerPre(Player, float, PoseStack, MultiBufferSource, int) -> boolean;
 * the Forge hook cancels the vanilla render when it returns true). In Epic
 * Fight battle mode the compat returns false, so the vanilla render proceeds
 * and YSMRenderHook draws the converted YSM mesh through the Epic Fight
 * pipeline (YSMPlayerRenderer) instead of ModernYSM's own model renderer.
 *
 * It also returns false when the player selected one of YSM's built-in vanilla
 * player models (misc/2_steve, misc/1_alex): those are the vanilla player rig
 * skinned with the player's own skin, so the vanilla render path reproduces them
 * exactly - with the Epic Fight biped in battle mode (YSMRenderHook) and with the
 * vanilla model otherwise - and no YSM model needs to be drawn.
 *
 * The old event-handler signature is handled by OpenYsmPlayerRenderMixin; both
 * injections are non-critical (require = 0), so only the signature the loaded
 * fork actually has applies.
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.ReplacePlayerRenderEvent", remap = false)
public abstract class ModernYsmPlayerRenderMixin {

    @Inject(method = "onRenderPlayerPre(Lnet/minecraft/world/entity/player/Player;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressModernYsmPlayerRenderInBattleMode(net.minecraft.world.entity.player.Player player,
                                                                        float partialTick,
                                                                        com.mojang.blaze3d.vertex.PoseStack poseStack,
                                                                        net.minecraft.client.renderer.MultiBufferSource buffer,
                                                                        int packedLight,
                                                                        CallbackInfoReturnable<Boolean> cir) {
        if (YSMBattleMode.isBattleMode(player) || !YSMModelAccess.isYsmDriven(player)) {
            cir.setReturnValue(false);
        }
    }
}
