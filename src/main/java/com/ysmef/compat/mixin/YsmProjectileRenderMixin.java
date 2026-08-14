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
 * shooting player is in Epic Fight battle mode. YSM's renderer returns false when
 * it drew a custom model (which then cancels the vanilla render); forcing a true
 * result skips the custom model so the vanilla projectile renders instead.
 *
 * The target is YSM's CustomProjectileRenderer#renderProjectile. YSM's release
 * jar is obfuscated; re-derive by scanning for the method descriptor below.
 */
@Mixin(value = com.elfmcys.yesstevemodel.O0oOooooo00Ooooo0OoOOOO0.class, remap = false)
public abstract class YsmProjectileRenderMixin {

    @Inject(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraft/world/entity/projectile/Projectile;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
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
