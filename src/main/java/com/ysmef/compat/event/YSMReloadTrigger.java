package com.ysmef.compat.event;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.network.ModelSyncClient;
import com.ysmef.compat.renderer.YSMModelAccess;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Re-validates the converted Epic Fight base meshes whenever YSM models are
 * reloaded at runtime ("/ysm model reload" or any "ysm ... reload" command).
 *
 * The command itself is handled by YSM (obfuscated), so we listen to Forge's
 * CommandEvent, and schedule the invalidation on the client (render) thread
 * with a delay, letting YSM finish its own reload + client sync first.
 *
 * Invalidation (YSMMeshLibrary.invalidateAll) drops the registered meshes and
 * compiled runtime models; the lazy per-model path then re-validates the cache
 * on the next mesh lookup - models whose files changed are re-converted in the
 * background, unchanged ones are restored from the verified cache without any
 * conversion. After invalidation the player model selection cache is cleared so
 * the next frame picks up the latest model selections immediately.
 */
@Mod.EventBusSubscriber(
        modid = YSMEpicFightCompat.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public class YSMReloadTrigger {

    /** Ticks to wait after the command before regenerating (lets YSM finish first). */
    private static final int REGENERATE_DELAY_TICKS = 40;

    private static volatile int pendingRegenerateTicks = -1;

    /**
     * Drop the per-player model selection cache when leaving a world / disconnecting,
     * so the next world always re-reads the fresh selection from its own server player
     * (a stale entry from the previous world would otherwise pin the old model - the
     * game-time TTL alone cannot detect a new world with a lower game time).
     *
     * The network-synced selections are dropped as well: they belong to the old
     * connection and are re-streamed by the server after the next handshake.
     */
    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        YSMModelAccess.clearCache();
        ModelSyncClient.clear();
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        String command = event.getParseResults().getReader().getString();
        if (command == null) {
            return;
        }
        String normalized = command.trim().replaceAll("\\s+", " ");
        if (normalized.equals("ysm model reload")
                || normalized.startsWith("ysm model reload ")
                || normalized.equals("ysm reload")
                || normalized.startsWith("ysm reload ")) {
            pendingRegenerateTicks = REGENERATE_DELAY_TICKS;
            YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: detected '{}', scheduling base mesh invalidation", normalized);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // ModernYSM-style delayed texture release: drop evicted textures only
        // after a few ticks so the current frame's draws never lose them mid-frame
        YSMMeshLibrary.processPendingTextureReleases();
        int pending = pendingRegenerateTicks;
        if (pending < 0) {
            return;
        }
        if (pending == 0) {
            pendingRegenerateTicks = -1;
            Minecraft.getInstance().execute(() -> {
                try {
                    YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: invalidating base meshes after YSM model reload");
                    YSMMeshLibrary.invalidateAll();
                    YSMMeshLibrary.preparePackFolder();
                    YSMModelAccess.clearCache();
                } catch (Throwable t) {
                    YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: base mesh invalidation failed", t);
                }
            });
            return;
        }
        pendingRegenerateTicks = pending - 1;
    }
}
