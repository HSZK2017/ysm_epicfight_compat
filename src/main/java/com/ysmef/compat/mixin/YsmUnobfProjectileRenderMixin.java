package com.ysmef.compat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses YSM's custom projectile models (arrows, tridents, ...) while the
 * shooting player is in Epic Fight battle mode. The un-obfuscated target
 * (CustomProjectileRenderer#renderProjectile) exists with this exact signature
 * in the official YSM 2.6.5 release, OpenYSM and ModernYSM alike; forcing a
 * true result skips the custom model so the vanilla projectile renders.
 *
 * The obfuscated-build counterpart is YsmProjectileRenderMixin. This injection
 * is non-critical (require = 0).
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.renderer.CustomProjectileRenderer", remap = false)
public abstract class YsmUnobfProjectileRenderMixin {

    @Inject(method = "renderProjectile(Lnet/minecraft/world/entity/projectile/Projectile;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressYsmProjectileInBattleMode(Projectile projectile, float entityYaw, float partialTick,
                                                                PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                                                CallbackInfoReturnable<Boolean> cir) {
        Entity owner = projectile.getOwner();
        if (owner instanceof Player player && YSMBattleMode.isBattleMode(player)) {
            cir.setReturnValue(true);
        }
    }
}
