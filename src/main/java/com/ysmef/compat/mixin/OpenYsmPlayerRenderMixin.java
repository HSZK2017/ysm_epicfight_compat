package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import com.ysmef.compat.renderer.YSMModelAccess;
import net.minecraftforge.client.event.RenderPlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the un-obfuscated YSM/OpenYSM third-person player render
 * interception while the player is in Epic Fight battle mode, and whenever the
 * player selected one of YSM's built-in vanilla player models (misc/2_steve,
 * misc/1_alex).
 *
 * Two different shapes of the same entry point are covered, each with
 * require = 0 so only the one the loaded build actually has applies:
 *
 * 1. OpenYSM / ModernYSM (this mod's target fork, e.g. 2.6.6.6) refactored the
 *    hook into a helper returning a boolean:
 *    onRenderPlayerPre(Player, float, PoseStack, MultiBufferSource, int) -> boolean,
 *    where the Forge hook cancels the vanilla render when it returns true. That
 *    is why the two injections below are mutually exclusive by signature - the
 *    legacy void form in 2. below never coexists with it.
 * 2. The un-obfuscated 2.6.5-era release shipped the Forge event handler itself:
 *    onRenderPlayerPre(RenderPlayerEvent.Pre) -> void (see
 *    OpenYsmPlayerRenderMixin's original form; the ModernYSM fork source is the
 *    authority for the boolean form).
 *
 * Why skipping the handler at all: whenever a model is active, YSM cancels the
 * event and draws the player through its own CustomPlayerRenderer. That renderer
 * posts a RenderLivingEvent.Pre manually, which Epic Fight's handler then takes
 * over - but with YSM's renderer, whose layer list has no PlayerItemInHandLayer,
 * so Epic Fight's PatchedItemInHandLayer never runs and the held weapon never
 * renders. Skipping the handler lets the vanilla PlayerRenderer proceed; its own
 * RenderLivingEvent.Pre is then intercepted at HIGHEST by YSMRenderHook, which
 * draws through Epic Fight's pipeline with the vanilla renderer, whose layers do
 * include PlayerItemInHandLayer. (Epic Fight's own handler never runs for the
 * canceled event: it is registered with the default receiveCanceled=false.)
 *
 * The handler is skipped for YSM's built-in vanilla player models as well: those
 * are the vanilla player rig skinned with the player's own skin, so the vanilla
 * PlayerRenderer reproduces them exactly and no YSM model needs to be drawn.
 *
 * Targets are referenced by string because only the obfuscated 2.6.5 release jar
 * is on the compile classpath; OpenYSM fork classes are not. A missing string
 * target only logs a warning and skips the mixin.
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.ReplacePlayerRenderEvent", remap = false)
public abstract class OpenYsmPlayerRenderMixin {

    /**
     * OpenYSM / ModernYSM (2.6.6.x) boolean helper form: returning false leaves
     * the vanilla render untouched.
     */
    @Inject(method = "onRenderPlayerPre(Lnet/minecraft/world/entity/player/Player;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressOpenYsmPlayerRenderBooleanForm(
            net.minecraft.world.entity.player.Player player,
            float partialTick,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource buffer,
            int packedLight,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (YSMBattleMode.isBattleMode(player) || !YSMModelAccess.isYsmDriven(player)) {
            cir.setReturnValue(false);
        }
    }

    /** Legacy un-obfuscated 2.6.5-era Forge event handler form. */
    @Inject(method = "onRenderPlayerPre(Lnet/minecraftforge/client/event/RenderPlayerEvent$Pre;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressOpenYsmPlayerRenderInBattleMode(RenderPlayerEvent.Pre event, CallbackInfo ci) {
        if (YSMBattleMode.isBattleMode(event.getEntity())
                || !YSMModelAccess.isYsmDriven(event.getEntity())) {
            ci.cancel();
        }
    }
}
