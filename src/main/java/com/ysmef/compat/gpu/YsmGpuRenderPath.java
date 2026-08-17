package com.ysmef.compat.gpu;

import com.ysmef.compat.YSMEpicFightCompat;
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
    private static final float[] mvScratch = new float[16];
    private static final float[] ivrScratch = new float[9];
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

    /** Once per mesh + reason: why the GPU path was skipped (diagnostics, removable). */
    private static final Map<YSMMesh, String> GPU_SKIP_DIAG = new ConcurrentHashMap<>();

    private static volatile boolean GPU_SKIP_FIRST_LOGGED = false;

    private static void gpuSkipDiag(YSMMesh mesh, String reason) {
        String prev = GPU_SKIP_DIAG.put(mesh, reason);
        boolean changed = !reason.equals(prev);
        if (!GPU_SKIP_FIRST_LOGGED) {
            GPU_SKIP_FIRST_LOGGED = true;
            // Always report the first GPU-path rejection so a high-poly model
            // silently spending the whole session on the CPU path is visible in
            // a normal log. Set ysm_ef_compat.diag for the per-reason trace.
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: GPU skinning path skipped its first draw: model={} reason={} "
                            + "(falling back; set ysm_ef_compat.diag=true for the full skip trace)",
                    mesh.getRuntimeModelId(), reason);
            return;
        }
        if (changed && com.ysmef.compat.YsmDiag.isEnabled()) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [diag] GPU path skip: model={} reason={}", mesh.getRuntimeModelId(), reason);
        }
    }

    /** Once per mesh: the exact GPU-path inputs of the first draw (stretch diagnostics). */
    private static final Map<YSMMesh, String> GPU_INPUT_DIAG = new ConcurrentHashMap<>();

    /**
     * One-time per mesh: log the full matrix state and a CPU-skinned sample of
     * the first GPU draw, so a stretched/wrong render can be attributed to
     * (a) the skinning data (model-space bounds blow up), (b) the GL camera
     * state (clip-space bounds blow up with sane model-space bounds), or
     * (c) the entity pose (poseStack differs from the compute path's).
     */
    private static void logGpuInputDiagOnce(YSMMesh mesh, YsmGpuMesh gpu, PoseStack poseStack,
                                            Armature armature, OpenMatrix4f[] poses) {
        if (!com.ysmef.compat.YsmDiag.isEnabled()) {
            return;
        }
        if (GPU_INPUT_DIAG.putIfAbsent(mesh, "logged") != null) {
            return;
        }
        try {
            int jointCount = poses.length;
            OpenMatrix4f[] toOrigin = toOriginOf(armature, jointCount);

            // Joint matrices exactly as written into the SSBO (poses x toOrigin).
            float maxJointTranslation = 0.0f;
            float maxJointScaleDiff = 0.0f;
            String worstJoint = "?";
            float worstJointTranslation = 0.0f;
            for (int j = 0; j < jointCount; j++) {
                jointScratch.load(poses[j]);
                jointScratch.mulBack(toOrigin[j]);
                float t = maxTranslation(jointScratch);
                if (t > maxJointTranslation) {
                    maxJointTranslation = t;
                }
                maxJointScaleDiff = Math.max(maxJointScaleDiff, maxScaleDiff(jointScratch));
                if (t > worstJointTranslation) {
                    worstJointTranslation = t;
                    yesman.epicfight.api.animation.Joint joint = armature.searchJointById(j);
                    worstJoint = joint != null ? joint.getName() : ("#" + j);
                }
            }

            // Model-space bounds of the skinned mesh (the compute path's output
            // before the model-view matrix): a runaway value here means the
            // pose/toOrigin/part data itself is wrong - the compute path would
            // render exactly as broken, so this isolates the input side.
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            int samples = 0;
            int partIdx = 0;
            float[] positions = mesh.positions();
            for (var part : mesh.getAllParts()) {
                var vertices = part.getVertices();
                if (!vertices.isEmpty() && partIdx < gpu.jointOfPart.length) {
                    yesman.epicfight.api.client.model.VertexBuilder vb = vertices.get(0);
                    int p = vb.position * 3;
                    if (p + 2 < positions.length) {
                        int joint = gpu.jointOfPart[partIdx];
                        jointScratch.load(poses[joint]);
                        jointScratch.mulBack(toOrigin[joint]);
                        OpenMatrix4f delta = mesh.getPartTransform(partIdx);
                        if (delta != null) {
                            jointScratch.mulBack(delta);
                        }
                        // skinning matrix x vertex position (point transform).
                        // OpenMatrix4f is row-vector convention: the translation
                        // lives in the last row (m30/m31/m32).
                        OpenMatrix4f out = OpenMatrix4f.mul(jointScratch,
                                OpenMatrix4f.ofTranslation(positions[p], positions[p + 1], positions[p + 2],
                                        new OpenMatrix4f()),
                                new OpenMatrix4f());
                        minX = Math.min(minX, out.m30);
                        maxX = Math.max(maxX, out.m30);
                        minY = Math.min(minY, out.m31);
                        maxY = Math.max(maxY, out.m31);
                        minZ = Math.min(minZ, out.m32);
                        maxZ = Math.max(maxZ, out.m32);
                        samples++;
                    }
                }
                partIdx++;
            }

            // GL camera state read by the GPU path (the compute path relies on
            // the same state through the vanilla shader pass).
            org.joml.Matrix4f proj = RenderSystem.getProjectionMatrix();
            org.joml.Matrix4f mv = RenderSystem.getModelViewMatrix();
            org.joml.Matrix4f pose = poseStack.last().pose();

            com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: [diag] GPU input: model={} joints={} parts={} verts={} "
                            + "jointMaxTranslation={} (worst={}:{}) jointMaxScaleDiff={} toOriginMaxTranslation={} "
                            + "modelSpaceBounds=([{},{}],[{},{}],[{},{}]) samples={} "
                            + "proj=(m00={},m11={},m22={},m33={},m30={},m31={}) "
                            + "view=(m00={},m11={},m22={},m33={},m30={},m31={},m32={}) "
                            + "pose=(m00={},m11={},m22={},m33={},m30={},m31={},m32={}) "
                            + "armature={}",
                    mesh.getRuntimeModelId(), jointCount, mesh.getPartCount(), gpu.vertexCount,
                    maxJointTranslation, worstJoint, worstJointTranslation, maxJointScaleDiff,
                    maxToOriginTranslation(toOrigin),
                    minX, maxX, minY, maxY, minZ, maxZ, samples,
                    proj.m00(), proj.m11(), proj.m22(), proj.m33(), proj.m30(), proj.m31(),
                    mv.m00(), mv.m11(), mv.m22(), mv.m33(), mv.m30(), mv.m31(), mv.m32(),
                    pose.m00(), pose.m11(), pose.m22(), pose.m33(), pose.m30(), pose.m31(), pose.m32(),
                    armature != null ? armature.getClass().getName() : "null");
        } catch (Throwable t) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: [diag] GPU input diagnostic failed for '{}'", mesh.getRuntimeModelId(), t);
        }
    }

    private static float maxTranslation(OpenMatrix4f m) {
        // OpenMatrix4f stores the translation in the last row (m30/m31/m32)
        return Math.max(Math.abs(m.m30), Math.max(Math.abs(m.m31), Math.abs(m.m32)));
    }

    /** Max deviation of the diagonal scale components (non-uniform scale stretches the model). */
    private static float maxScaleDiff(OpenMatrix4f m) {
        float sx = Math.abs(m.m00), sy = Math.abs(m.m11), sz = Math.abs(m.m22);
        float max = Math.max(sx, Math.max(sy, sz));
        float min = Math.min(sx, Math.min(sy, sz));
        return max <= 0.0f ? 0.0f : Math.max(max / Math.max(min, 1.0e-6f) - 1.0f, Math.abs(max - 1.0f));
    }

    private static float maxToOriginTranslation(OpenMatrix4f[] toOrigin) {
        float max = 0.0f;
        for (OpenMatrix4f m : toOrigin) {
            if (m != null) {
                max = Math.max(max, maxTranslation(m));
            }
        }
        return max;
    }

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
     * Whether the current projection is the GUI's orthographic one (set by
     * GameRenderer before the GUI render: setOrtho(0, w, h, 0, n, f) has
     * non-zero m30/m31), as opposed to the world camera's perspective matrix
     * (m30 = m31 = 0).
     */
    public static boolean isGuiEntityProjection() {
        org.joml.Matrix4f proj = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
        return proj.m30() != 0.0F || proj.m31() != 0.0F;
    }

    /** Once per session: the gate's measured values of the first rejected draw. */
    private static volatile boolean TRANSLATION_GATE_DIAG_LOGGED = false;

    /**
     * Whether the camera transform actually applied by the GPU path
     * (u_proj = proj x mv x poseStack) places the model at the entity instead of
     * at the camera.
     *
     * The dispatcher normally translates the poseStack by the camera-relative
     * entity offset and RenderSystem's model-view matrix only carries the camera
     * rotation, so the translation of the COMBINED matrix (mv x poseStack) has
     * the same length as the entity-camera distance: rotations preserve length,
     * and uniform entity scales do not touch the translation column. Checking
     * the poseStack alone is not enough - some render chains (TLM maids, GUI
     * entity previews, YSM's model preview) put the entity translation in
     * RenderSystem's model-view matrix instead, while others contaminate that
     * matrix with an extra translation. Both cases are caught by comparing the
     * combined translation against the interpolated entity render position.
     * The combined 3x3 must also remain a rigid rotation times one uniform
     * scale (a non-uniform camera/model matrix is what stretches a model while
     * the camera moves). A contaminated matrix no longer reaches the GPU path,
     * and a valid model-view-carried translation is no longer rejected just
     * because the poseStack has none.
     */
    private static boolean hasSoundWorldTranslation(PoseStack poseStack) {
        net.minecraft.world.entity.LivingEntity entity = com.ysmef.compat.model.runtime.YSMRuntimeBridge.getCurrentEntity();
        if (entity == null || entity.level() == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
            return false;
        }
        net.minecraft.world.phys.Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        // The dispatcher translates the poseStack by the INTERPOLATED render
        // position (LevelRenderer.renderEntity lerps xOld -> x), so compare
        // against that same interpolation, not the raw tick position.
        float partialTick = mc.getFrameTime();
        double px = net.minecraft.util.Mth.lerp(partialTick, entity.xOld, entity.getX());
        double py = net.minecraft.util.Mth.lerp(partialTick, entity.yOld, entity.getY());
        double pz = net.minecraft.util.Mth.lerp(partialTick, entity.zOld, entity.getZ());
        double dx = px - cam.x;
        double dy = py - cam.y;
        double dz = pz - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        org.joml.Matrix4f pose = poseStack.last().pose();
        org.joml.Matrix4f modelView = RenderSystem.getModelViewMatrix();
        // The exact matrix product the GPU path uploads as u_mv / u_proj.
        org.joml.Matrix4f combined = projMVScratch.set(modelView).mul(pose);

        // JOML field naming is column-major: the translation lives in
        // m30/m31/m32 (column 3), not m03/m13/m23 (row 3).
        float poseLen = translationLength(pose.m30(), pose.m31(), pose.m32());
        float modelViewLen = translationLength(modelView.m30(), modelView.m31(), modelView.m32());
        float combinedLen = translationLength(combined.m30(), combined.m31(), combined.m32());

        // The combined 3x3 must stay a rigid rotation times one uniform scale.
        // A non-uniform camera/model matrix is exactly what visually stretches
        // a model while the camera moves; such draws belong on the fallback
        // path (which is what the vanilla/EF pipeline was authored for).
        // JOML field naming is column-first: mRC is column R, row C, so each
        // column is (m?0, m?1, m?2).
        float c0 = columnLength(combined.m00(), combined.m01(), combined.m02());
        float c1 = columnLength(combined.m10(), combined.m11(), combined.m12());
        float c2 = columnLength(combined.m20(), combined.m21(), combined.m22());
        float minScale = Math.min(c0, Math.min(c1, c2));
        float maxScale = Math.max(c0, Math.max(c1, c2));
        boolean uniformScale = minScale > 1.0e-6f
                && (maxScale / minScale - 1.0f) <= MAX_COMBINED_SCALE_DEVIATION;

        boolean finite = Float.isFinite(poseLen) && Float.isFinite(modelViewLen) && Float.isFinite(combinedLen)
                && Float.isFinite(minScale) && Float.isFinite(maxScale);
        boolean sound = finite && uniformScale
                && Math.abs(combinedLen - dist) <= Math.max(0.1, 0.05 * dist);

        if (!sound && !TRANSLATION_GATE_DIAG_LOGGED) {
            TRANSLATION_GATE_DIAG_LOGGED = true;
            // The first rejection is always logged (this is the exact evidence
            // needed to attribute the maid GPU-stretch symptom); further
            // rejections only produce per-mesh skip diagnostics when diag is on.
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: GPU translation gate rejected a draw: entity={} renderPos=({},{},{}) cameraPos=({},{},{}) dist={} poseStackTranslationLen={} modelViewTranslationLen={} combinedTranslationLen={} combinedScale=({},{},{}) finite={} uniformScale={} pose=(m30={},m31={},m32={}) modelView=(m30={},m31={},m32={}) combined=(m30={},m31={},m32={})",
                    entity.getClass().getName(),
                    String.format("%.2f", px), String.format("%.2f", py), String.format("%.2f", pz),
                    String.format("%.2f", cam.x), String.format("%.2f", cam.y), String.format("%.2f", cam.z),
                    String.format("%.2f", dist), String.format("%.2f", poseLen),
                    String.format("%.2f", modelViewLen), String.format("%.2f", combinedLen),
                    String.format("%.3f", c0), String.format("%.3f", c1), String.format("%.3f", c2),
                    finite, uniformScale,
                    pose.m30(), pose.m31(), pose.m32(),
                    modelView.m30(), modelView.m31(), modelView.m32(),
                    combined.m30(), combined.m31(), combined.m32());
        }
        return sound;
    }

    private static float translationLength(float x, float y, float z) {
        return (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
    }

    private static float columnLength(float x, float y, float z) {
        return (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
    }

    /** Max relative difference between the combined matrix's column scales. */
    private static final float MAX_COMBINED_SCALE_DEVIATION = 0.05f;

    /**
     * Try to draw the mesh with the GPU skinning path. Returns true when the
     * draw happened; false lets the caller use Epic Fight's render path.
     */
    public static boolean tryRender(YSMMesh mesh, PoseStack poseStack, MultiBufferSource bufferSources,
                                    ResourceLocation texture, int packedLight, float r, float g, float b, float a,
                                    int overlay, @Nullable Armature armature, @Nullable OpenMatrix4f[] poses) {
        long t0 = com.ysmef.compat.YsmDiag.isEnabled() ? System.nanoTime() : 0L;
        // Linked to the loaded YSM fork's own GPU toggle: ModernYSM's UseGpuRenderer
        // (kept in sync), or this mod's enableGpuRender config for OpenYSM/LegacyYSM.
        if (!YsmGpuRenderEnable.isEnabled()) {
            gpuSkipDiag(mesh, "disabled-by-config");
            return false;
        }
        if (!YsmGpuCapability.isAvailable()) {
            YsmGpuRenderEnable.disableIfOwned();
            logUnavailableOnce();
            gpuSkipDiag(mesh, "capability-unavailable");
            return false;
        }
        if (!YsmBoneSkinShader.ensureCompiled()) {
            YsmGpuRenderEnable.disableIfOwned();
            gpuSkipDiag(mesh, "shader-compile-failed");
            return false;
        }
        if (poses == null || armature == null || bufferSources instanceof OutlineBufferSource) {
            gpuSkipDiag(mesh, "no-poses-or-outline-pass");
            return false;
        }
        if (isGuiEntityProjection()) {
            // In-GUI entity previews (the YSM model selection screen's
            // renderEntityInInventory) render with an orthographic projection and
            // GUI-specific GL state. The GPU skinning path's light/overlay unit
            // setup and uniform math are tuned for the world render and draw the
            // converted mesh as a collapsed red rectangle over the preview;
            // Epic Fight's compute-shader path renders correctly there (verified:
            // the red box appears only when the bone-count gate lets the GPU path
            // engage, i.e. a bare-handed maid, and disappears on the compute path).
            gpuSkipDiag(mesh, "gui-projection");
            return false;
        }
        if (shaderPackInUse()) {
            // under a shader pack the custom program would bypass the pack's shaders:
            // use Epic Fight's compute path, which supports Iris/Oculus natively
            gpuSkipDiag(mesh, "shader-pack-in-use");
            return false;
        }
        if (!hasSoundWorldTranslation(poseStack)) {
            // The GPU path draws with u_proj = proj x mv x poseStack. Some render
            // chains (Touhou Little Maid maids through YSM's GeoReplacedEntityRenderer,
            // and YSM's GUI previews) hand the mesh draw a poseStack whose top pose
            // does not carry the entity -> camera translation; others put an extra
            // translation in RenderSystem's model-view matrix. hasSoundWorldTranslation
            // validates the product the GPU path will actually upload (mv x poseStack)
            // against the entity's interpolated render position and rejects
            // contaminated/non-uniform camera matrices. Drawing an unverified
            // reconstruction puts the model AT the camera: for maids a few blocks
            // away this shows as the "stretched" artifact, for the local player it
            // draws inside the camera and looks invisible.
            gpuSkipDiag(mesh, "camera-transform-mismatch");
            return false;
        }
        if (UNSUPPORTED.contains(mesh)) {
            gpuSkipDiag(mesh, "mesh-unsupported");
            return false;
        }
        if (!YSMMeshLibrary.isTextureUploaded(texture)) {
            // the texture's async decode/upload has not completed yet: its GL
            // id is not ready, so binding it would draw an untextured (white)
            // mesh for a frame or two - use Epic Fight's path until it is up
            gpuSkipDiag(mesh, "texture-not-uploaded");
            return false;
        }

        YsmGpuMesh gpu = getOrBuild(mesh, poses.length);
        if (gpu == null) {
            gpuSkipDiag(mesh, "build-failed");
            return false;
        }
        if (gpu.vertexCount == 0 || gpu.boneCount - mesh.getPartCount() != poses.length) {
            // the armature joint layout changed since the mesh was uploaded
            gpuSkipDiag(mesh, "bone-count-mismatch(" + poses.length + "vs" + (gpu.boneCount - mesh.getPartCount()) + ")");
            return false;
        }

        try {
            fillBoneBuffer(gpu, mesh, poseStack, armature, poses, packedLight);
            logGpuInputDiagOnce(mesh, gpu, poseStack, armature, poses);
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
        if (YsmBoneSkinShader.locMv() >= 0) {
            GL20.glUniformMatrix4fv(YsmBoneSkinShader.locMv(), false, mvScratch);
        }
        if (YsmBoneSkinShader.locIvr() >= 0) {
            GL20.glUniformMatrix3fv(YsmBoneSkinShader.locIvr(), false, ivrScratch);
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
        if (YsmBoneSkinShader.locPackedLight() >= 0) {
            GL20.glUniform1i(YsmBoneSkinShader.locPackedLight(), packedLight);
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

        logGpuActiveOnce(mesh, gpu);
        com.ysmef.compat.YsmDiag.addNanos(com.ysmef.compat.YsmDiag.SLOT_GPU_PATH, System.nanoTime() - t0);
        return true;
    }

    private static final Set<YSMMesh> GPU_ACTIVE_LOGGED = ConcurrentHashMap.newKeySet();

    /** Once per mesh: confirm that model actually goes through the GPU skinning path. */
    private static void logGpuActiveOnce(YSMMesh mesh, YsmGpuMesh gpu) {
        if (GPU_ACTIVE_LOGGED.add(mesh)) {
            YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: GPU skinning path active (bone SSBO + skinning shader): model='{}', {} parts, {} vertices",
                    mesh.getRuntimeModelId(), mesh.getPartCount(), gpu.vertexCount);
        }
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
     *
     * Upload optimization: in Epic Fight battle mode the parts carry no runtime
     * transforms (identity deltas) and their hidden flags are static per model,
     * so the part section is uploaded to the GPU only once; only the joint
     * matrices (a few KB) are re-uploaded every frame. Outside battle mode the
     * section is re-uploaded when a transform appears/changes/disappears or a
     * hidden flag flips - a transform fading back to identity is treated as a
     * change so the GPU can never keep a stale non-identity delta. The packed
     * light is a uniform (u_packedLight), so the cached section never goes
     * stale.
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

        // u_mv = modelView x entityPose and u_ivr = inverse view rotation: the
        // same inputs Minecraft's entity shader feeds into fog_distance (the fog
        // must be computed on the view-transformed position, not the model-space
        // one - see bone_skin.vsh).
        projMVScratch.set(RenderSystem.getModelViewMatrix());
        projMVScratch.mul(poseMatrix);
        projMVScratch.get(mvScratch);
        RenderSystem.getInverseViewRotationMatrix().get(ivrScratch);

        ByteBuffer boneBuf = gpu.perFrameBoneBuffer;
        boneBuf.clear();

        // joints: poses[j] x toOrigin(j) in OM math (identical to EF's TOTAL_POSES)
        for (int j = 0; j < jointCount; j++) {
            jointScratch.load(poses[j]);
            jointScratch.mulBack(toOrigin[j]);
            boneBuf.position(j * YsmGpuMesh.BONE_STRIDE);
            storeMatrix(boneBuf, jointScratch, packedLight, false);
        }
        int jointBytes = jointCount * YsmGpuMesh.BONE_STRIDE;

        // parts: raw bind-space deltas + hidden flags; written into the cached
        // section every frame (cheap CPU), uploaded only when the content changed
        ByteBuffer partCache = gpu.partSectionCache();
        partCache.position(0);
        boolean anyTransform = false;
        boolean hiddenChanged = false;
        boolean identityChanged = false;
        boolean[] cachedHidden = gpu.cachedPartHidden();
        boolean[] cachedIdentity = gpu.cachedPartIdentity();
        int partIdx = 0;
        for (var part : mesh.getAllParts()) {
            if (jointCount + partIdx >= gpu.boneCount) {
                break;
            }
            OpenMatrix4f delta = mesh.getPartTransform(partIdx);
            boolean hidden = part.isHidden();
            if (hidden != cachedHidden[partIdx]) {
                cachedHidden[partIdx] = hidden;
                hiddenChanged = true;
            }
            if (delta != null) {
                anyTransform = true;
                if (cachedIdentity[partIdx]) {
                    cachedIdentity[partIdx] = false;
                    identityChanged = true;
                }
                storeMatrix(partCache, delta, packedLight, hidden);
            } else {
                // a transform fading back to identity is a content change too:
                // without this the GPU keeps the last non-identity delta forever
                if (!cachedIdentity[partIdx]) {
                    cachedIdentity[partIdx] = true;
                    identityChanged = true;
                }
                storeIdentity(partCache, packedLight, hidden);
            }
            partIdx++;
        }
        partCache.position(0);

        boolean uploadParts = !gpu.partSectionValid() || anyTransform || hiddenChanged || identityChanged;
        if (uploadParts) {
            boneBuf.position(jointBytes);
            boneBuf.put(partCache);
            gpu.markPartSectionValid();
        }
        boneBuf.position(0);
        boneBuf.limit(uploadParts ? gpu.boneCount * YsmGpuMesh.BONE_STRIDE : jointBytes);
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
