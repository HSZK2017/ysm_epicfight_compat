package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ModernYSM compatibility: the fork refactored RenderFirstPlayerBackground's
 * hook from a RenderHandEvent handler into
 * onRenderHand(PoseStack, MultiBufferSource, int, float). In Epic Fight battle
 * mode the background hand model is skipped (the vanilla hand renders instead).
 *
 * The old event-handler signature is handled by OpenYsmBackgroundHandMixin;
 * both injections are non-critical (require = 0).
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.RenderFirstPlayerBackground", remap = false)
public abstract class ModernYsmBackgroundHandMixin {

    @Inject(method = "onRenderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressModernYsmBackgroundHandInBattleMode(com.mojang.blaze3d.vertex.PoseStack poseStack,
                                                                          net.minecraft.client.renderer.MultiBufferSource buffer,
                                                                          int packedLight, float partialTick,
                                                                          CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && YSMBattleMode.isBattleMode(player)) {
            ci.cancel();
        }
    }
}
