package com.ysmef.compat.mixin;

import com.ysmef.compat.input.YsmInputGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects the "left click closes a screen" transition in MouseHandler#onPress.
 *
 * Vanilla dispatches the click to the open GUI first; when the GUI closes
 * itself (YSM's animation roulette does exactly that after selecting an
 * animation), MouseHandler re-checks the now-null screen and still processes
 * the same press as a game attack click. Recording the transition lets the
 * Epic Fight input mixin discard that click without affecting real game clicks.
 */
@Mixin(value = MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Unique
    private boolean ysmef$screenWasOpenOnPress;

    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), require = 0)
    private void ysmef$captureScreenState(long windowPointer, int button, int action, int modifiers, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        this.ysmef$screenWasOpenOnPress = minecraft.screen != null;
    }

    @Inject(method = "onPress(JIII)V", at = @At("RETURN"), require = 0)
    private void ysmef$notifyGuiClosedByLeftClick(long windowPointer, int button, int action, int modifiers, CallbackInfo ci) {
        if (button != 0 || action != 1 || !this.ysmef$screenWasOpenOnPress) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null) {
            YsmInputGuard.notifyGuiClosedOnLeftClick();
        }
        this.ysmef$screenWasOpenOnPress = false;
    }
}
