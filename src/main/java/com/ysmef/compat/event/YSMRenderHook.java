package com.ysmef.compat.event;

import com.ysmef.compat.YSMEpicFightCompat;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.client.world.capabilites.entitypatch.player.LocalPlayerPatch;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * Render-time bridge between YSM and Epic Fight.
 *
 * YSM replaces the vanilla player render by listening to RenderPlayerEvent.Pre at
 * NORMAL priority and drawing its own CustomPlayerRenderer. In Epic Fight battle
 * mode that renderer is undesirable: it re-posts a RenderLivingEvent.Pre with a
 * renderer whose layer list lacks PlayerItemInHandLayer, so Epic Fight's
 * PatchedItemInHandLayer never runs on that path and the held weapon never renders
 * (YsmPlayerRenderMixin / OpenYsmPlayerRenderMixin / ModernYsmPlayerRenderMixin
 * suppress YSM's interception for the same reason).
 *
 * This handler therefore takes over the player draw at HIGHEST priority in battle
 * mode: it draws the player through Epic Fight's pipeline (renderEntityArmatureModel)
 * and cancels the event, so neither the vanilla model nor YSM's render path runs.
 *
 * NOTE: the draw MUST happen here. Epic Fight's own RenderLivingEvent handler is
 * registered with the default receiveCanceled=false, so once this handler cancels
 * the event the bus skips it entirely - leaving the draw to it renders nothing
 * (verified empirically: the player then has no body at all). Drawing here and
 * canceling yields exactly one draw, because Epic Fight's handler never runs for
 * the canceled event.
 *
 * The draw is skipped when Epic Fight has no patched renderer for the player
 * (hasRendererFor), when the player's level is null (loading screen: Epic Fight's
 * own render path bails there too) and in vanilla-model-debugging mode.
 */
@Mod.EventBusSubscriber(
        modid = YSMEpicFightCompat.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public class YSMRenderHook {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        // This bridge is only for Epic Fight battle mode. Outside battle mode
        // YSM must keep rendering its own GEO model; taking over there makes
        // the converted EF mesh and YSM's renderer fight over the same entity
        // (models with many animated variants, e.g. Wither2.3, visibly overlap).
        if (!com.ysmef.compat.renderer.YSMBattleMode.isBattleMode(player)) {
            return;
        }
        if (!(event.getRenderer() instanceof PlayerRenderer)) {
            return;
        }

        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);
        if (patch == null || !patch.overrideRender()) {
            return;
        }
        if (player.level() == null) {
            return;
        }
        ClientEngine clientEngine = ClientEngine.getInstance();
        if (clientEngine.isVanillaModelDebuggingMode()) {
            return;
        }
        if (!clientEngine.renderEngine.hasRendererFor(player)) {
            return;
        }

        logTakeoverOnce(player);

        float partialTick = event.getPartialTick();
        boolean guiLikeRender = (partialTick == 0.0F || partialTick == 1.0F);

        if (guiLikeRender && patch instanceof LocalPlayerPatch localPlayerPatch) {
            float originalYRot = localPlayerPatch.getModelYRot();
            localPlayerPatch.setModelYRotInGui(player.getYRot());
            event.getPoseStack().translate(0.0D, 0.1D, 0.0D);
            clientEngine.renderEngine.renderEntityArmatureModel(player, patch, event.getRenderer(),
                    event.getMultiBufferSource(), event.getPoseStack(), event.getPackedLight(), partialTick);
            event.setCanceled(true);
            localPlayerPatch.disableModelYRotInGui(originalYRot);
        } else {
            clientEngine.renderEngine.renderEntityArmatureModel(player, patch, event.getRenderer(),
                    event.getMultiBufferSource(), event.getPoseStack(), event.getPackedLight(), partialTick);
            event.setCanceled(true);
        }
    }

    private static final java.util.Set<String> LOGGED_PLAYERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void logTakeoverOnce(Player player) {
        if (LOGGED_PLAYERS.add(player.getGameProfile().getName())) {
            YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: taking over player rendering for '{}' via Epic Fight pipeline",
                    player.getGameProfile().getName());
        }
    }
}
