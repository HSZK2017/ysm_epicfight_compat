package com.ysmef.compat.renderer;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central battle-mode check for the YSM-EF compat features.
 *
 * "Battle mode" means the state where Epic Fight plays its own combat animations
 * on the entity: for players that is PlayerPatch#isEpicFightMode; for other
 * patched entities (e.g. EpicFight_TouhouLittleMaid's MaidPatch) it is the
 * patch's isFightMode() method, looked up reflectively so this mod keeps no
 * compile-time dependency on those addons.
 *
 * In that state the compat mod renders the plain converted YSM base mesh only -
 * no compat script animations, no variant forms, no YSM mod rendering, no armor
 * models.
 */
public final class YSMBattleMode {

    private static final Map<Class<?>, Method> FIGHT_MODE_METHODS = new ConcurrentHashMap<>();
    private static final Method NO_FIGHT_MODE = NO_FIGHT_MODE_MARKER();
    private static boolean fightModeLookupFailed = false;

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

    /**
     * True when the given entity is currently in battle mode: players via
     * PlayerPatch#isEpicFightMode, other entities via a reflective isFightMode()
     * on their patch (e.g. EFTLM's MaidPatch).
     */
    public static boolean isBattleMode(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof Player player) {
            return isBattleMode(player);
        }
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
        if (patch == null) {
            return false;
        }
        Method method = FIGHT_MODE_METHODS.computeIfAbsent(patch.getClass(), YSMBattleMode::findFightModeMethod);
        if (method == NO_FIGHT_MODE) {
            return false;
        }
        try {
            return (boolean) method.invoke(patch);
        } catch (Exception e) {
            if (!fightModeLookupFailed) {
                fightModeLookupFailed = true;
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to query isFightMode on {}", patch.getClass().getName(), e);
            }
            return false;
        }
    }

    private static Method findFightModeMethod(Class<?> patchClass) {
        try {
            return patchClass.getMethod("isFightMode");
        } catch (NoSuchMethodException e) {
            return NO_FIGHT_MODE;
        }
    }

    private static Method NO_FIGHT_MODE_MARKER() {
        try {
            return YSMBattleMode.class.getDeclaredMethod("noFightModeMarker");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static void noFightModeMarker() {}
}
