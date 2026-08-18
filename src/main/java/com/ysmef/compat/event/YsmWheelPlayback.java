package com.ysmef.compat.event;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.YsmExtraAnimationLibrary;
import com.ysmef.compat.renderer.YSMBattleMode;
import com.ysmef.compat.renderer.YSMModelAccess;
import com.ysmef.compat.renderer.YsmWheelAnimationState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Battle-mode wheel-animation bridge.
 *
 * YSM keeps playing its own GEO extra animation when the wheel is used. In Epic
 * Fight battle mode YSM's renderer is suppressed and the player is drawn through
 * Epic Fight's pipeline, so this handler mirrors the wheel state there:
 *
 * - battle mode + YSM extra animation active -> play the matching converted
 *   Avalon-style frame animation on the client animator's HIGHEST composite layer
 * - non-battle mode -> stop the converted animation, YSM's own renderer plays the
 *   original GEO animation as before
 *
 * The wheel state is read from YSM's client-side PlayerCapability for every
 * player, which YSM itself keeps synchronized, so no extra network protocol is
 * needed.
 */
@Mod.EventBusSubscriber(modid = YSMEpicFightCompat.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class YsmWheelPlayback {

    private static final class Tracked {
        String activeWheelAnimation;
        String activeTemplate;
        AssetAccessor<? extends StaticAnimation> accessor;
        boolean retryPending;
        boolean loggedNoEntry;
        boolean loggedNoAccessor;
        String lastDiagState;
    }

    private static final Map<UUID, Tracked> TRACKED = new ConcurrentHashMap<>();
    private static volatile boolean tickDiagLogged;

    private YsmWheelPlayback() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Register freshly generated public templates on the render thread.
        YsmExtraAnimationLibrary.clientTick();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (!tickDiagLogged) {
            tickDiagLogged = true;
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [wheel] client tick bridge active: level players={}, localPlayer='{}'",
                    mc.level.players().size(), mc.player.getGameProfile().getName());
        }
        // ClientLevel#players() usually contains the local player, but never
        // rely on that: processing the local player directly guarantees wheel
        // playback works even in edge cases where the tracking list is late.
        for (Player player : mc.level.players()) {
            tickPlayer(player);
        }
        if (!mc.level.players().contains(mc.player)) {
            tickPlayer(mc.player);
        }
    }

    private static void tickPlayer(Player player) {
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);
        if (patch == null) {
            return;
        }
        boolean battleMode = YSMBattleMode.isBattleMode(player);
        Tracked tracked = TRACKED.get(player.getUUID());
        if (!battleMode) {
            if (tracked != null) {
                stopTracked(patch, tracked);
                TRACKED.remove(player.getUUID(), tracked);
            }
            return;
        }

        YsmWheelAnimationState.State state = YsmWheelAnimationState.read(player);
        if (tracked == null) {
            tracked = new Tracked();
            TRACKED.put(player.getUUID(), tracked);
        }

        YSMModelAccess.YSMModelRef modelRef = YSMModelAccess.getCurrentModel(player);
        String modelId = modelRef == null ? "<none>" : modelRef.modelId();
        String diagState = "battle=" + battleMode + ",playing=" + state.playing()
                + ",animation=" + (state.animationName() == null ? "" : state.animationName())
                + ",model=" + modelId;
        if (!diagState.equals(tracked.lastDiagState)) {
            tracked.lastDiagState = diagState;
            tracked.loggedNoEntry = false;
            tracked.loggedNoAccessor = false;
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [wheel] state for '{}': {}",
                    player.getGameProfile().getName(), diagState);
        }

        String wheelAnimation = state.playing() ? state.animationName() : "";
        if (wheelAnimation.equals(tracked.activeWheelAnimation)) {
            if (tracked.retryPending && tracked.activeWheelAnimation != null && !tracked.activeWheelAnimation.isEmpty()) {
                tryPlay(patch, player, tracked);
            }
            return;
        }

        stopTracked(patch, tracked);
        if (wheelAnimation == null || wheelAnimation.isEmpty()) {
            return;
        }

        tracked.activeWheelAnimation = wheelAnimation;
        tracked.retryPending = true;
        tryPlay(patch, player, tracked);
    }

    private static void tryPlay(LivingEntityPatch<?> patch, Player player, Tracked tracked) {
        YSMModelAccess.YSMModelRef modelRef = YSMModelAccess.getCurrentModel(player);
        if (modelRef == null) {
            return;
        }
        YsmExtraAnimationLibrary.WheelEntry entry =
                YsmExtraAnimationLibrary.findEntry(modelRef.modelId(), tracked.activeWheelAnimation);
        if (entry == null) {
            if (!tracked.loggedNoEntry) {
                tracked.loggedNoEntry = true;
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [wheel] no converted entry for model='{}' animation='{}' (conversion may still be running)",
                        modelRef.modelId(), tracked.activeWheelAnimation);
            }
            // Conversion of this model's wheel animations is running in the
            // background; retry on the next tick.
            return;
        }
        AssetAccessor<? extends StaticAnimation> accessor =
                YsmExtraAnimationLibrary.getTemplateAccessor(entry.templateId());
        if (accessor == null) {
            if (!tracked.loggedNoAccessor) {
                tracked.loggedNoAccessor = true;
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [wheel] template '{}' for model='{}' animation='{}' is not registered yet",
                        entry.templateId(), modelRef.modelId(), tracked.activeWheelAnimation);
            }
            // Registration is queued for this render tick; retry shortly.
            return;
        }
        try {
            patch.getClientAnimator().playAnimation(accessor, 0.0F);
            tracked.accessor = accessor;
            tracked.activeTemplate = entry.templateId();
            tracked.retryPending = false;
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [wheel] playing animation '{}' (template '{}', loop={}) for '{}' in battle mode",
                    tracked.activeWheelAnimation, entry.templateId(), entry.loop(), player.getGameProfile().getName());
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: failed to play wheel animation '{}' for '{}'",
                    tracked.activeWheelAnimation, player.getGameProfile().getName(), t);
            tracked.retryPending = false;
        }
    }

    private static void stopTracked(LivingEntityPatch<?> patch, Tracked tracked) {
        AssetAccessor<? extends StaticAnimation> accessor = tracked.accessor;
        tracked.accessor = null;
        tracked.activeTemplate = null;
        tracked.activeWheelAnimation = null;
        tracked.retryPending = false;
        tracked.loggedNoEntry = false;
        tracked.loggedNoAccessor = false;
        if (accessor != null) {
            try {
                patch.getClientAnimator().stopPlaying(accessor);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Forget all per-player state (world leave). */
    public static void clear() {
        TRACKED.clear();
        tickDiagLogged = false;
    }
}
