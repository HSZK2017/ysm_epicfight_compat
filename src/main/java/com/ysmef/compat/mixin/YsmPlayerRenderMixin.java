package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraftforge.client.event.RenderPlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses YSM's third-person player render interception while the player is in
 * Epic Fight battle mode.
 *
 * YSM listens to RenderPlayerEvent.Pre (NORMAL) and, whenever its model is active,
 * cancels the event and draws the player through its own CustomPlayerRenderer. That
 * renderer re-posts a RenderLivingEvent.Pre from inside LivingEntityRenderer.render,
 * which Epic Fight's handler then takes over - but with YSM's renderer, whose layer
 * list contains no PlayerItemInHandLayer. Epic Fight's PatchedItemInHandLayer is
 * therefore never invoked on that path, so the held weapon never renders (the mesh
 * renders, the weapon does not).
 *
 * Skipping the handler entirely in battle mode lets the vanilla PlayerRenderer
 * proceed; its own RenderLivingEvent.Pre is then intercepted at HIGHEST by
 * YSMRenderHook, which draws the player through Epic Fight's pipeline with the
 * vanilla renderer, whose layers include PlayerItemInHandLayer ->
 * PatchedItemInHandLayer, so Epic Fight's weapon rendering is restored. (Epic
 * Fight's own handler never runs for the canceled event: it is registered with
 * the default receiveCanceled=false.)
 *
 * The target is YSM's ReplacePlayerRenderEvent#onRenderPlayerPre. YSM's release jar
 * is obfuscated, so the obfuscated class/method names are used; they can be
 * re-derived for other YSM versions by scanning the jar for classes referencing
 * Lnet/minecraftforge/client/event/RenderPlayerEvent$Pre;.
 */
@Mixin(value = com.elfmcys.yesstevemodel.O0oOOo00o0oooOo0OoO0OOo0.class, remap = false)
public abstract class YsmPlayerRenderMixin {

    @Inject(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraftforge/client/event/RenderPlayerEvent$Pre;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressYsmPlayerRenderInBattleMode(RenderPlayerEvent.Pre event, CallbackInfo ci) {
        if (YSMBattleMode.isBattleMode(event.getEntity())) {
            ci.cancel();
        }
    }
}
