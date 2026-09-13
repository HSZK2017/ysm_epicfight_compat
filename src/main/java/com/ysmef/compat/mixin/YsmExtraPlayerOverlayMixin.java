package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YsmExtraPlayerOverlaySupport;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses YSM's extra player render (the corner paperdoll overlay) while the
 * local player is in Epic Fight battle mode - the layout where the target keeps its
 * readable class and method names.
 *
 * <p>The paperdoll renders the player through the entity render dispatcher in a GUI
 * context every frame. In battle mode that dispatches to this mod's patched Epic
 * Fight renderer, so each paperdoll frame runs a SECOND full EF render pipeline
 * (armature pose, patched layers, converted mesh draw) on top of the in-world render
 * - the measured cause of the 20-30 FPS vs 100+ FPS drop when the paperdoll is
 * enabled. The player model is already visible in-world during battle, so the
 * overlay is suppressed by default (config: disableExtraPlayerInBattleMode).
 *
 * <p>This class covers the official YSM 2.6.5 release, OpenYSM and ModernYSM, whose
 * {@code ModelPreviewRenderer} is un-obfuscated. The obfuscated variants of the same
 * mod are covered by {@link YsmObfuscatedExtraPlayerOverlayMixin}, whose target was
 * derived by following the paperdoll's call chain rather than by name; the decision
 * itself is shared through {@link YsmExtraPlayerOverlaySupport}.
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.client.renderer.ModelPreviewRenderer", remap = false)
public abstract class YsmExtraPlayerOverlayMixin {

    @Inject(
            method = "renderPlayerOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/player/LocalPlayer;DDFFIF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void ysmef$suppressExtraPlayerInBattleMode(GuiGraphics guiGraphics, LocalPlayer localPlayer,
                                                              double x, double y, float scale, float yawOffset,
                                                              int zDepth, float partialTick, CallbackInfo ci) {
        if (YsmExtraPlayerOverlaySupport.shouldSuppress(localPlayer, "readable ModelPreviewRenderer")) {
            ci.cancel();
        }
    }
}
