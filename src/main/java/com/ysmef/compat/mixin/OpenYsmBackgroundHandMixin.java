package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import com.ysmef.compat.renderer.YSMModelAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.client.event.RenderHandEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the un-obfuscated YSM/OpenYSM first-person background hand (the
 * animated hand/arm model drawn as a first-person overlay) while the local player
 * is in Epic Fight battle mode, and whenever the local player uses one of YSM's
 * built-in vanilla player models (misc/2_steve, misc/1_alex) - the vanilla hand
 * is the correct one for those.
 *
 * Two shapes of the entry point are covered, each with require = 0:
 *
 * 1. OpenYSM / ModernYSM (this mod's target fork, e.g. 2.6.6.6):
 *    onRenderHand(PoseStack, MultiBufferSource, int, float) -> void. Note this
 *    form takes no player and no event: it reads Minecraft.getInstance().player
 *    itself, and being called at all means YSM drew its hand - hence cancel().
 * 2. The un-obfuscated 2.6.5-era release shipped the Forge event handler:
 *    onRenderHand(RenderHandEvent) -> void.
 *
 * The obfuscated-build counterpart is YsmBackgroundHandMixin. Targets are
 * referenced by string because only the obfuscated 2.6.5 release jar is on the
 * compile classpath; a missing string target only logs a warning.
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.RenderFirstPlayerBackground", remap = false)
public abstract class OpenYsmBackgroundHandMixin {

    /**
     * OpenYSM / ModernYSM (2.6.6.x) form. The method has no player argument, so
     * the local player is resolved from the client - matching what the target
     * method itself does internally.
     */
    @Inject(method = "onRenderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressOpenYsmBackgroundHandModernForm(
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource buffer,
            int packedLight, float partialTick,
            CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && (YSMBattleMode.isBattleMode(player) || !YSMModelAccess.isYsmDriven(player))) {
            ci.cancel();
        }
    }

    /** Legacy un-obfuscated 2.6.5-era Forge event handler form. */
    @Inject(method = "onRenderHand(Lnet/minecraftforge/client/event/RenderHandEvent;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressYsmBackgroundHandInBattleMode(RenderHandEvent event, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && (YSMBattleMode.isBattleMode(player) || !YSMModelAccess.isYsmDriven(player))) {
            ci.cancel();
        }
    }
}
