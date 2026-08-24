package com.ysmef.compat.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.YSMMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.GLConstants;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup;
import yesman.epicfight.client.renderer.shader.compute.backend.program.ComputeProgram;
import yesman.epicfight.client.renderer.shader.compute.iris.IrisComputeShaderSetup;
import yesman.epicfight.client.renderer.shader.compute.loader.ComputeShaderProvider;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optimized replacement for Epic Fight's IrisComputeShaderSetup draw loop
 * (used whenever Oculus/Iris is present and the mesh's compute setup is the
 * Iris variant). The visual output is identical - Epic Fight's own
 * iris_mesh_transformer.comp skins the mesh into an Iris-format output SSBO and
 * the pack's entity shader (RenderSystem.getShader()) draws it - but the
 * per-draw costs of Epic Fight's path are removed:
 *
 * - pose upload: Epic Fight stages into the shared 1000-entry TOTAL_POSES and
 *   its DynamicSSBO.updateFromTo uploads the whole 64 KB buffer per entity per
 *   draw; this path uploads only the joint section with an exact byte count
 *   (~20-40 matrices) and re-uploads the part section only when it changes;
 * - uniform locations: queried once per program instance instead of 8
 *   glGetUniformLocation calls per draw (plus a Uniform allocation each);
 * - vertex format: the VAO attribute layout is re-specified only when the
 *   shader's vertex format changes instead of ~12 glVertexAttribPointer calls
 *   per draw;
 * - the pose SSBO is per-mesh and dynamically sized, so meshes exceeding Epic
 *   Fight's MAX_JOINTS (1000) pose-array capacity render through this path as
 *   well (no more ArrayIndexOutOfBoundsException, no CPU last-resort needed).
 *
 * Everything Oculus-side is reflective (no hard dependency): the entity/block/
 * item ids of CapturedRenderingState, IrisConfig#areShadersEnabled and the
 * IrisVertexFormats elements. Set -Dysm_ef_compat.disable_iris_compute_path to
 * fall back to Epic Fight's Iris path for A/B verification.
 */
public final class YsmIrisComputePath {

    // Registered on first class load (render thread, when the path is first
    // used): resource release goes through the MeshReleaser interface and the
    // draw dispatch through RenderBridgeRegistry, so the model package never
    // imports this class (the model <-> gpu/cpu package cycle is broken).
    static {
        com.ysmef.compat.model.YSMMeshLibrary.registerMeshReleaser(new com.ysmef.compat.model.MeshReleaser() {
            @Override
            public void disposeMesh(YSMMesh mesh) {
                YsmIrisComputePath.disposeMesh(mesh);
            }

            @Override
            public void disposeAll() {
                YsmIrisComputePath.disposeAll();
            }
        });
        com.ysmef.compat.model.RenderBridgeRegistry.registerIris(new com.ysmef.compat.model.RenderBridgeRegistry.IrisSkinRender() {
            @Override
            public boolean tryRender(YSMMesh mesh, ComputeShaderSetup setup, PoseStack poseStack,
                                     MultiBufferSource bufferSources, RenderType renderType, int packedLight,
                                     float r, float g, float b, float a, int overlay,
                                     Armature armature, OpenMatrix4f[] poses) {
                return YsmIrisComputePath.tryRender(mesh, setup, poseStack, bufferSources,
                        renderType, packedLight, r, g, b, a, overlay, armature, poses);
            }
        });
    }

    private YsmIrisComputePath() {}

    // ------------------------------------------------------------------
    // Oculus/Iris reflection
    // ------------------------------------------------------------------

    private static final Object IRIS_CONFIG = findIrisConfig();
    private static final Method ARE_SHADERS_ENABLED = findAreShadersEnabled();
    private static final Object CAPTURED_STATE = findCapturedState();
    private static final Method GET_ENTITY = findCapturedMethod("getCurrentRenderedEntity");
    private static final Method GET_BLOCK = findCapturedMethod("getCurrentRenderedBlockEntity");
    private static final Method GET_ITEM = findCapturedMethod("getCurrentRenderedItem");

    static final Object IRIS_ENTITY_ID_ELEMENT = findIrisFormatElement("ENTITY_ID_ELEMENT");
    static final Object IRIS_MID_TEXTURE_ELEMENT = findIrisFormatElement("MID_TEXTURE_ELEMENT");
    static final Object IRIS_TANGENT_ELEMENT = findIrisFormatElement("TANGENT_ELEMENT");

    private static final boolean REFLECTION_OK = IRIS_CONFIG != null && ARE_SHADERS_ENABLED != null
            && CAPTURED_STATE != null && IRIS_ENTITY_ID_ELEMENT != null
            && IRIS_MID_TEXTURE_ELEMENT != null && IRIS_TANGENT_ELEMENT != null;

    private static final boolean DISABLED = System.getProperty("ysm_ef_compat.disable_iris_compute_path") != null;

    private static Object findIrisConfig() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            return iris.getMethod("getIrisConfig").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findAreShadersEnabled() {
        try {
            return IRIS_CONFIG == null ? null : IRIS_CONFIG.getClass().getMethod("areShadersEnabled");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object findCapturedState() {
        try {
            Class<?> captured = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Field instance = captured.getField("INSTANCE");
            return instance.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findCapturedMethod(String name) {
        try {
            return CAPTURED_STATE == null ? null : CAPTURED_STATE.getClass().getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object findIrisFormatElement(String name) {
        try {
            Class<?> formats = Class.forName("net.irisshaders.iris.vertices.IrisVertexFormats");
            return formats.getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean areShadersEnabled() {
        try {
            return Boolean.TRUE.equals(ARE_SHADERS_ENABLED.invoke(IRIS_CONFIG));
        } catch (Throwable t) {
            return false;
        }
    }

    private static int capturedInt(Method method) {
        try {
            return (Integer) method.invoke(CAPTURED_STATE);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Outline team color (private fields under these mappings; cached once)
    // ------------------------------------------------------------------

    private static final Field OUTLINE_TEAM_R = findOutlineField("teamR");
    private static final Field OUTLINE_TEAM_G = findOutlineField("teamG");
    private static final Field OUTLINE_TEAM_B = findOutlineField("teamB");
    private static final Field OUTLINE_TEAM_A = findOutlineField("teamA");

    private static Field findOutlineField(String name) {
        try {
            Field field = OutlineBufferSource.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private static float outlineColor(OutlineBufferSource outline, Field field) {
        try {
            return field != null ? field.getFloat(outline) / 255.0F : 1.0F;
        } catch (Throwable t) {
            return 1.0F;
        }
    }

    // ------------------------------------------------------------------
    // Per-mesh resources
    // ------------------------------------------------------------------

    private static final Map<YSMMesh, YsmIrisMesh> IRIS_MESHES = new IdentityHashMap<>();
    private static final Set<YSMMesh> UNSUPPORTED = ConcurrentHashMap.newKeySet();
    private static final Set<YSMMesh> ACTIVE_LOGGED = ConcurrentHashMap.newKeySet();
    private static boolean unavailableLogged = false;

    @Nullable
    private static YsmIrisMesh getOrBuild(YSMMesh mesh, int jointCount) {
        synchronized (IRIS_MESHES) {
            YsmIrisMesh gpu = IRIS_MESHES.get(mesh);
            if (gpu != null) {
                return gpu;
            }
        }
        YsmIrisMesh built;
        try {
            built = YsmIrisMesh.build(mesh, jointCount);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: optimized Iris path setup failed for '{}', using Epic Fight's Iris path",
                    mesh.getRuntimeModelId(), t);
            built = null;
        }
        synchronized (IRIS_MESHES) {
            YsmIrisMesh gpu = IRIS_MESHES.get(mesh);
            if (gpu != null) {
                if (built != null) {
                    built.dispose();
                }
                return gpu;
            }
            if (built != null) {
                IRIS_MESHES.put(mesh, built);
            }
        }
        if (built == null) {
            UNSUPPORTED.add(mesh);
        }
        return built;
    }

    /** Free the GL resources of one mesh (eviction / reload). Must run on the render thread. */
    public static void disposeMesh(YSMMesh mesh) {
        YsmIrisMesh gpu;
        synchronized (IRIS_MESHES) {
            gpu = IRIS_MESHES.remove(mesh);
        }
        if (gpu != null) {
            gpu.dispose();
        }
        UNSUPPORTED.remove(mesh);
    }

    /** Free every mesh resource (resource reload). Must run on the render thread. */
    public static void disposeAll() {
        synchronized (IRIS_MESHES) {
            for (YsmIrisMesh gpu : IRIS_MESHES.values()) {
                try {
                    gpu.dispose();
                } catch (Throwable ignored) {
                }
            }
            IRIS_MESHES.clear();
        }
        UNSUPPORTED.clear();
    }

    // ------------------------------------------------------------------
    // Compute program uniforms (queried once per program instance)
    // ------------------------------------------------------------------

    private static final String[] UNIFORM_NAMES = {
            "colorIn", "uv1In", "uv2In", "part_offset", "entity_id_0", "entity_id_1",
            "model_view_matrix", "normal_matrix"
    };
    private static ComputeProgram uniformProgram;
    private static final int[] uniformLocations = new int[UNIFORM_NAMES.length];

    private static int[] locations(ComputeProgram program) {
        if (program != uniformProgram) {
            for (int i = 0; i < UNIFORM_NAMES.length; i++) {
                uniformLocations[i] = program.getUniformLocation(UNIFORM_NAMES[i]);
            }
            uniformProgram = program;
        }
        return uniformLocations;
    }

    private static final Matrix4f IDENTITY4 = new Matrix4f();
    private static final Matrix3f IDENTITY3 = new Matrix3f();
    private static final FloatBuffer MV_SCRATCH = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer NM_SCRATCH = BufferUtils.createFloatBuffer(9);

    private static void uploadMat4(int location, Matrix4f matrix) {
        if (location < 0) {
            return;
        }
        matrix.get(0, MV_SCRATCH);
        GL20.glUniformMatrix4fv(location, false, MV_SCRATCH);
    }

    private static void uploadMat3(int location, Matrix3f matrix) {
        if (location < 0) {
            return;
        }
        matrix.get(0, NM_SCRATCH);
        GL20.glUniformMatrix3fv(location, false, NM_SCRATCH);
    }

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------

    /**
     * Try to draw the mesh through the optimized Iris compute path. Returns
     * false when unavailable (no Oculus, the mesh's compute setup is not the
     * Iris variant, setup failure) so the caller falls back to Epic Fight's
     * path.
     */
    public static boolean tryRender(YSMMesh mesh, ComputeShaderSetup setup, PoseStack poseStack,
                                    MultiBufferSource bufferSources, RenderType renderType,
                                    int packedLight, float r, float g, float b, float a, int overlay,
                                    @Nullable Armature armature, @Nullable OpenMatrix4f[] poses) {
        if (DISABLED || !REFLECTION_OK || poses == null) {
            return false;
        }
        // GUI entity previews (the YSM model selection screen, the inventory
        // player model, ...) are not drawn by the pack's entity shader: this
        // path would dispatch the Iris compute shader and draw nothing there.
        // Decline so the caller falls back to the CPU skinning path, which
        // renders GUI previews correctly even under a shader pack.
        if (YsmGpuRenderPath.isGuiEntityProjection() || YsmGpuRenderPath.isYsmPreviewMode()) {
            return false;
        }
        ComputeProgram program = ComputeShaderProvider.meshComputeIris;
        if (program == null || !(setup instanceof IrisComputeShaderSetup)) {
            return false;
        }
        if (UNSUPPORTED.contains(mesh)) {
            return false;
        }
        YsmIrisMesh gpu = getOrBuild(mesh, poses.length);
        if (gpu == null) {
            return false;
        }

        try {
            gpu.fillPoses(mesh, armature, poses);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: optimized Iris path pose fill failed for '{}', using Epic Fight's Iris path",
                    mesh.getRuntimeModelId(), t);
            UNSUPPORTED.add(mesh);
            return false;
        }

        boolean shadersOn = areShadersEnabled();
        int prevVao = GlStateManager._getInteger(GLConstants.GL_VERTEX_ARRAY_BINDING);
        int prevVbo = GlStateManager._getInteger(GLConstants.GL_VERTEX_ARRAY_BUFFER_BINDING);
        GlStateManager._glBindVertexArray(gpu.vao);
        try {
            // Mirrors IrisComputeShaderSetup.drawWithShader: with the pack active
            // the compute pass outputs model-space vertices and the entity shader
            // receives the poseStack as its model-view matrix.
            Matrix4f frustumMatrix = shadersOn ? poseStack.last().pose() : RenderSystem.getModelViewMatrix();
            draw(gpu, poseStack, renderType, frustumMatrix, r, g, b, a, overlay, packedLight, poses.length, shadersOn);
            if (bufferSources instanceof OutlineBufferSource outlineBufferSource) {
                renderType.outline().ifPresent(outlineRenderType ->
                        draw(gpu, poseStack, outlineRenderType, frustumMatrix,
                                outlineColor(outlineBufferSource, OUTLINE_TEAM_R),
                                outlineColor(outlineBufferSource, OUTLINE_TEAM_G),
                                outlineColor(outlineBufferSource, OUTLINE_TEAM_B),
                                outlineColor(outlineBufferSource, OUTLINE_TEAM_A),
                                overlay, packedLight, poses.length, shadersOn));
            }
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: optimized Iris path draw failed for '{}', using Epic Fight's Iris path",
                    mesh.getRuntimeModelId(), t);
            UNSUPPORTED.add(mesh);
            return false;
        } finally {
            GlStateManager._glBindVertexArray(prevVao);
            GlStateManager._glBindBuffer(GLConstants.GL_ARRAY_BUFFER, prevVbo);
        }

        if (ACTIVE_LOGGED.add(mesh)) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: optimized Iris compute path active: model='{}', {} parts, {} vertices",
                    mesh.getRuntimeModelId(), gpu.partCount, gpu.vertexCount);
        }
        return true;
    }

    /**
     * One render-type draw: the same sequence as ComputeShaderSetup#draw
     * (render state -> default uniforms -> shader apply -> compute dispatch ->
     * glDrawArrays -> state cleanup), with the cached-uniform compute program
     * and the change-gated vertex format. The post-draw clearBufferState is
     * replaced by BufferUploader.invalidate(): the attribute state stays valid
     * in this path's own VAO, and the vanilla pipeline re-specifies its own
     * state on its next draw.
     */
    private static void draw(YsmIrisMesh gpu, PoseStack poseStack, RenderType renderType, Matrix4f frustumMatrix,
                             float r, float g, float b, float a, int overlay, int packedLight, int joints,
                             boolean shadersOn) {
        renderType.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) {
            renderType.clearRenderState();
            return;
        }
        VertexFormat format = shader.getVertexFormat();
        gpu.bindFormatIfNeeded(format);

        ComputeShaderSetup.setShaderDefaultUniforms(frustumMatrix, shader, renderType.mode(),
                Minecraft.getInstance().getWindow());
        shader.apply();

        runCompute(gpu, poseStack, r, g, b, a, overlay, packedLight, joints, shadersOn);

        GL20.glUseProgram(shader.getId());
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, gpu.vertexCount);

        shader.clear();
        renderType.clearRenderState();
        BufferUploader.invalidate();
    }

    /** Dispatch Epic Fight's iris_mesh_transformer.comp with cached uniform locations. */
    private static void runCompute(YsmIrisMesh gpu, PoseStack poseStack, float r, float g, float b, float a,
                                   int overlay, int light, int joints, boolean shadersOn) {
        ComputeProgram program = ComputeShaderProvider.meshComputeIris;
        int[] u = locations(program);
        program.useProgram();
        if (u[0] >= 0) GL20.glUniform4f(u[0], r, g, b, a);
        if (u[1] >= 0) GL30.glUniform1ui(u[1], overlay);
        if (u[2] >= 0) GL30.glUniform1ui(u[2], light);
        if (u[3] >= 0) GL30.glUniform1ui(u[3], joints);
        short entity = (short) capturedInt(GET_ENTITY);
        short block = (short) capturedInt(GET_BLOCK);
        short item = (short) capturedInt(GET_ITEM);
        if (u[4] >= 0) GL30.glUniform1ui(u[4], ((entity << 16) & 0xFFFF0000) | (block & 0xFFFF));
        if (u[5] >= 0) GL30.glUniform1ui(u[5], item << 16);
        if (shadersOn) {
            uploadMat4(u[6], IDENTITY4);
            uploadMat3(u[7], IDENTITY3);
        } else {
            uploadMat4(u[6], poseStack.last().pose());
            uploadMat3(u[7], poseStack.last().normal());
        }

        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, gpu.poseSsbo);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, gpu.elementsBO.glSSBO);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, gpu.vObjBO.glSSBO);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, gpu.jointBO.glSSBO);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, gpu.hiddenFlagsBO.glSSBO);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, gpu.outBO.glSSBO);

        int workGroups = ((gpu.vertexCount / 3) + 127) / 128;
        program.dispatch(workGroups, 1, 1);
        program.waitBarriers();

        for (int i = 0; i <= 5; i++) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, 0);
        }
    }
}
