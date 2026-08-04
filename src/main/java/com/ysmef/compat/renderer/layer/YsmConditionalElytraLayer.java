package com.ysmef.compat.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.compat.renderer.YSMModelAccess;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.layer.PatchedElytraLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * Elytra layer that skips rendering while the player uses a converted YSM mesh.
 *
 * Epic Fight's PatchedElytraLayer draws the vanilla elytra wings on the Chest joint,
 * which are shaped for the vanilla biped and misalign on YSM models. For players
 * without a YSM model the behavior is identical to Epic Fight's default.
 */
public class YsmConditionalElytraLayer<E extends LivingEntity, T extends LivingEntityPatch<E>, M extends EntityModel<E>>
        extends PatchedLayer<E, T, M, ElytraLayer<E, M>> {

    private final PatchedElytraLayer<E, T, M> delegate = new PatchedElytraLayer<>();

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
    protected void renderLayer(T entitypatch, E entity, ElytraLayer<E, M> layer, PoseStack poseStack,
                               MultiBufferSource buffer, int packedLight, OpenMatrix4f[] poses,
                               float bob, float yRot, float xRot, float partialTicks) {
        // Rendering is delegated through the public entry point above.
    }
}
