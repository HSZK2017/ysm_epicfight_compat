package com.ysmef.compat.event;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.renderer.YSMModelAccess;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Regenerates the converted Epic Fight base meshes whenever YSM models are
 * reloaded at runtime ("/ysm model reload" or any "ysm ... reload" command).
 *
 * The command itself is handled by YSM (obfuscated), so we listen to Forge's
 * CommandEvent, and schedule our regeneration on the client (render) thread
 * with a delay, letting YSM finish its own reload + client sync first.
 *
 * Regeneration uses the gated ensureGeneratedBlocking path (which respects
 * content fingerprints): if model files were rewritten without actual content
 * changes (e.g. mtime-only or re-encryption refresh), no mesh conversion
 * happens and the previous results are kept. After regeneration the player
 * model selection cache is cleared so the next frame picks up the latest
 * model selections immediately.
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
     */
    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        YSMModelAccess.clearCache();
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
            YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: detected '{}', scheduling base mesh regeneration", normalized);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        int pending = pendingRegenerateTicks;
        if (pending < 0) {
            return;
        }
        if (pending == 0) {
            pendingRegenerateTicks = -1;
            Minecraft.getInstance().execute(() -> {
                try {
                    YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: regenerating base meshes after YSM model reload");
                    YSMMeshLibrary.ensureGeneratedBlocking();
                    YSMModelAccess.clearCache();
                } catch (Throwable t) {
                    YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: base mesh regeneration failed", t);
                }
            });
            return;
        }
        pendingRegenerateTicks = pending - 1;
    }
}
