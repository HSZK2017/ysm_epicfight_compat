package com.ysmef.compat.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * TEMPORARY DIAGNOSTIC: observes the real render flow of Epic Fight's patched
 * living renderer. Together with RenderEngineDiagMixin it brackets the exact
 * place where the player mesh draw disappears: if the head log fires but the
 * getMeshProvider logs (YSMPlayerRenderer) never do, the bail is inside this
 * method; if the head log never fires for players, the dispatch never reaches
 * this renderer at all.
 *
 * Remove once the player-missing-mesh issue is resolved.
 */
@Mixin(value = PatchedLivingEntityRenderer.class, remap = false)
public abstract class PatchedLivingRenderDiagMixin {

    private static final java.util.Map<String, Integer> COUNTS = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/PoseStack;IF)V",
            at = @At("HEAD"), require = 0)
    private void ysmef$diagRenderHead(LivingEntity entity, LivingEntityPatch<?> entitypatch,
                                      LivingEntityRenderer<?, ?> renderer, MultiBufferSource buffer,
                                      PoseStack poseStack, int packedLight, float partialTicks, CallbackInfo ci) {
        if (!com.ysmef.compat.YsmDiag.isEnabled()) {
            return;
        }
        String key = entity.getClass().getName();
        int count = COUNTS.merge(key, 1, Integer::sum);
        if (count > 3) {
            return;
        }
        org.joml.Matrix4f pose = poseStack.last().pose();
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] patchedLiving.render head: entity={} renderer={} "
                        + "poseStack=(m00={},m11={},m22={},m30={},m31={},m32={}) "
                        + "stack4={} stack5={} stack6={}",
                entity.getClass().getName(), renderer != null ? renderer.getClass().getName() : "null",
                pose.m00(), pose.m11(), pose.m22(), pose.m30(), pose.m31(), pose.m32(),
                stackFrame(4), stackFrame(5), stackFrame(6));
    }

    private static String stackFrame(int depth) {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        if (trace == null || depth >= trace.length) {
            return "?";
        }
        StackTraceElement el = trace[depth];
        String simple = el.getClassName();
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        return simple + "." + el.getMethodName() + ":" + el.getLineNumber();
    }
}
