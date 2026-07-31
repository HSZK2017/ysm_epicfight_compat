package com.ysmef.compat.renderer;

import net.minecraft.world.entity.player.Player;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;

/**
 * Central battle-mode check for the YSM-EF compat features.
 *
 * "Battle mode" is Epic Fight's combat stance (PlayerPatch#isEpicFightMode): the
 * state where Epic Fight plays its own combat animations on the player. In that
 * state the compat mod renders the plain converted YSM base mesh only - no compat
 * script animations, no variant forms, no YSM mod rendering, no armor models.
 */
public final class YSMBattleMode {

    private YSMBattleMode() {}

    /**
     * True when the given player is currently in Epic Fight battle mode.
     */
    public static boolean isBattleMode(Player player) {
        if (player == null) {
            return false;
        }
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(player, LivingEntityPatch.class);
        return patch instanceof PlayerPatch<?> playerPatch && playerPatch.isEpicFightMode();
    }
}
