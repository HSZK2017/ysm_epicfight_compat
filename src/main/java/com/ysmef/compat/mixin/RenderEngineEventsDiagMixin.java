package com.ysmef.compat.mixin;

import net.minecraftforge.client.event.RenderLivingEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.client.ClientEngine;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * TEMPORARY DIAGNOSTIC: observes Epic Fight's own RenderLivingEvent handler.
 * For the local player the handler must call renderEntityArmatureModel, but
 * the render-entry diagnostic shows it never does - this mixin logs what the
 * handler itself sees (level, hasRendererFor, patch, overrideRender) so the
 * failing condition can be identified exactly.
 *
 * Remove once the player-missing-mesh issue is resolved.
 */
@Mixin(targets = "yesman.epicfight.client.events.engine.RenderEngine$Events", remap = false)
public abstract class RenderEngineEventsDiagMixin {

    private static final java.util.Map<String, Integer> COUNTS = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject(method = "renderLivingEvent(Lnet/minecraftforge/client/event/RenderLivingEvent$Pre;)V",
            at = @At("HEAD"), require = 0)
    private static void ysmef$diagRenderLivingEventHead(RenderLivingEvent.Pre<?, ?> event, CallbackInfo ci) {
        if (!com.ysmef.compat.YsmDiag.isEnabled()) {
            return;
        }
        net.minecraft.world.entity.LivingEntity entity = event.getEntity();
        String key = entity.getClass().getName();
        int count = COUNTS.merge(key, 1, Integer::sum);
        if (count > 3) {
            return;
        }
        boolean levelNull = entity.level() == null;
        boolean hasRenderer = ClientEngine.getInstance().renderEngine.hasRendererFor(entity);
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
        org.joml.Matrix4f pose = event.getPoseStack().last().pose();
        net.minecraft.world.phys.Vec3 epos = entity.position();
        net.minecraft.world.phys.Vec3 cpos = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] EF renderLivingEvent: entity={} partialTick={} levelNull={} hasRenderer={} patch={} overrideRender={} canceled={} "
                        + "poseStack=(m00={},m11={},m22={},m30={},m31={},m32={}) "
                        + "entityPos=({},{},{}) cameraPos=({},{},{}) delta=({},{},{}) "
                        + "stack0={} stack1={} stack2={} stack3={} stack4={} stack5={} stack6={} stack7={}",
                entity.getClass().getName(), event.getPartialTick(), levelNull, hasRenderer,
                patch != null ? patch.getClass().getName() : "null",
                patch != null && patch.overrideRender(),
                event.isCanceled(),
                pose.m00(), pose.m11(), pose.m22(), pose.m30(), pose.m31(), pose.m32(),
                String.format("%.2f", epos.x), String.format("%.2f", epos.y), String.format("%.2f", epos.z),
                String.format("%.2f", cpos.x), String.format("%.2f", cpos.y), String.format("%.2f", cpos.z),
                String.format("%.2f", epos.x - cpos.x), String.format("%.2f", epos.y - cpos.y), String.format("%.2f", epos.z - cpos.z),
                stackFrame(2), stackFrame(3), stackFrame(4), stackFrame(5), stackFrame(6), stackFrame(7), stackFrame(8), stackFrame(9));
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
