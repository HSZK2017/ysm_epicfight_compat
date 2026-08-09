package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraftforge.client.event.RenderPlayerEvent;
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
 * and the Epic Fight patched renderer (YSMPlayerRenderer / YSMRenderHook) draws
 * the converted YSM mesh instead of ModernYSM's own model renderer.
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
        if (YSMBattleMode.isBattleMode(player)) {
            cir.setReturnValue(false);
        }
    }
}
