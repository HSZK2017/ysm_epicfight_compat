package com.ysmef.compat.mixin;

import com.ysmef.compat.model.AnimationRegistryGuard;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.network.client.CPCheckAnimationRegistryMatches;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Server-side exemption for Epic Fight's animation registry consistency check.
 *
 * <p>When a player joins, Epic Fight 20.14.17 compares the client's animation
 * registry against the server's and disconnects the player on ANY mismatch
 * ({@code gui.epicfight.warn.animation_unsync}). This mod registers wheel
 * templates ({@code ysm_epicfight_compat:public/pub_*}) at runtime on each
 * client from that client's own YSM model data, so those ids can never exist
 * on a dedicated server and no two machines agree. Without an exemption every
 * player using the wheel bridge is kicked on join.
 *
 * <p>This mirrors the original validation but ignores this mod's generated
 * templates on BOTH sides: templates registered on the server (integrated
 * server shares the client's AnimationManager instance) and templates a
 * joining client registered locally.
 */
@Mixin(value = AnimationManager.class, remap = false)
public abstract class AnimationManagerValidationMixin {

    @Shadow
    private Map<AnimationAccessor<? extends StaticAnimation>, StaticAnimation> animations;

    @Inject(
            method = "validateClientAnimationRegistry(Lyesman/epicfight/network/client/CPCheckAnimationRegistryMatches;Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void ysmef$ignoreGeneratedTemplatesInRegistryValidation(
            CPCheckAnimationRegistryMatches msg, ServerGamePacketListenerImpl connection, CallbackInfo ci) {
        StringBuilder messageBuilder = new StringBuilder();
        int count = 0;

        Set<String> clientAnimationRegistry = new HashSet<>(Set.of(msg.registryNames));
        clientAnimationRegistry.removeIf(AnimationRegistryGuard::shouldIgnore);

        for (AnimationAccessor<? extends StaticAnimation> accessor : this.animations.keySet()) {
            String registryName = accessor.toString();
            if (AnimationRegistryGuard.shouldIgnore(registryName)) {
                continue;
            }
            if (!clientAnimationRegistry.contains(registryName)) {
                // Animations that don't exist in client
                if (count < 10) {
                    messageBuilder.append(registryName);
                    messageBuilder.append("\n");
                }
                count++;
            } else {
                clientAnimationRegistry.remove(registryName);
            }
        }

        // Animations that don't exist in server
        for (String registryName : clientAnimationRegistry) {
            if (registryName.equals("empty")) {
                continue;
            }
            if (count < 10) {
                messageBuilder.append(registryName);
                messageBuilder.append("\n");
            }
            count++;
        }

        if (count >= 10) {
            messageBuilder.append(Component.translatable("gui.epicfight.warn.animation_unsync.etc", (count - 9)).getString());
            messageBuilder.append("\n");
        }

        if (!messageBuilder.isEmpty()) {
            connection.disconnect(Component.translatable("gui.epicfight.warn.animation_unsync", messageBuilder.toString()));
        }
        ci.cancel();
    }
}
