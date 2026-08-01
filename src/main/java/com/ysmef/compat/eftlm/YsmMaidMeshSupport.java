package com.ysmef.compat.eftlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.ysmef.compat.renderer.YSMMeshSelector;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.client.mesh.HumanoidMesh;

/**
 * Bridge between Touhou Little Maid entities and the YSM mesh library.
 *
 * TLM lets a maid wear a YSM model (its own YSM integration); the selection is
 * stored in synced entity data, readable directly on the client. When such a
 * maid is rendered through Epic Fight's pipeline (EpicFight_TouhouLittleMaid's
 * patched renderer), the converted YSM base mesh for her model is substituted.
 *
 * This class is only referenced when EpicFight_TouhouLittleMaid is installed
 * (the maid renderer mixin lives in the optional eftlm mixin config).
 */
public final class YsmMaidMeshSupport {

    private YsmMaidMeshSupport() {}

    /**
     * Select the converted YSM base mesh for the maid's current YSM model, or
     * null when the maid does not use a YSM model (or it has no converted mesh),
     * leaving EpicFight_TouhouLittleMaid's own mesh selection in place.
     */
    public static AssetAccessor<HumanoidMesh> selectMaidMesh(EntityMaid maid) {
        if (maid == null || !maid.isYsmModel()) {
            return null;
        }
        String modelId = maid.getYsmModelId();
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }
        String texture = maid.getYsmModelTexture();
        return YSMMeshSelector.selectMeshForModel(maid, modelId, texture, maid.getName().getString());
    }
}
