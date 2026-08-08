package com.ysmef.compat.gpu;

import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.config.YSMCompatConfig;
import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.mixin.RenderSystemAccessorMixin;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct GPU skinning path for converted YSM-EF meshes (ported from ModernYSM's
 * GpuRenderPath, adapted to Epic Fight's SkinnedMesh data model).
 *
 * Every frame the CPU composes one combined matrix per mesh part
 * (entity pose x joint pose x toOrigin x YSM bind-space delta - the exact same
 * product Epic Fight's compute path builds in VanillaComputeShaderSetup), fills
 * the bone SSBO, and issues a single glDrawArrays through the bone-skinning
 * shader. Hidden parts are culled in the vertex shader (BoneData.isHidden), so
 * the whole model stays one draw call.
 *
 * The per-frame CPU cost is a matrix composition per part (no vertex loop), the
 * vertex skinning moves fully to the GPU and the EF per-frame pose buffer
 * upload / compute dispatch / output SSBO round trip are skipped entirely.
 *
 * Falls back to Epic Fight's compute-shader path when the GPU path is
 * unavailable (config, capability, shader compile failure, outline pass, or a
 * mesh that cannot be uploaded).
 */
public final class YsmGpuRenderPath {

    private static final float[] projScratch = new float[16];
    private static final Matrix4f projMVScratch = new Matrix4f();
    private static final Vector3f[] currentLights = new Vector3f[2];
    private static final OpenMatrix4f jointScratch = new OpenMatrix4f();

    /** GPU resources per mesh instance (one YSMMesh instance per model, shared by all players). */
    private static final Map<YSMMesh, YsmGpuMesh> GPU_MESHES = new IdentityHashMap<>();
    /** Meshes whose upload failed; never retried until the mesh is rebuilt. */
    private static final Set<YSMMesh> UNSUPPORTED = ConcurrentHashMap.newKeySet();
    /** Per-armature to-origin matrices (joint space -> model space), keyed by armature identity. */
    private static final Map<Armature, OpenMatrix4f[]> TO_ORIGIN_CACHE = new IdentityHashMap<>();
    /** Per-armature pose length, keyed by armature identity. */
    private static final Map<Armature, Integer> POSE_LENGTH_CACHE = new IdentityHashMap<>();

    private static boolean failureLogged = false;

    /** Oculus/Iris API (reflective: the compat mod has no hard dependency on Oculus). */
    private static final Class<?> IRIS_API_CLASS = findIrisApiClass();
    private static long shaderPackCheckedAtNanos = 0;
    private static boolean shaderPackInUseCache = false;

    private static Class<?> findIrisApiClass() {
        try {
            return Class.forName("net.irisshaders.iris.api.v0.IrisApi");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether a shader pack is active (Oculus/Iris). Under a shader pack the
     * custom GLSL program would bypass the pack's shaders, so the draw falls
     * back to Epic Fight's compute path, which has Iris support built in.
     * Reflective + TTL-cached (the pack state changes rarely).
     */
    private static boolean shaderPackInUse() {
        long now = System.nanoTime();
        if (now - shaderPackCheckedAtNanos < 250_000_000L) {
            return shaderPackInUseCache;
        }
        shaderPackCheckedAtNanos = now;
        boolean inUse = false;
        if (IRIS_API_CLASS != null) {
            try {
                Object instance = IRIS_API_CLASS.getMethod("getInstance").invoke(null);
                if (instance != null) {
                    inUse = Boolean.TRUE.equals(IRIS_API_CLASS.getMethod("isShaderPackInUse").invoke(instance));
                }
            } catch (Throwable ignored) {
            }
        }
        shaderPackInUseCache = inUse;
        return inUse;
    }

    private YsmGpuRenderPath() {}

    /**
     * Try to draw the mesh with the GPU skinning path. Returns true when the
     * draw happened; false lets the caller use Epic Fight's render path.
     */
    public static boolean tryRender(YSMMesh mesh, PoseStack poseStack, MultiBufferSource bufferSources,
                                    ResourceLocation texture, int packedLight, float r, float g, float b, float a,
                                    int overlay, @Nullable Armature armature, @Nullable OpenMatrix4f[] poses) {
        if (!YSMCompatConfig.ENABLE_GPU_RENDER.get()) {
            return false;
        }
        if (!YsmGpuCapability.isAvailable()) {
            logUnavailableOnce();
            return false;
        }
        if (!YsmBoneSkinShader.ensureCompiled()) {
            return false;
        }
        if (poses == null || armature == null || bufferSources instanceof OutlineBufferSource) {
            return false;
        }
        if (shaderPackInUse()) {
            // under a shader pack the custom program would bypass the pack's shaders:
            // use Epic Fight's compute path, which supports Iris/Oculus natively
            return false;
        }
        if (UNSUPPORTED.contains(mesh)) {
            return false;
        }

        YsmGpuMesh gpu = getOrBuild(mesh, poses.length);
        if (gpu == null) {
            return false;
        }
        if (gpu.vertexCount == 0 || gpu.boneCount - mesh.getPartCount() != poses.length) {
            // the armature joint layout changed since the mesh was uploaded
            return false;
        }

        try {
            fillBoneBuffer(gpu, mesh, poseStack, armature, poses, packedLight);
        } catch (Throwable t) {
            // Matrix math failure must never break the entity render: fall back.
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: GPU skinning matrix fill failed, falling back", t);
            return false;
        }

        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        Minecraft mc = Minecraft.getInstance();
        AbstractTexture modelTex = mc.getTextureManager().getTexture(texture);
        int modelTexId = modelTex.getId();

        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 2);
        mc.gameRenderer.lightTexture().turnOnLightLayer();

        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 1);
        mc.gameRenderer.overlayTexture().setupOverlayColor();
        // the overlay texture has no getter; it is what setupOverlayColor bound to unit 1
        GlStateManager._bindTexture(RenderSystem.getShaderTexture(1));

        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(modelTexId);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, gpu.boneSsbo);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, gpu.perFrameBoneBuffer);
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, YsmBoneSkinShader.SSBO, gpu.boneSsbo);

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        float[] fogColor = RenderSystem.getShaderFogColor();
        int fogShape = RenderSystem.getShaderFogShape().getIndex();

        GlStateManager._glUseProgram(YsmBoneSkinShader.program());
        if (YsmBoneSkinShader.locProj() >= 0) {
            GL20.glUniformMatrix4fv(YsmBoneSkinShader.locProj(), false, projScratch);
        }
        if (YsmBoneSkinShader.locColor() >= 0) {
            GL20.glUniform4f(YsmBoneSkinShader.locColor(), r, g, b, a);
        }
        if (YsmBoneSkinShader.locOverlay() >= 0) {
            GL20.glUniform1i(YsmBoneSkinShader.locOverlay(), overlay);
        }
        if (YsmBoneSkinShader.locFogStart() >= 0) {
            GL20.glUniform1f(YsmBoneSkinShader.locFogStart(), fogStart);
        }
        if (YsmBoneSkinShader.locFogEnd() >= 0) {
            GL20.glUniform1f(YsmBoneSkinShader.locFogEnd(), fogEnd);
        }
        if (YsmBoneSkinShader.locFogColor() >= 0) {
            GL20.glUniform4f(YsmBoneSkinShader.locFogColor(), fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        }
        if (YsmBoneSkinShader.locFogShape() >= 0) {
            GL20.glUniform1i(YsmBoneSkinShader.locFogShape(), fogShape);
        }

        refreshLights();
        if (YsmBoneSkinShader.locLight0() >= 0) {
            GL20.glUniform3f(YsmBoneSkinShader.locLight0(), currentLights[0].x, currentLights[0].y, currentLights[0].z);
        }
        if (YsmBoneSkinShader.locLight1() >= 0) {
            GL20.glUniform3f(YsmBoneSkinShader.locLight1(), currentLights[1].x, currentLights[1].y, currentLights[1].z);
        }
        if (YsmBoneSkinShader.locPartOffset() >= 0) {
            GL30.glUniform1ui(YsmBoneSkinShader.locPartOffset(), poses.length);
        }

        GlStateManager._glBindVertexArray(gpu.vao);
        boolean translucent = YSMMeshLibrary.isTranslucentTexture(texture);
        if (YsmBoneSkinShader.locAlphaMode() >= 0) {
            GL20.glUniform1i(YsmBoneSkinShader.locAlphaMode(), 1);
        }
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, gpu.vertexCount);

        if (translucent) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            if (YsmBoneSkinShader.locAlphaMode() >= 0) {
                GL20.glUniform1i(YsmBoneSkinShader.locAlphaMode(), 2);
            }
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, gpu.vertexCount);
            RenderSystem.disableBlend();
        }

        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, YsmBoneSkinShader.SSBO, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GlStateManager._glUseProgram(0);
        BufferUploader.invalidate();
        GlStateManager._glBindVertexArray(0);

        mc.gameRenderer.lightTexture().turnOffLightLayer();

        return true;
    }

    /**
     * Compose the per-frame bone buffer and fill the bone SSBO. Mirrors Epic
     * Fight's compute path structure exactly (verified numerically):
     *
     * - entries [0, jointCount): the joint matrices TOTAL_POSES[j] = poses[j] x
     *   toOrigin(j), the same OM-math product Epic Fight uploads per frame;
     * - entries [jointCount, ...): the raw per-part YSM bind-space deltas with
     *   their hidden flags (like TOTAL_POSES[poses.length + partIdx]);
     * - the vertex shader computes (joint x delta) per vertex, and u_proj is
     *   proj x mv x pose, matching EF's model_view + MC shader application.
     */
    private static void fillBoneBuffer(YsmGpuMesh gpu, YSMMesh mesh, PoseStack poseStack,
                                       Armature armature, OpenMatrix4f[] poses, int packedLight) {
        int jointCount = poses.length;
        OpenMatrix4f[] toOrigin = toOriginOf(armature, jointCount);
        if (toOrigin == null) {
            throw new IllegalStateException("armature joint layout changed");
        }

        // u_proj = projection x modelView x entityPose: the same product the
        // vanilla entity shader applies to the Epic Fight compute output.
        Matrix4f poseMatrix = poseStack.last().pose();
        projMVScratch.set(RenderSystem.getProjectionMatrix());
        projMVScratch.mul(RenderSystem.getModelViewMatrix());
        projMVScratch.mul(poseMatrix);
        projMVScratch.get(projScratch);

        ByteBuffer boneBuf = gpu.perFrameBoneBuffer;
        boneBuf.clear();

        // joints: poses[j] x toOrigin(j) in OM math (identical to EF's TOTAL_POSES)
        for (int j = 0; j < jointCount; j++) {
            jointScratch.load(poses[j]);
            jointScratch.mulBack(toOrigin[j]);
            boneBuf.position(j * YsmGpuMesh.BONE_STRIDE);
            storeMatrix(boneBuf, jointScratch, packedLight, false);
        }

        // parts: raw bind-space deltas + hidden flags (identical to EF's part slots)
        int partIdx = 0;
        for (var part : mesh.getAllParts()) {
            if (jointCount + partIdx >= gpu.boneCount) {
                break;
            }
            OpenMatrix4f delta = mesh.getPartTransform(partIdx);
            boneBuf.position((jointCount + partIdx) * YsmGpuMesh.BONE_STRIDE);
            if (delta != null) {
                storeMatrix(boneBuf, delta, packedLight, part.isHidden());
            } else {
                storeIdentity(boneBuf, packedLight, part.isHidden());
            }
            partIdx++;
        }
        boneBuf.position(0);
        boneBuf.limit(gpu.boneCount * YsmGpuMesh.BONE_STRIDE);
    }

    /** OpenMatrix4f -> SSBO row-major fields; GLSL mat4 reads them back column-major == same matrix. */
    private static void storeMatrix(ByteBuffer buf, OpenMatrix4f m, int packedLight, boolean hidden) {
        buf.putFloat(m.m00).putFloat(m.m01).putFloat(m.m02).putFloat(m.m03);
        buf.putFloat(m.m10).putFloat(m.m11).putFloat(m.m12).putFloat(m.m13);
        buf.putFloat(m.m20).putFloat(m.m21).putFloat(m.m22).putFloat(m.m23);
        buf.putFloat(m.m30).putFloat(m.m31).putFloat(m.m32).putFloat(m.m33);
        buf.putFloat(m.m00).putFloat(m.m01).putFloat(m.m02).putFloat(0.0f);
        buf.putFloat(m.m10).putFloat(m.m11).putFloat(m.m12).putFloat(0.0f);
        buf.putFloat(m.m20).putFloat(m.m21).putFloat(m.m22).putFloat(0.0f);
        buf.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).putFloat(1.0f);
        buf.putInt(packedLight);
        buf.putInt(hidden ? 1 : 0);
        buf.putInt(0);
        buf.putInt(0);
    }

    private static void storeIdentity(ByteBuffer buf, int packedLight, boolean hidden) {
        buf.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
        buf.putFloat(0).putFloat(1).putFloat(0).putFloat(0);
        buf.putFloat(0).putFloat(0).putFloat(1).putFloat(0);
        buf.putFloat(0).putFloat(0).putFloat(0).putFloat(1);
        buf.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
        buf.putFloat(0).putFloat(1).putFloat(0).putFloat(0);
        buf.putFloat(0).putFloat(0).putFloat(1).putFloat(0);
        buf.putFloat(0).putFloat(0).putFloat(0).putFloat(1);
        buf.putInt(packedLight);
        buf.putInt(hidden ? 1 : 0);
        buf.putInt(0);
        buf.putInt(0);
    }

    private static OpenMatrix4f[] toOriginOf(Armature armature, int jointCount) {
        synchronized (TO_ORIGIN_CACHE) {
            OpenMatrix4f[] cached = TO_ORIGIN_CACHE.get(armature);
            Integer cachedLen = POSE_LENGTH_CACHE.get(armature);
            if (cached != null && cachedLen != null && cachedLen == jointCount) {
                return cached;
            }
            OpenMatrix4f[] toOrigin = new OpenMatrix4f[jointCount];
            for (int j = 0; j < jointCount; j++) {
                Joint joint = armature.searchJointById(j);
                toOrigin[j] = joint != null ? joint.getToOrigin() : OpenMatrix4f.IDENTITY;
            }
            TO_ORIGIN_CACHE.put(armature, toOrigin);
            POSE_LENGTH_CACHE.put(armature, jointCount);
            return toOrigin;
        }
    }

    private static YsmGpuMesh getOrBuild(YSMMesh mesh, int jointCount) {
        synchronized (GPU_MESHES) {
            YsmGpuMesh gpu = GPU_MESHES.get(mesh);
            if (gpu != null) {
                return gpu;
            }
        }
        YsmGpuMesh built;
        try {
            built = YsmGpuMesh.build(mesh, jointCount);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: GPU mesh upload failed for '{}', using Epic Fight compute path", mesh.getRuntimeModelId(), t);
            built = null;
        }
        synchronized (GPU_MESHES) {
            YsmGpuMesh gpu = GPU_MESHES.get(mesh);
            if (gpu != null) {
                if (built != null) {
                    built.dispose();
                }
                return gpu;
            }
            if (built != null) {
                GPU_MESHES.put(mesh, built);
            }
        }
        if (built == null) {
            UNSUPPORTED.add(mesh);
        }
        return built;
    }

    /** Free the GPU resources of one mesh (eviction / reload). Must run on the render thread. */
    public static void disposeMesh(YSMMesh mesh) {
        YsmGpuMesh gpu;
        synchronized (GPU_MESHES) {
            gpu = GPU_MESHES.remove(mesh);
        }
        if (gpu != null) {
            gpu.dispose();
        }
        UNSUPPORTED.remove(mesh);
    }

    /** Free every GPU mesh and per-armature cache (resource reload). Must run on the render thread. */
    public static void disposeAll() {
        synchronized (GPU_MESHES) {
            for (YsmGpuMesh gpu : GPU_MESHES.values()) {
                try {
                    gpu.dispose();
                } catch (Throwable ignored) {
                }
            }
            GPU_MESHES.clear();
        }
        UNSUPPORTED.clear();
        synchronized (TO_ORIGIN_CACHE) {
            TO_ORIGIN_CACHE.clear();
            POSE_LENGTH_CACHE.clear();
        }
    }

    private static void refreshLights() {
        Vector3f[] arr = RenderSystemAccessorMixin.ysmef$getShaderLightDirections();
        currentLights[0] = (arr != null && arr.length > 0 && arr[0] != null)
                ? arr[0] : new Vector3f(0.2f, 1.0f, -0.7f).normalize();
        currentLights[1] = (arr != null && arr.length > 1 && arr[1] != null)
                ? arr[1] : new Vector3f(-0.2f, 1.0f, 0.7f).normalize();
    }

    private static void logUnavailableOnce() {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        YSMEpicFightCompat.LOGGER.info("YSM-EF Compat: GPU skinning path unavailable ({}), using Epic Fight's compute shader path",
                YsmGpuCapability.getReason());
    }
}
