package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMBattleMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.client.event.RenderHandEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses YSM's first-person background hand rendering while the local player
 * is in Epic Fight battle mode (un-obfuscated target: the official YSM 2.6.5
 * release and OpenYSM ship RenderFirstPlayerBackground#onRenderHand(RenderHandEvent)
 * with this signature).
 *
 * The obfuscated-build counterpart is YsmBackgroundHandMixin; the ModernYSM
 * fork uses a different signature (see ModernYsmBackgroundHandMixin). This
 * injection is non-critical (require = 0).
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.event.RenderFirstPlayerBackground", remap = false)
public abstract class OpenYsmBackgroundHandMixin {

    @Inject(method = "onRenderHand(Lnet/minecraftforge/client/event/RenderHandEvent;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$suppressYsmBackgroundHandInBattleMode(RenderHandEvent event, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && YSMBattleMode.isBattleMode(player)) {
            ci.cancel();
        }
    }
}
