package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import com.ysmef.compat.renderer.YSMModelAccess;
import net.minecraftforge.client.event.RenderArmEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses the un-obfuscated YSM/OpenYSM first-person arm rendering while the
 * local player is in Epic Fight battle mode, and whenever the player selected
 * one of YSM's built-in vanilla player models (misc/2_steve, misc/1_alex).
 * Cancelling lets the vanilla arm (with Epic Fight's patched weapon rendering)
 * render instead, which is also exactly the right arm for those two models.
 *
 * Two shapes of the entry point are covered, each with require = 0:
 *
 * 1. OpenYSM / ModernYSM (this mod's target fork, e.g. 2.6.6.6):
 *    onRenderArm(Player, HumanoidArm, PoseStack, MultiBufferSource, int) -> boolean,
 *    where true means YSM rendered the custom arm and the vanilla arm render is
 *    cancelled - so the compat returns false.
 * 2. The un-obfuscated 2.6.5-era release shipped the Forge event handler:
 *    onRenderArm(RenderArmEvent) -> void.
 *
 * The obfuscated-build counterpart is YsmArmRenderMixin. Targets are referenced
 * by string because only the obfuscated 2.6.5 release jar is on the compile
 * classpath; a missing string target only logs a warning and skips the mixin.
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.ReplacePlayerHandRenderEvent", remap = false)
public abstract class OpenYsmHandRenderMixin {

    /**
     * OpenYSM / ModernYSM (2.6.6.x) boolean form: false = do not take over the
     * vanilla arm render.
     */
    @Inject(method = "onRenderArm(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressOpenYsmHandBooleanForm(
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.entity.HumanoidArm arm,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource buffer,
            int packedLight,
            CallbackInfoReturnable<Boolean> cir) {
        if (YSMBattleMode.isBattleMode(player) || !YSMModelAccess.isYsmDriven(player)) {
            cir.setReturnValue(false);
        }
    }

    /** Legacy un-obfuscated 2.6.5-era Forge event handler form. */
    @Inject(method = "onRenderArm(Lnet/minecraftforge/client/event/RenderArmEvent;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressYsmHandInBattleMode(RenderArmEvent event, CallbackInfo ci) {
        if (YSMBattleMode.isBattleMode(event.getPlayer())
                || !YSMModelAccess.isYsmDriven(event.getPlayer())) {
            ci.cancel();
        }
    }
}
