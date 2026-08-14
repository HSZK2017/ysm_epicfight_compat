package com.ysmef.compat.cpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.model.YSMMesh;
import com.ysmef.compat.model.YSMMeshLibrary;
import com.ysmef.compat.mixin.RenderSystemAccessorMixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.SkinnedMesh.SkinnedMeshPart;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec4f;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CPU skinning render path for converted YSM-EF meshes.
 *
 * This is the replacement for Epic Fight's CPU skinning fallback
 * ({@code SkinnedMesh#drawPosed}, which renders the converted meshes with
 * missing faces). Every frame the CPU composes the same per-vertex matrices
 * the GPU path builds (joint pose x toOrigin x per-part YSM bind-space
 * delta - verified numerically identical to Epic Fight's compute shader
 * product), skins each vertex into a reused direct ByteBuffer, streams it
 * into a per-mesh dynamic VBO and issues a single glDrawArrays through
 * {@code cpu_skin.vsh}. Hidden parts are simply not written.
 *
 * Unlike Epic Fight's compute path this needs no compute shader, and unlike
 * the GPU path (bone SSBO + skinning shader) it needs no SSBO either - the
 * minimum is desktop OpenGL 3.3 / OpenGL ES 3.0 - and its memory footprint is
 * one dynamic VBO plus one CPU buffer per mesh (24 bytes per vertex), which
 * also keeps working on machines with very little free memory. Because the
 * whole model stays one draw call there is no per-part buffer churn.
 *
 * The poseStack is applied on the CPU - the same contract Epic Fight's
 * drawPosed uses - so the shader receives plain RenderSystem proj/modelView
 * uniforms like the vanilla entity shader. Unlike the GPU path this needs no
 * poseStack/camera reconstruction, so the path also renders in-GUI previews
 * and render chains whose poseStack does not carry the entity-camera
 * translation (e.g. Touhou Little Maid). Only shader packs (the custom
 * program would bypass the pack's shaders) make the path step aside; when it
 * declines, the caller keeps Epic Fight's original drawPosed behavior (which
 * YSMMesh routes through a cache-independent TRIANGLES render type).
 */
public final class YsmCpuRenderPath {

    private static final float[] projScratch = new float[16];
    private static final float[] mvScratch = new float[16];
    private static final float[] ivrScratch = new float[9];
    private static final Vector3f[] currentLights = new Vector3f[2];

    /** CPU-path resources per mesh instance (one YSMMesh instance per model, shared by all players). */
    private static final Map<YSMMesh, YsmCpuMesh> CPU_MESHES = new IdentityHashMap<>();
    /** Meshes whose upload failed; never retried until the mesh is rebuilt. */
    private static final Set<YSMMesh> UNSUPPORTED = ConcurrentHashMap.newKeySet();
    /** Per-armature to-origin matrices (joint space -> model space), keyed by armature identity. */
    private static final Map<Armature, OpenMatrix4f[]> TO_ORIGIN_CACHE = new IdentityHashMap<>();
    /** Per-armature pose length, keyed by armature identity. */
    private static final Map<Armature, Integer> POSE_LENGTH_CACHE = new IdentityHashMap<>();

    // Per-frame skinning scratch. Render thread only - the same ownership model
    // Epic Fight's own drawPosed uses for its static scratch vectors.
    private static OpenMatrix4f[] TOTAL = allocateScratch(32);
    private static final OpenMatrix4f jointScratch = new OpenMatrix4f();
    private static final Vec4f POS4 = new Vec4f();
    private static final Vec4f NRM4 = new Vec4f();
    private static final Vec4f ACC4 = new Vec4f();
    private static final Vec4f TMP4 = new Vec4f();
    private static final org.joml.Vector3f NRM3 = new org.joml.Vector3f();
    private static final org.joml.Vector4f POS4J = new org.joml.Vector4f();

    private static OpenMatrix4f[] allocateScratch(int size) {
        OpenMatrix4f[] arr = new OpenMatrix4f[size];
        for (int i = 0; i < size; i++) {
            arr[i] = new OpenMatrix4f();
        }
        return arr;
    }

    /** Once per mesh + reason: why the CPU path was skipped (diagnostics, removable). */
    private static final Map<YSMMesh, String> CPU_SKIP_DIAG = new ConcurrentHashMap<>();

    private static void cpuSkipDiag(YSMMesh mesh, String reason) {
        String prev = CPU_SKIP_DIAG.put(mesh, reason);
        if (!reason.equals(prev)) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [diag] CPU path skip: model={} reason={}", mesh.getRuntimeModelId(), reason);
        }
    }

    private static boolean cpuActiveLogged = false;
    private static boolean failureLogged = false;

    private YsmCpuRenderPath() {}

    /**
     * Whether the user forced the CPU skinning path through the
     * "ysm_ef_compat.force_cpu_render" system property. With the flag set,
     * YSMMesh#drawWithPreferredPath skips Epic Fight's compute shader even
     * when it is available, so the CPU path can be exercised on capable
     * hardware (verification of the CPU fallback without a compute-less GPU).
     */
    public static boolean isForced() {
        return System.getProperty("ysm_ef_compat.force_cpu_render") != null;
    }

    /** Once per session: confirm the CPU skinning path is drawing. */
    private static void logCpuActiveOnce(YSMMesh mesh, int writtenCount) {
        if (cpuActiveLogged) {
            return;
        }
        cpuActiveLogged = true;
        YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: CPU skinning path active (CPU skin -> dynamic VBO -> cpu_skin shader): model='{}', {} parts, {} vertices drawn",
                mesh.getRuntimeModelId(), mesh.getPartCount(), writtenCount);
    }

    private static void logUnavailableOnce() {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: CPU skinning path unavailable ({}), keeping Epic Fight's CPU skinning path",
                YsmCpuSkinShader.getCapabilityReason());
    }

    /**
     * Try to draw the mesh with the CPU skinning path. Returns true when the
     * draw happened; false lets the caller use Epic Fight's drawPosed.
     *
     * Invoked from the drawPosed mixin, i.e. whenever Epic Fight's render
     * pipeline is about to fall back to its CPU skinning shader for a
     * converted YSM mesh.
     */
    public static boolean tryRender(YSMMesh mesh, PoseStack poseStack, Mesh.DrawingFunction drawingFunction,
                                    int packedLight, float r, float g, float b, float a, int overlay,
                                    @Nullable Armature armature, @Nullable OpenMatrix4f[] poses) {
        // The CPU path replicates NEW_ENTITY semantics (position/color/uv/overlay/
        // light/normal); other drawing functions write different vertex layouts.
        if (drawingFunction != Mesh.DrawingFunction.NEW_ENTITY) {
            cpuSkipDiag(mesh, "drawing-function-not-new-entity");
            return false;
        }
        if (poses == null) {
            cpuSkipDiag(mesh, "no-poses");
            return false;
        }
        if (shaderPackInUse()) {
            // under a shader pack the custom program would bypass the pack's shaders
            cpuSkipDiag(mesh, "shader-pack-in-use");
            return false;
        }
        if (UNSUPPORTED.contains(mesh)) {
            cpuSkipDiag(mesh, "mesh-unsupported");
            return false;
        }
        ResourceLocation texture = mesh.getResolvedTexture();
        if (texture == null) {
            // no texture override: the vanilla render type's own texture must be
            // used, which only Epic Fight's drawPosed knows - step aside
            cpuSkipDiag(mesh, "no-resolved-texture");
            return false;
        }
        if (!YSMMeshLibrary.isTextureUploaded(texture)) {
            // its GL id is not ready yet: binding it would draw an untextured mesh
            cpuSkipDiag(mesh, "texture-not-uploaded");
            return false;
        }
        if (!YsmCpuSkinShader.isCapabilityAvailable()) {
            logUnavailableOnce();
            cpuSkipDiag(mesh, "capability-unavailable");
            return false;
        }
        if (!YsmCpuSkinShader.ensureCompiled()) {
            cpuSkipDiag(mesh, "shader-compile-failed");
            return false;
        }

        YsmCpuMesh cpu = getOrBuild(mesh);
        if (cpu == null) {
            cpuSkipDiag(mesh, "build-failed");
            return false;
        }

        int writtenCount;
        try {
            writtenCount = fillVertexBuffer(cpu, mesh, armature, poses, poseStack);
        } catch (Throwable t) {
            // Matrix math failure must never break the entity render: fall back.
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: CPU skinning failed, falling back", t);
            return false;
        }
        if (writtenCount == 0) {
            // nothing visible this frame (all parts hidden): nothing to draw
            return true;
        }

        try {
            draw(cpu, mesh, texture, poseStack, packedLight, r, g, b, a, overlay, writtenCount);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: CPU skinning draw failed, falling back", t);
            return false;
        }

        logCpuActiveOnce(mesh, writtenCount);
        return true;
    }

    /**
     * Skin every visible vertex on the CPU into the mesh's accumulation buffer.
     *
     * The math is the exact CPU equivalent of Epic Fight's compute shader and
     * of the GPU path's vertex shader: for every affecting joint j of a vertex,
     * skinning matrix M = (pose_j x toOrigin_j) x partDelta, then
     * pos' = sum_j weight_j x (M x bindPos), nrm' = normalize(sum_j weight_j x (M x bindNrm)).
     * The poseStack (entity orientation + camera-relative translation) and its
     * normal matrix are then applied on the CPU - the same contract Epic
     * Fight's drawPosed uses - so the uploaded vertices are CAMERA-space and
     * {@link #draw} only applies the plain RenderSystem proj/modelView, exactly
     * like the vanilla entity shader. This keeps the path working in GUI
     * previews and for render chains whose poseStack does not carry the
     * entity-camera translation (e.g. Touhou Little Maid), where the GPU path's
     * u_proj reconstruction cannot engage.
     *
     * @return the number of vertices written
     */
    private static int fillVertexBuffer(YsmCpuMesh cpu, YSMMesh mesh, @Nullable Armature armature,
                                        OpenMatrix4f[] poses, PoseStack poseStack) {
        int jointCount = poses.length;
        if (jointCount > TOTAL.length) {
            TOTAL = allocateScratch(Math.max(jointCount, TOTAL.length * 2));
        }
        OpenMatrix4f[] total = TOTAL;
        OpenMatrix4f[] toOrigin = toOriginOf(armature, jointCount);
        for (int j = 0; j < jointCount; j++) {
            total[j].load(poses[j]);
            total[j].mulBack(toOrigin[j]);
        }

        float[] positions = mesh.positions();
        float[] normals = mesh.normals();
        float[] uvs = mesh.uvs();
        int[] jointCounts = mesh.affectingJointCounts();
        int[][] jointIndices = mesh.affectingJointIndices();
        int[][] weightIndices = mesh.affectingWeightIndices();
        float[] weights = mesh.weights();

        Matrix4f pose = poseStack.last().pose();
        Matrix3f normalMat = poseStack.last().normal();

        ByteBuffer buf = cpu.cpuBuffer;
        buf.clear();

        int partIdx = 0;
        for (SkinnedMeshPart part : mesh.getAllParts()) {
            if (part.isHidden()) {
                partIdx++;
                continue;
            }
            OpenMatrix4f delta = mesh.getPartTransform(partIdx);
            for (VertexBuilder vb : part.getVertices()) {
                skinVertex(buf, vb, positions, normals, uvs, jointCounts, jointIndices, weightIndices,
                        weights, total, jointCount, delta, pose, normalMat);
            }
            partIdx++;
        }
        buf.flip();
        return buf.limit() / YsmCpuMesh.VERTEX_STRIDE;
    }

    /** Skin one vertex, apply the poseStack, and append it (pos 3f, uv 2f, packed normal) to the buffer. */
    private static void skinVertex(ByteBuffer buf, VertexBuilder vb, float[] positions, float[] normals,
                                   float[] uvs, int[] jointCounts, int[][] jointIndices, int[][] weightIndices,
                                   float[] weights, OpenMatrix4f[] total, int jointCount,
                                   @Nullable OpenMatrix4f delta, Matrix4f pose, Matrix3f normalMat) {
        int posIdx = vb.position;
        int normIdx = vb.normal;
        int uvIdx = vb.uv;

        if (posIdx * 3 + 2 >= positions.length || normIdx * 3 + 2 >= normals.length
                || uvIdx * 2 + 1 >= uvs.length || posIdx >= jointCounts.length
                || posIdx >= jointIndices.length || posIdx >= weightIndices.length) {
            // Corrupt index triple: drop only this vertex (the rest of the model
            // keeps rendering; Epic Fight's CPU path would crash or draw garbage).
            return;
        }

        POS4.set(positions[posIdx * 3], positions[posIdx * 3 + 1], positions[posIdx * 3 + 2], 1.0F);
        ACC4.set(0.0F, 0.0F, 0.0F, 0.0F);

        int count = jointCounts[posIdx];
        int[] joints = jointIndices[posIdx];
        int[] weightIdx = weightIndices[posIdx];
        float totalWeight = 0.0F;
        for (int i = 0; i < count && i < joints.length && i < weightIdx.length; i++) {
            int jointIndex = joints[i];
            if (jointIndex < 0 || jointIndex >= jointCount) {
                // Out-of-range joint: the compute shader reads zero for OOB entries;
                // contribute nothing instead of crashing like the CPU path would.
                continue;
            }
            float weight = weightIdx[i] < weights.length ? weights[weightIdx[i]] : 0.0F;
            if (weight <= 0.0F) {
                continue;
            }
            jointScratch.load(total[jointIndex]);
            if (delta != null) {
                jointScratch.mulBack(delta);
            }
            OpenMatrix4f.transform(jointScratch, POS4, TMP4);
            ACC4.x += TMP4.x * weight;
            ACC4.y += TMP4.y * weight;
            ACC4.z += TMP4.z * weight;
            ACC4.w += TMP4.w * weight;
            totalWeight += weight;
        }
        if (totalWeight <= 0.0F) {
            // No contributing joint (all weights zero): skip the vertex instead of
            // writing it at the model origin (which renders as a visible spike).
            return;
        }

        float px = ACC4.x;
        float py = ACC4.y;
        float pz = ACC4.z;

        // normal: same weighted transform with w = 0 (translation drops out)
        NRM4.set(normals[normIdx * 3], normals[normIdx * 3 + 1], normals[normIdx * 3 + 2], 0.0F);
        ACC4.set(0.0F, 0.0F, 0.0F, 0.0F);
        for (int i = 0; i < count && i < joints.length && i < weightIdx.length; i++) {
            int jointIndex = joints[i];
            if (jointIndex < 0 || jointIndex >= jointCount) {
                continue;
            }
            float weight = weightIdx[i] < weights.length ? weights[weightIdx[i]] : 0.0F;
            if (weight <= 0.0F) {
                continue;
            }
            jointScratch.load(total[jointIndex]);
            if (delta != null) {
                jointScratch.mulBack(delta);
            }
            OpenMatrix4f.transform(jointScratch, NRM4, TMP4);
            ACC4.x += TMP4.x * weight;
            ACC4.y += TMP4.y * weight;
            ACC4.z += TMP4.z * weight;
        }

        float nx = ACC4.x;
        float ny = ACC4.y;
        float nz = ACC4.z;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-6F || Float.isNaN(len)) {
            nx = 0.0F;
            ny = 0.0F;
            nz = 1.0F;
        } else {
            nx /= len;
            ny /= len;
            nz /= len;
        }

        if (Float.isNaN(px) || Float.isNaN(py) || Float.isNaN(pz)
                || Float.isNaN(nx) || Float.isNaN(ny) || Float.isNaN(nz)) {
            // A NaN part transform would make this whole part vanish on every
            // render path; drop the vertex to keep the frame stable.
            return;
        }

        // Apply the poseStack on the CPU (same contract as Epic Fight's drawPosed:
        // the vertex arrives at the shader in camera space, so the shader only
        // applies the plain RenderSystem proj/modelView like the vanilla entity
        // shader does).
        POS4J.set(px, py, pz, 1.0F);
        POS4J.mul(pose);
        NRM3.set(nx, ny, nz);
        NRM3.mul(normalMat);
        nx = NRM3.x;
        ny = NRM3.y;
        nz = NRM3.z;
        len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-6F || Float.isNaN(len)) {
            nx = 0.0F;
            ny = 0.0F;
            nz = 1.0F;
        } else {
            nx /= len;
            ny /= len;
            nz /= len;
        }

        buf.putFloat(POS4J.x);
        buf.putFloat(POS4J.y);
        buf.putFloat(POS4J.z);
        buf.putFloat(uvs[uvIdx * 2]);
        buf.putFloat(uvs[uvIdx * 2 + 1]);
        buf.putInt(packNormal(nx, ny, nz));
    }

    /** Pack a normal into the GL_INT_2_10_10_10_REV layout (same unpack as cpu_skin.vsh). */
    static int packNormal(float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > 1e-6f) {
            x /= len;
            y /= len;
            z /= len;
        }
        int xi = Math.max(-511, Math.min(511, Math.round(x * 511)));
        int yi = Math.max(-511, Math.min(511, Math.round(y * 511)));
        int zi = Math.max(-511, Math.min(511, Math.round(z * 511)));
        return (xi & 0x3FF) | ((yi & 0x3FF) << 10) | ((zi & 0x3FF) << 20);
    }

    /**
     * Upload the skinned vertices and issue the draw calls. Mirrors
     * YsmGpuRenderPath#tryRender's GL state section exactly (texture units,
     * fog/light uniforms, alpha modes, translucent second pass).
     */
    private static void draw(YsmCpuMesh cpu, YSMMesh mesh, ResourceLocation texture, PoseStack poseStack,
                             int packedLight, float r, float g, float b, float a, int overlay, int writtenCount) {
        // The vertices were poseStack-transformed on the CPU (camera space), so
        // the shader applies exactly the uniforms the vanilla entity shader
        // receives: proj, modelView (camera), inverse view rotation.
        RenderSystem.getProjectionMatrix().get(projScratch);
        RenderSystem.getModelViewMatrix().get(mvScratch);
        RenderSystem.getInverseViewRotationMatrix().get(ivrScratch);

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

        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, cpu.vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, cpu.cpuBuffer);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        float[] fogColor = RenderSystem.getShaderFogColor();
        int fogShape = RenderSystem.getShaderFogShape().getIndex();

        GlStateManager._glUseProgram(YsmCpuSkinShader.program());
        if (YsmCpuSkinShader.locProj() >= 0) {
            GL20.glUniformMatrix4fv(YsmCpuSkinShader.locProj(), false, projScratch);
        }
        if (YsmCpuSkinShader.locMv() >= 0) {
            GL20.glUniformMatrix4fv(YsmCpuSkinShader.locMv(), false, mvScratch);
        }
        if (YsmCpuSkinShader.locIvr() >= 0) {
            GL20.glUniformMatrix3fv(YsmCpuSkinShader.locIvr(), false, ivrScratch);
        }
        if (YsmCpuSkinShader.locColor() >= 0) {
            GL20.glUniform4f(YsmCpuSkinShader.locColor(), r, g, b, a);
        }
        if (YsmCpuSkinShader.locOverlay() >= 0) {
            GL20.glUniform1i(YsmCpuSkinShader.locOverlay(), overlay);
        }
        if (YsmCpuSkinShader.locFogStart() >= 0) {
            GL20.glUniform1f(YsmCpuSkinShader.locFogStart(), fogStart);
        }
        if (YsmCpuSkinShader.locFogEnd() >= 0) {
            GL20.glUniform1f(YsmCpuSkinShader.locFogEnd(), fogEnd);
        }
        if (YsmCpuSkinShader.locFogColor() >= 0) {
            GL20.glUniform4f(YsmCpuSkinShader.locFogColor(), fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        }
        if (YsmCpuSkinShader.locFogShape() >= 0) {
            GL20.glUniform1i(YsmCpuSkinShader.locFogShape(), fogShape);
        }
        refreshLights();
        if (YsmCpuSkinShader.locLight0() >= 0) {
            GL20.glUniform3f(YsmCpuSkinShader.locLight0(), currentLights[0].x, currentLights[0].y, currentLights[0].z);
        }
        if (YsmCpuSkinShader.locLight1() >= 0) {
            GL20.glUniform3f(YsmCpuSkinShader.locLight1(), currentLights[1].x, currentLights[1].y, currentLights[1].z);
        }
        if (YsmCpuSkinShader.locPackedLight() >= 0) {
            GL20.glUniform1i(YsmCpuSkinShader.locPackedLight(), packedLight);
        }

        GlStateManager._glBindVertexArray(cpu.vao);
        boolean translucent = YSMMeshLibrary.isTranslucentTexture(texture);
        if (YsmCpuSkinShader.locAlphaMode() >= 0) {
            GL20.glUniform1i(YsmCpuSkinShader.locAlphaMode(), 1);
        }
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, writtenCount);

        if (translucent) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            if (YsmCpuSkinShader.locAlphaMode() >= 0) {
                GL20.glUniform1i(YsmCpuSkinShader.locAlphaMode(), 2);
            }
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, writtenCount);
            RenderSystem.disableBlend();
        }

        GlStateManager._glUseProgram(0);
        BufferUploader.invalidate();
        GlStateManager._glBindVertexArray(0);

        mc.gameRenderer.lightTexture().turnOffLightLayer();
    }

    // ------------------------------------------------------------------
    // Gates mirrored from YsmGpuRenderPath (identical logic, see there)
    // ------------------------------------------------------------------

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
     * back to Epic Fight's own CPU path. Reflective + TTL-cached.
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

    private static void refreshLights() {
        Vector3f[] arr = RenderSystemAccessorMixin.ysmef$getShaderLightDirections();
        currentLights[0] = (arr != null && arr.length > 0 && arr[0] != null)
                ? arr[0] : new Vector3f(0.2f, 1.0f, -0.7f).normalize();
        currentLights[1] = (arr != null && arr.length > 1 && arr[1] != null)
                ? arr[1] : new Vector3f(-0.2f, 1.0f, 0.7f).normalize();
    }

    // ------------------------------------------------------------------
    // Resources
    // ------------------------------------------------------------------

    private static OpenMatrix4f[] toOriginOf(@Nullable Armature armature, int jointCount) {
        synchronized (TO_ORIGIN_CACHE) {
            OpenMatrix4f[] cached = TO_ORIGIN_CACHE.get(armature);
            Integer cachedLen = POSE_LENGTH_CACHE.get(armature);
            if (cached != null && cachedLen != null && cachedLen == jointCount) {
                return cached;
            }
            OpenMatrix4f[] toOrigin = new OpenMatrix4f[jointCount];
            for (int j = 0; j < jointCount; j++) {
                if (armature != null) {
                    Joint joint = armature.searchJointById(j);
                    toOrigin[j] = joint != null ? joint.getToOrigin() : OpenMatrix4f.IDENTITY;
                } else {
                    // armature-less drawPosed callers: no origin correction
                    toOrigin[j] = OpenMatrix4f.IDENTITY;
                }
            }
            TO_ORIGIN_CACHE.put(armature, toOrigin);
            POSE_LENGTH_CACHE.put(armature, jointCount);
            return toOrigin;
        }
    }

    private static YsmCpuMesh getOrBuild(YSMMesh mesh) {
        synchronized (CPU_MESHES) {
            YsmCpuMesh cpu = CPU_MESHES.get(mesh);
            if (cpu != null) {
                return cpu;
            }
        }
        YsmCpuMesh built;
        try {
            List<List<VertexBuilder>> partVertices = new ArrayList<>();
            for (SkinnedMeshPart part : mesh.getAllParts()) {
                partVertices.add(part.getVertices());
            }
            built = YsmCpuMesh.build(partVertices);
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: CPU mesh upload failed for '{}', using Epic Fight's CPU skinning path",
                    mesh.getRuntimeModelId(), t);
            built = null;
        }
        synchronized (CPU_MESHES) {
            YsmCpuMesh cpu = CPU_MESHES.get(mesh);
            if (cpu != null) {
                if (built != null) {
                    built.dispose();
                }
                return cpu;
            }
            if (built != null) {
                CPU_MESHES.put(mesh, built);
            }
        }
        if (built == null) {
            UNSUPPORTED.add(mesh);
        }
        return built;
    }

    /** Free the CPU-path resources of one mesh (eviction / reload). Must run on the render thread. */
    public static void disposeMesh(YSMMesh mesh) {
        YsmCpuMesh cpu;
        synchronized (CPU_MESHES) {
            cpu = CPU_MESHES.remove(mesh);
        }
        if (cpu != null) {
            cpu.dispose();
        }
        UNSUPPORTED.remove(mesh);
    }

    /** Free every CPU-path mesh and per-armature cache (resource reload). Must run on the render thread. */
    public static void disposeAll() {
        synchronized (CPU_MESHES) {
            for (YsmCpuMesh cpu : CPU_MESHES.values()) {
                try {
                    cpu.dispose();
                } catch (Throwable ignored) {
                }
            }
            CPU_MESHES.clear();
        }
        UNSUPPORTED.clear();
        synchronized (TO_ORIGIN_CACHE) {
            TO_ORIGIN_CACHE.clear();
            POSE_LENGTH_CACHE.clear();
        }
    }
}
