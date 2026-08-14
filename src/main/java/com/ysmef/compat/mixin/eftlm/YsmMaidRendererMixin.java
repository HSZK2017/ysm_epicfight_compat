package com.ysmef.compat.mixin.eftlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.ysmef.compat.eftlm.YsmMaidMeshSupport;
import net.EFTLM.EF.Capability.MaidPatch;
import net.EFTLM.EF.Model.Mesh.MaidMesh;
import net.EFTLM.EF.Render.PatchedLivingMaidRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;

/**
 * Bytecode-level hijack of EpicFight_TouhouLittleMaid's maid mesh selection.
 *
 * PatchedLivingMaidRenderer#getMeshProvider normally picks one of EFTLM's own
 * meshes (defaulting to its WineFox mesh) regardless of the maid's YSM model.
 * When the maid wears a YSM model (TLM's synced entity data), the converted YSM
 * base mesh is substituted instead.
 *
 * Lives in the optional eftlm mixin config (required:false) so the mod still
 * loads when EpicFight_TouhouLittleMaid is absent.
 */
@Mixin(value = PatchedLivingMaidRenderer.class, remap = false)
public abstract class YsmMaidRendererMixin {

    @Inject(method = "getMeshProvider(Lnet/EFTLM/EF/Capability/MaidPatch;)Lyesman/epicfight/api/asset/AssetAccessor;",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ysmef$useYsmMesh(MaidPatch<EntityMaid> maidPatch,
                                  CallbackInfoReturnable<AssetAccessor<MaidMesh>> cir) {
        EntityMaid maid = maidPatch.getOriginal();
        if (maid == null) {
            return;
        }
        AssetAccessor<HumanoidMesh> mesh = YsmMaidMeshSupport.selectMaidMesh(maid);
        if (mesh != null) {
            @SuppressWarnings("unchecked")
            AssetAccessor<MaidMesh> result = (AssetAccessor<MaidMesh>) (AssetAccessor<?>) mesh;
            cir.setReturnValue(result);
        }
    }
}
