package com.ysmef.compat.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards YSM's LivingMovementAnimationPredicate#renderRidingAnimation against
 * non-player animatables.
 *
 * YSM registers this predicate for both players and TLM maids, but the method
 * unconditionally casts the animatable's entity to Player:
 * {@code Player player = (Player) ((LivingAnimatable) event.getAnimatable()).getEntity();}
 * Rendering a YSM-model maid in a GUI entity preview (for example the maid
 * tooltip in the creative inventory) therefore crashes with
 * "EntityMaid cannot be cast to Player".
 *
 * The redirected getEntity() call is made player-safe: non-player entities are
 * returned as null, and the original code's existing null check then exits the
 * riding-animation branch cleanly. The redirect uses string targets and
 * reflection because the libs YSM jar on the compile classpath is obfuscated.
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.animation.predicate.LivingMovementAnimationPredicate", remap = false)
public abstract class YsmLivingMovementPredicateMixin {

    private static volatile boolean NON_PLAYER_GUARD_LOGGED = false;
    private static volatile boolean GUARD_FAILED_LOGGED = false;
    private static final Map<Class<?>, Method> GET_ENTITY_METHODS = new ConcurrentHashMap<>();

    @Redirect(
            method = "renderRidingAnimation(Lcom/elfmcys/yesstevemodel/geckolib3/core/event/predicate/AnimationEvent;)Lcom/elfmcys/yesstevemodel/geckolib3/core/enums/PlayState;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/elfmcys/yesstevemodel/client/entity/LivingAnimatable;getEntity()Lnet/minecraft/world/entity/Entity;"
            ),
            require = 0
    )
    private Entity ysmef$guardLivingAnimatableEntity(@Coerce Object animatable) {
        return playerOrNull(animatable);
    }

    @Redirect(
            method = "renderRidingAnimation(Lcom/elfmcys/yesstevemodel/geckolib3/core/event/predicate/AnimationEvent;)Lcom/elfmcys/yesstevemodel/geckolib3/core/enums/PlayState;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/elfmcys/yesstevemodel/geckolib3/core/AnimatableEntity;getEntity()Lnet/minecraft/world/entity/Entity;"
            ),
            require = 0
    )
    private Entity ysmef$guardAnimatableEntity(@Coerce Object animatable) {
        return playerOrNull(animatable);
    }

    /** Returns the entity only when it is a Player; null for maids/previews. */
    private static Entity playerOrNull(Object animatable) {
        if (animatable == null) {
            return null;
        }
        try {
            Method getEntity = GET_ENTITY_METHODS.get(animatable.getClass());
            if (getEntity == null) {
                for (Method candidate : animatable.getClass().getMethods()) {
                    if ("getEntity".equals(candidate.getName())
                            && candidate.getParameterCount() == 0
                            && Entity.class.isAssignableFrom(candidate.getReturnType())) {
                        getEntity = candidate;
                        break;
                    }
                }
                if (getEntity == null) {
                    if (!GUARD_FAILED_LOGGED) {
                        GUARD_FAILED_LOGGED = true;
                        com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                                "YSM-EF Compat: cannot find getEntity() on {}; YSM riding animations will be skipped",
                                animatable.getClass().getName());
                    }
                    return null;
                }
                GET_ENTITY_METHODS.put(animatable.getClass(), getEntity);
            }
            Object value = getEntity.invoke(animatable);
            if (value instanceof Player player) {
                return player;
            }
            if (value instanceof Entity && !NON_PLAYER_GUARD_LOGGED) {
                NON_PLAYER_GUARD_LOGGED = true;
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: YSM riding-animation predicate received non-player entity {}; treating it as no-vehicle (prevents the EntityMaid -> Player cast crash)",
                        value.getClass().getName());
            }
            return null;
        } catch (Throwable t) {
            if (!GUARD_FAILED_LOGGED) {
                GUARD_FAILED_LOGGED = true;
                com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to inspect YSM riding-animation animatable; skipping riding animation", t);
            }
            return null;
        }
    }
}
