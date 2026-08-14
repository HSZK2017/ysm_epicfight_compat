package com.ysmef.compat.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import yesman.epicfight.client.events.engine.RenderEngine;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * TEMPORARY DIAGNOSTIC: observes renderEntityArmatureModel invocations
 * (the entry Epic Fight's RenderLivingEvent handler uses to draw patched
 * entities). Remove once the player-missing-mesh issue is resolved.
 */
@Mixin(value = RenderEngine.class, remap = false)
public abstract class RenderEngineDiagMixin {

    private static final java.util.Map<String, Integer> COUNTS = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject(method = "renderEntityArmatureModel(Lnet/minecraft/world/entity/LivingEntity;Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;Lnet/minecraft/client/renderer/entity/EntityRenderer;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;IF)V",
            at = @At("HEAD"), require = 0)
    private void ysmef$diagRenderEntityArmatureModelHead(LivingEntity entity, LivingEntityPatch<?> entitypatch,
                                                         EntityRenderer<?> renderer, MultiBufferSource buffer,
                                                         PoseStack poseStack, int packedLight, float partialTicks,
                                                         CallbackInfo ci) {
        if (!com.ysmef.compat.YsmDiag.isEnabled()) {
            return;
        }
        String key = entity.getClass().getName();
        int count = COUNTS.merge(key, 1, Integer::sum);
        if (count > 3) {
            return;
        }
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] renderEntityArmatureModel: entity={} patch={} renderer={}",
                entity.getClass().getName(),
                entitypatch != null ? entitypatch.getClass().getName() : "null",
                renderer != null ? renderer.getClass().getName() : "null");
    }
}
