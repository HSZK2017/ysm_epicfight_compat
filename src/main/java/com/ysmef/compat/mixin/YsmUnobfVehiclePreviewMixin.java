package com.ysmef.compat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses YSM's vehicle preview-model pass while a passenger is in Epic
 * Fight battle mode (companion to YsmUnobfVehicleRenderMixin). The
 * un-obfuscated target (ModelPreviewRenderer#renderVehicleModel) exists with
 * this exact signature in the official YSM 2.6.5 release, OpenYSM and
 * ModernYSM alike.
 *
 * The obfuscated-build counterpart is YsmVehiclePreviewMixin. This injection
 * is non-critical (require = 0).
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.renderer.ModelPreviewRenderer", remap = false)
public abstract class YsmUnobfVehiclePreviewMixin {

    @Inject(method = "renderVehicleModel(Lnet/minecraft/world/entity/Entity;Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressYsmVehiclePreviewInBattleMode(Entity entity, PoseStack poseStack, float partialTick,
                                                                    CallbackInfo ci) {
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof Player player && YSMBattleMode.isBattleMode(player)) {
                ci.cancel();
                return;
            }
        }
    }
}
