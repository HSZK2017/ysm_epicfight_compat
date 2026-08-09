package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ModernYSM compatibility: the fork refactored ReplacePlayerHandRenderEvent's
 * hook into onRenderArm(Player, HumanoidArm, PoseStack, MultiBufferSource, int)
 * returning a boolean (the Forge hook cancels the vanilla arm render when it
 * returns true). In Epic Fight battle mode the compat returns false so the
 * vanilla arm (with Epic Fight's patched weapon rendering) renders instead of
 * ModernYSM's custom arm model.
 *
 * The old event-handler signature (RenderArmEvent) is handled by
 * OpenYsmHandRenderMixin; both injections are non-critical (require = 0).
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.ReplacePlayerHandRenderEvent", remap = false)
public abstract class ModernYsmHandRenderMixin {

    @Inject(method = "onRenderArm(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressModernYsmHandInBattleMode(Player player,
                                                                net.minecraft.world.entity.HumanoidArm arm,
                                                                com.mojang.blaze3d.vertex.PoseStack poseStack,
                                                                net.minecraft.client.renderer.MultiBufferSource buffer,
                                                                int packedLight,
                                                                CallbackInfoReturnable<Boolean> cir) {
        if (YSMBattleMode.isBattleMode(player)) {
            cir.setReturnValue(false);
        }
    }
}
