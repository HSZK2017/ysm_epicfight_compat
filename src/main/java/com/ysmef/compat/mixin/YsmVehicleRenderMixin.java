package com.ysmef.compat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses YSM's custom vehicle models (boats, minecarts, ...) while a passenger
 * is in Epic Fight battle mode (same return-value convention as
 * YsmProjectileRenderMixin).
 *
 * The target is YSM's CustomVehicleRenderer#renderVehicle (obfuscated).
 */
@Mixin(value = com.elfmcys.yesstevemodel.OOoO0O0OooOO0o00oOoOOoO0.class, remap = false)
public abstract class YsmVehicleRenderMixin {

    @Inject(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressYsmVehicleInBattleMode(Entity entity, float entityYaw, float partialTick,
                                                             PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                                             CallbackInfoReturnable<Boolean> cir) {
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof Player player && YSMBattleMode.isBattleMode(player)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
