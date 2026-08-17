package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import com.ysmef.compat.renderer.YSMModelAccess;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderLivingEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets YSM render its own GEO model outside Epic Fight battle mode.
 *
 * YSM's CustomPlayerRenderer re-posts RenderLivingEvent.Pre before drawing. Epic
 * Fight's RenderEngine handler normally consumes that event and draws the
 * converted EF mesh even when the player is not in battle mode, so the YSM
 * renderer never draws its own model (and models whose variant scripts cannot
 * be fully evaluated on the EF mesh, such as Wither2.3, render every variant on
 * top of each other). Skip Epic Fight's handler for the YSM renderer path while
 * the player is not in battle mode; YSM then proceeds with its native GEO
 * rendering. The inventory-screen vanilla PlayerRenderer path is intentionally
 * left untouched.
 */
@Mixin(value = yesman.epicfight.client.events.engine.RenderEngine.Events.class, remap = false)
public abstract class EpicFightRenderLivingEventMixin {

    @Inject(method = "renderLivingEvent(Lnet/minecraftforge/client/event/RenderLivingEvent$Pre;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$skipEpicFightForNonBattleYsmRenderers(
            RenderLivingEvent.Pre<? extends net.minecraft.world.entity.LivingEntity, ? extends net.minecraft.client.model.EntityModel<? extends net.minecraft.world.entity.LivingEntity>> event,
            CallbackInfo ci) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (YSMBattleMode.isBattleMode(player)) {
            return;
        }
        EntityRenderer<?> renderer = event.getRenderer();
        // Only the path where YSM re-posts the event from its own replaced
        // renderer. Vanilla PlayerRenderer (GUI previews) keeps Epic Fight's
        // inventory rendering behavior.
        if (renderer != null && renderer.getClass().getName().startsWith("com.elfmcys.yesstevemodel")
                && YSMModelAccess.getCurrentModel(player) != null) {
            ci.cancel();
        }
    }
}
