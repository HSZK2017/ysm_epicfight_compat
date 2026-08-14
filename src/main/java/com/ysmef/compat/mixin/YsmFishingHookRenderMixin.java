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
 * battle mode (same return-value convention as YsmProjectileRenderMixin).
 *
 * The target is YSM's CustomFishingHookRenderer#tryRenderCustomHook (obfuscated).
 */
@Mixin(value = com.elfmcys.yesstevemodel.oO0Ooooooo0O0OOOO00OoOo0.class, remap = false)
public abstract class YsmFishingHookRenderMixin {

    @Inject(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraft/world/entity/projectile/FishingHook;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
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
