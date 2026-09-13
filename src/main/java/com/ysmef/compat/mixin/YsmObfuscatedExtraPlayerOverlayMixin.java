package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YsmExtraPlayerOverlaySupport;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses YSM's extra player render (the corner paperdoll overlay) in battle
 * mode for the OBFUSCATED YSM layouts - the counterpart of
 * {@link YsmExtraPlayerOverlayMixin}, which covers the builds that keep the
 * {@code client.renderer.ModelPreviewRenderer} name.
 *
 * <h2>How the target was derived</h2>
 *
 * <p>The obfuscated build renames the method as well as the class (no class in it
 * contains the string {@code renderPlayerOverlay}), so the target cannot be looked up
 * by name. It was followed down the paperdoll's call chain instead, which is stable
 * because the descriptor survives obfuscation:
 *
 * <ol>
 *   <li>The overlay is registered as a Forge HUD overlay, so the entry point is the
 *       mod's {@code IGuiOverlay} implementation - the only class implementing
 *       {@code IGuiOverlay} in the mod, found by shape rather than by name.</li>
 *   <li>Its single {@code render} method was disassembled. After reading its own
 *       config values it makes exactly one call into the mod with the paperdoll's
 *       argument list, described as
 *       {@code (Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/player/LocalPlayer;DDFFIF)V}
 *       - the same descriptor the readable build's {@code renderPlayerOverlay} has,
 *       because obfuscation renames members but does not rewrite descriptors.</li>
 *   <li>That call's owner class is the target here.</li>
 * </ol>
 *
 * <p>Note the trap this derivation avoids: the method NAME at that call site is
 * {@code Oo0Oo0o00O00Oo0OOoOOoooo}, and the obfuscator reuses that same name for
 * several unrelated members within this package (it appears with an {@code Entity,
 * PoseStack, float} signature and a generic animatable signature elsewhere, and even
 * on the config-accessor class). Matching the name alone would inject into the wrong
 * method, which is why the method string below carries the full descriptor.
 *
 * <p>{@code require = 0} and a string target keep this safe: on a build where the
 * class has been re-obfuscated the mixin logs a warning and is skipped, leaving the
 * behaviour to the readable-path mixin or, if neither matches, unchanged.
 *
 * <p>The class and method names were derived from the official YSM 2.6.5 release
 * shipped with the test instance. A future obfuscated release will need them
 * re-derived by repeating the chain above; the shape-based class discovery this mod
 * already performs (see {@code YsmClasses}) is what makes re-deriving cheap, and it
 * is also the intended upgrade path if names must stop being hardcoded here.
 */
@Mixin(targets = "com.elfmcys.yesstevemodel.OoO00Oo00Ooo0OoOoo00o000", remap = false)
public abstract class YsmObfuscatedExtraPlayerOverlayMixin {

    /**
     * The paperdoll render. The method name is the obfuscated one read off the call
     * site, and the descriptor after the name is what actually selects the method.
     */
    @Inject(
            method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/player/LocalPlayer;DDFFIF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private static void ysmef$suppressObfuscatedExtraPlayerInBattleMode(
            GuiGraphics guiGraphics, LocalPlayer localPlayer,
            double x, double y, float scale, float yawOffset,
            int zDepth, float partialTick, CallbackInfo ci) {
        if (YsmExtraPlayerOverlaySupport.shouldSuppress(localPlayer, "obfuscated layout")) {
            ci.cancel();
        }
    }
}
