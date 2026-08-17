package com.ysmef.compat.mixin;

import com.ysmef.compat.input.YsmInputGuard;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.client.input.DiscreteActionHandler;
import yesman.epicfight.client.input.DiscreteInputActionTrigger;

/**
 * Keeps Epic Fight from treating GUI left-clicks as combat attacks.
 *
 * Epic Fight polls its attack KeyMapping clicks every client tick through
 * DiscreteInputActionTrigger without checking whether a screen is open. Clicking
 * the YSM animation-roulette wheel therefore both selects the animation and
 * starts an Epic Fight attack. Vanilla discards attack clicks while a screen is
 * open; mirror that behavior here by draining pending clicks of any mapping bound
 * to the physical attack key without invoking the Epic Fight action handler.
 */
@Mixin(value = DiscreteInputActionTrigger.class, remap = false)
public abstract class DiscreteInputActionTriggerMixin {

    @Inject(method = "handleKeyboardAndMouse(Lnet/minecraft/client/KeyMapping;Lyesman/epicfight/api/client/input/DiscreteActionHandler;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ysmef$discardAttackClicksWhileScreenOpen(KeyMapping keyMapping,
                                                                 DiscreteActionHandler handler,
                                                                 CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null || keyMapping == null) {
            return;
        }
        boolean screenOpen = minecraft.screen != null;
        boolean guiClosedByLeftClick = !screenOpen && YsmInputGuard.consumeGuiCloseAttackClick();
        if ((screenOpen || guiClosedByLeftClick) && keyMapping.same(minecraft.options.keyAttack)) {
            while (keyMapping.consumeClick()) {
                // Drain pending clicks so the attack does not fire while a GUI is
                // open (or was just closed by this same left click).
            }
            ci.cancel();
        }
    }
}
