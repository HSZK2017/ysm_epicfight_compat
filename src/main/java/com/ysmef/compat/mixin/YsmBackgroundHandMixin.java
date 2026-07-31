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
 * is in Epic Fight battle mode.
 *
 * The target is YSM's RenderFirstPlayerBackground#onRenderHand. YSM's release jar
 * is obfuscated, so the obfuscated class/method names are used; they can be
 * re-derived for other YSM versions by scanning the jar for classes referencing
 * Lnet/minecraftforge/client/event/RenderHandEvent;.
 */
@Mixin(value = com.elfmcys.yesstevemodel.O000O0O00ooo000O0oOOoo00.class, remap = false)
public abstract class YsmBackgroundHandMixin {

    @Inject(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraftforge/client/event/RenderHandEvent;)V",
            at = @At("HEAD"), cancellable = true)
    private static void ysmef$suppressYsmBackgroundHandInBattleMode(RenderHandEvent event, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && YSMBattleMode.isBattleMode(player)) {
            ci.cancel();
        }
    }
}
