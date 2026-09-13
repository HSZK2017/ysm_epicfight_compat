package com.ysmef.compat.renderer;

import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The decision behind suppressing YSM's extra player render, shared by the two
 * mixins that reach that method in the two YSM class layouts.
 *
 * <p>Kept out of the mixins themselves so the rule is written once and cannot drift
 * between the readable and the obfuscated path - those two must agree, since which
 * one applies depends only on which YSM build is installed.
 */
@OnlyIn(Dist.CLIENT)
public final class YsmExtraPlayerOverlaySupport {

    /** Set once the suppression has actually fired and been reported. */
    private static final AtomicBoolean SUPPRESSION_LOGGED = new AtomicBoolean();

    private YsmExtraPlayerOverlaySupport() {}

    /**
     * Whether the paperdoll should be skipped for this player right now: the user
     * asked for it (config {@code disableExtraPlayerInBattleMode}, default on) and
     * the player is in Epic Fight battle mode, where the model is already visible
     * in-world and the overlay costs a second full Epic Fight render pipeline per
     * frame.
     *
     * @param via which mixin reached the paperdoll, for the one-time log line
     */
    public static boolean shouldSuppress(LocalPlayer localPlayer, String via) {
        if (localPlayer == null) {
            return false;
        }
        try {
            if (!YSMCompatConfig.DISABLE_EXTRA_PLAYER_IN_BATTLE_MODE.get()) {
                return false;
            }
            if (!YSMBattleMode.isBattleMode(localPlayer)) {
                return false;
            }
            logSuppressionOnce(via);
            return true;
        } catch (Throwable t) {
            // Config not available yet: leave the overlay alone rather than guess.
            return false;
        }
    }

    /**
     * Says once per session that the suppression is actually firing, naming the path
     * that reached the paperdoll.
     *
     * <p>Without this the feature is invisible, and its two possible failures look
     * identical in game: either the option is working, or neither mixin matched this
     * YSM build and the paperdoll simply keeps showing. The line distinguishes them,
     * and the named path also tells a bug report which class layout the installed
     * build has.
     */
    private static void logSuppressionOnce(String via) {
        if (!SUPPRESSION_LOGGED.compareAndSet(false, true)) {
            return;
        }
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: suppressing YSM's extra player render in battle mode (reached via {}, config disableExtraPlayerInBattleMode)",
                via);
    }
}
