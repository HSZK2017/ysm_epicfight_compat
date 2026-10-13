package com.ysmef.compat.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.YsmDiag;
import com.ysmef.compat.renderer.YSMModelAccess;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Pass the current YSM hand poses through EF's unmodified item-rendering path. */
public final class YsmAnchoredItemLayer<E extends LivingEntity, T extends LivingEntityPatch<E>, M extends EntityModel<E>>
        extends PatchedItemInHandLayer<E, T, M> {

    private static final Set<String> DIAG_REPORTED = ConcurrentHashMap.newKeySet();

    @Override
    protected void renderLayer(T patch, E entity, RenderLayer<E, M> vanillaLayer, PoseStack poseStack,
                               MultiBufferSource buffer, int packedLight, OpenMatrix4f[] poses,
                               float bob, float yRot, float xRot, float partialTicks) {
        OpenMatrix4f[] itemPoses = YsmWeaponPoseBridge.posesFor(entity, patch, patch.getArmature(), poses);
        if (YsmDiag.isEnabled() && entity instanceof Player player) {
            YSMModelAccess.YSMModelRef model = YSMModelAccess.getCurrentModel(player);
            String modelId = model == null ? "none" : model.modelId();
            boolean anchored = itemPoses != poses;
            String key = modelId + "|" + vanillaLayer.getClass().getName() + "|" + anchored;
            if (DIAG_REPORTED.add(key)) {
                Joint joint = patch.getParentJointOfHand(InteractionHand.MAIN_HAND);
                int id = joint == null ? -1 : joint.getId();
                OpenMatrix4f original = id >= 0 && id < poses.length ? poses[id] : null;
                OpenMatrix4f target = id >= 0 && id < itemPoses.length ? itemPoses[id] : null;
                YSMEpicFightCompat.LOGGER.info(
                        "YSM-EF Compat: [diag] held item layer: model={} layer={} joint={} anchored={} source=({},{},{}) target=({},{},{})",
                        modelId, vanillaLayer.getClass().getName(), joint == null ? "missing" : joint.getName(),
                        anchored,
                        original == null ? "?" : original.m30, original == null ? "?" : original.m31,
                        original == null ? "?" : original.m32,
                        target == null ? "?" : target.m30, target == null ? "?" : target.m31,
                        target == null ? "?" : target.m32);
            }
        }
        boolean itemScope = YsmWeaponPoseBridge.beginItemLayer(entity, patch, patch.getArmature());
        try {
            super.renderLayer(patch, entity, vanillaLayer, poseStack, buffer, packedLight,
                    itemPoses, bob, yRot, xRot, partialTicks);
        } finally {
            YsmWeaponPoseBridge.endItemLayer(itemScope);
        }
    }
}
