package com.ysmef.compat.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.compat.renderer.YSMModelAccess;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
import yesman.epicfight.client.renderer.patched.layer.WearableItemLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * Armor layer that skips rendering while the player uses a converted YSM mesh.
 *
 * Epic Fight's WearableItemLayer draws the vanilla armor models, which are shaped
 * for the vanilla biped and float/misalign badly on YSM models. For players without
 * a YSM model the behavior is identical to Epic Fight's default.
 */
public class YsmConditionalArmorLayer<E extends LivingEntity, T extends LivingEntityPatch<E>, M extends HumanoidModel<E>, AM extends HumanoidMesh>
        extends PatchedLayer<E, T, M, HumanoidArmorLayer<E, M, M>> {

    private final WearableItemLayer<E, T, M, AM> delegate;

    public YsmConditionalArmorLayer(AssetAccessor<AM> mesh, ModelManager modelManager) {
        this.delegate = new WearableItemLayer<>(mesh, false, modelManager);
    }

    @Override
    public void renderLayer(E entity, T entitypatch, RenderLayer<E, M> layer, PoseStack poseStack,
                            MultiBufferSource buffer, int packedLight, OpenMatrix4f[] poses,
                            float bob, float yRot, float xRot, float partialTicks) {
        if (entity instanceof Player player && YSMModelAccess.getCurrentModel(player) != null) {
            return;
        }
        this.delegate.renderLayer(entity, entitypatch, layer, poseStack, buffer, packedLight,
                poses, bob, yRot, xRot, partialTicks);
    }

    @Override
    protected void renderLayer(T entitypatch, E entity, HumanoidArmorLayer<E, M, M> layer, PoseStack poseStack,
                               MultiBufferSource buffer, int packedLight, OpenMatrix4f[] poses,
                               float bob, float yRot, float xRot, float partialTicks) {
        // Rendering is delegated through the public entry point above.
    }
}
