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
 * Suppresses YSM's vehicle preview-model pass while a passenger is in Epic Fight
 * battle mode (companion to YsmVehicleRenderMixin).
 *
 * The target is YSM's ModelPreviewRenderer#renderVehicleModel (obfuscated).
 */
@Mixin(value = com.elfmcys.yesstevemodel.OoO00Oo00Ooo0OoOoo00o000.class, remap = false)
public abstract class YsmVehiclePreviewMixin {

    @Inject(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraft/world/entity/Entity;Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
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
