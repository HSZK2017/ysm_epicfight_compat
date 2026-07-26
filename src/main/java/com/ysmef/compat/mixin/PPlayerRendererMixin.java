package com.ysmef.compat.mixin;

import com.ysmef.compat.renderer.YSMMeshSelector;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.patched.entity.PPlayerRenderer;
import yesman.epicfight.client.world.capabilites.entitypatch.player.AbstractClientPlayerPatch;

/**
 * Bytecode-level hijack of Epic Fight's player renderer mesh selection.
 *
 * Epic Fight's PPlayerRenderer#getMeshProvider normally returns the vanilla biped
 * (or slim-arm) mesh regardless of the player's YSM model. This mixin injects at
 * the head of that method and substitutes the converted YSM base mesh when the
 * rendered player has a YSM model active.
 *
 * Because the injection targets Epic Fight's own class directly, it applies no
 * matter which patched renderer ended up registered for the player entity type
 * (ours or another addon's), as long as it derives from PPlayerRenderer or the
 * registered renderer is PPlayerRenderer itself.
 */
@Mixin(value = PPlayerRenderer.class, remap = false)
public abstract class PPlayerRendererMixin {

    @Inject(
            method = "getMeshProvider(Lyesman/epicfight/client/world/capabilites/entitypatch/player/AbstractClientPlayerPatch;)Lyesman/epicfight/api/asset/AssetAccessor;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void ysmef$useYsmMesh(AbstractClientPlayerPatch<AbstractClientPlayer> entitypatch,
                                  CallbackInfoReturnable<AssetAccessor<HumanoidMesh>> cir) {
        AssetAccessor<HumanoidMesh> mesh = YSMMeshSelector.selectMesh(entitypatch.getOriginal());
        if (mesh != null) {
            cir.setReturnValue(mesh);
        }
    }
}
