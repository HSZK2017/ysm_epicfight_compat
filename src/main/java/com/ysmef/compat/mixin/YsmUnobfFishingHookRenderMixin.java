package com.ysmef.compat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses YSM's custom fishing hook model while the owner is in Epic Fight
 * battle mode (same return-value convention as YsmUnobfProjectileRenderMixin).
 * The un-obfuscated target (CustomFishingHookRenderer#tryRenderCustomHook)
 * exists with this exact signature in the official YSM 2.6.5 release, OpenYSM
 * and ModernYSM alike.
 *
 * The obfuscated-build counterpart is YsmFishingHookRenderMixin. This
 * injection is non-critical (require = 0).
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.renderer.CustomFishingHookRenderer", remap = false)
public abstract class YsmUnobfFishingHookRenderMixin {

    @Inject(method = "tryRenderCustomHook(Lnet/minecraft/world/entity/projectile/FishingHook;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressYsmHookInBattleMode(FishingHook fishingHook, float entityYaw, float partialTick,
                                                          PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                                          CallbackInfoReturnable<Boolean> cir) {
        Entity owner = fishingHook.getOwner();
        if (owner instanceof Player player && YSMBattleMode.isBattleMode(player)) {
            cir.setReturnValue(true);
        }
    }
}
