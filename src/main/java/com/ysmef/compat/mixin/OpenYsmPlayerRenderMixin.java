package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraftforge.client.event.RenderPlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses OpenYSM's third-person player render interception while the player is
 * in Epic Fight battle mode - the OpenYSM counterpart of YsmPlayerRenderMixin.
 *
 * OpenYSM is an un-obfuscated fork of Yes Steve Model (same modId
 * "yes_steve_model", mutually exclusive with the release jar). Its
 * ReplacePlayerRenderEvent#onRenderPlayerPre listens to RenderPlayerEvent.Pre and,
 * whenever a model is active, cancels the event and draws the player through its own
 * CustomPlayerRenderer. That renderer posts a RenderLivingEvent.Pre manually (see
 * GeoReplacedEntityRenderer#renderEntityWithTexture) which Epic Fight's handler then
 * takes over - but with OpenYSM's renderer, whose layer list contains no
 * PlayerItemInHandLayer. Epic Fight's PatchedItemInHandLayer is therefore never
 * invoked on that path, so the held weapon never renders (the mesh renders, the
 * weapon does not).
 *
 * Skipping the handler entirely in battle mode lets the vanilla PlayerRenderer
 * proceed; its own RenderLivingEvent.Pre is then intercepted at HIGHEST by
 * YSMRenderHook, which draws the player through Epic Fight's pipeline with the
 * vanilla renderer, whose layers include PlayerItemInHandLayer ->
 * PatchedItemInHandLayer, so Epic Fight's weapon rendering is restored. (Epic
 * Fight's own handler never runs for the canceled event: it is registered with
 * the default receiveCanceled=false.)
 *
 * The target is referenced by string because the OpenYSM jar is not on the compile
 * classpath (the obfuscated release jar is). The string target is safe under the
 * obfuscated YSM 2.6.5: a missing @Mixin target only logs a warning and skips the
 * mixin (MixinInfo#handleTargetError, non-strict configs).
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.ReplacePlayerRenderEvent", remap = false)
public abstract class OpenYsmPlayerRenderMixin {

    @Inject(method = "onRenderPlayerPre(Lnet/minecraftforge/client/event/RenderPlayerEvent$Pre;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressOpenYsmPlayerRenderInBattleMode(RenderPlayerEvent.Pre event, CallbackInfo ci) {
        if (YSMBattleMode.isBattleMode(event.getEntity())) { ci.cancel(); }
    }
}
