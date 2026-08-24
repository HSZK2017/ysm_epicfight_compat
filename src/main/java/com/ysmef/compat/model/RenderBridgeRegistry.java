package com.ysmef.compat.model;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup;

/**
 * Render-path bridges used by {@link YSMMesh#draw} to dispatch to the GPU /
 * CPU / Iris skinning paths without importing the gpu/cpu packages (breaking
 * the model &lt;-&gt; gpu/cpu package cycle; the render paths keep their one-way
 * data dependency on the model package).
 *
 * Implementations are registered through {@link RenderBridgeRegistry} in their
 * static initializer (render thread, before any draw can need them).
 */
public final class RenderBridgeRegistry {

    /** The direct GPU skinning path (bone SSBO + skinning shader). */
    public interface GpuSkinRender {
        boolean tryRender(YSMMesh mesh, PoseStack poseStack, MultiBufferSource bufferSources,
                          ResourceLocation texture, int packedLight,
                          float r, float g, float b, float a, int overlay,
                          Armature armature, OpenMatrix4f[] poses);

        boolean isGuiEntityProjection();

        boolean isYsmPreviewMode();
    }

    /** The CPU skinning fallback path (CPU skin -> dynamic VBO -> cpu_skin shader). */
    public interface CpuSkinRender {
        boolean isForced();

        boolean tryRender(YSMMesh mesh, PoseStack poseStack, Mesh.DrawingFunction drawingFunction,
                          int packedLight, float r, float g, float b, float a, int overlay,
                          Armature armature, OpenMatrix4f[] poses);

        boolean tryRenderLastResort(YSMMesh mesh, PoseStack poseStack, Mesh.DrawingFunction drawingFunction,
                                    int packedLight, float r, float g, float b, float a, int overlay,
                                    Armature armature, OpenMatrix4f[] poses);

        void pushCapturePass();

        void popCapturePass();
    }

    /** The optimized Iris compute path (joint-only pose uploads, no MAX_JOINTS cap). */
    public interface IrisSkinRender {
        boolean tryRender(YSMMesh mesh, ComputeShaderSetup setup, PoseStack poseStack,
                          MultiBufferSource bufferSources, RenderType renderType, int packedLight,
                          float r, float g, float b, float a, int overlay,
                          Armature armature, OpenMatrix4f[] poses);
    }

    private static volatile GpuSkinRender gpu;
    private static volatile CpuSkinRender cpu;
    private static volatile IrisSkinRender iris;

    private RenderBridgeRegistry() {}

    public static void registerGpu(GpuSkinRender bridge) {
        gpu = bridge;
    }

    public static void registerCpu(CpuSkinRender bridge) {
        cpu = bridge;
    }

    public static void registerIris(IrisSkinRender bridge) {
        iris = bridge;
    }

    public static GpuSkinRender gpu() {
        return gpu;
    }

    public static CpuSkinRender cpu() {
        return cpu;
    }

    public static IrisSkinRender iris() {
        return iris;
    }
}
