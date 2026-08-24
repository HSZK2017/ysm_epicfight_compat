package com.ysmef.compat.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.compat.gpu.YsmGpuRenderPath;
import com.ysmef.compat.model.runtime.YSMRuntimeBridge;
import com.ysmef.compat.model.runtime.YsmBindArmature;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.client.model.MeshPartDefinition;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.EpicFightRenderTypes;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A HumanoidMesh loaded from a generated Epic Fight animmodels JSON (see
 * EFMeshJsonWriter / YSMMeshLibrary).
 *
 * Epic Fight's patched render pipeline always draws the mesh with the render type of the
 * vanilla entity renderer (i.e. the player's own skin texture). To display the YSM model's
 * texture instead, the render type's texture is replaced here, keeping every other render
 * state (translucency, outline, cull, ...) from the original render type.
 *
 * The default texture comes from the mesh JSON's render_properties; the patched renderer
 * can override it per frame (players may select different textures of the same model).
 *
 * YSM models change shape at runtime through molang-driven bone animations. Each YSM
 * bone is a separate Epic Fight part ("y/<boneName>") whose vanilla part transform is
 * fed from the runtime script evaluator (see YSMRuntimeBridge): every frame the scripts
 * decide which bones are hidden and which bind-space delta each visible bone gets, so
 * the mesh replicates YSM's model-changing behavior (variant forms, secondary bones).
 */
public class YSMMesh extends HumanoidMesh {

    private ResourceLocation textureOverride;
    private String runtimeModelId;
    /** Per-part runtime transforms, indexed by part ordinal (see rebindPartTransforms). */
    private OpenMatrix4f[] transformByPart;
    /** partName -> ordinal into transformByPart, built once at rebind time. */
    private final Map<String, Integer> partIndex = new HashMap<>();

    public YSMMesh(Map<String, Number[]> arrayMap,
                   Map<MeshPartDefinition, List<VertexBuilder>> parts,
                   @Nullable SkinnedMesh parent,
                   RenderProperties properties) {
        super(arrayMap, parts, parent, properties);
        rebindPartTransforms();
    }

    /**
     * Re-creates every part with a vanilla-part-transform supplier fed from the
     * runtime script evaluator, so per-bone transforms can be injected per frame.
     * The compute-shader part binding (partVBO, assigned when Epic Fight built the
     * ComputeShaderSetup during the super constructor) is carried over verbatim.
     *
     * Transforms are stored in a flat array indexed by part ordinal: the supplier
     * (read by Epic Fight's per-part transform upload on the GPU path every frame)
     * and the runtime evaluator both do O(1) array access instead of a string-keyed
     * map lookup per part per frame.
     */
    private void rebindPartTransforms() {
        List<Map.Entry<String, SkinnedMeshPart>> entries = new ArrayList<>(this.parts.entrySet());
        this.transformByPart = new OpenMatrix4f[entries.size()];
        this.partIndex.clear();
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, SkinnedMeshPart> entry = entries.get(i);
            String partName = entry.getKey();
            int index = i;
            partIndex.put(partName, index);
            SkinnedMeshPart old = entry.getValue();
            SkinnedMeshPart part = new SkinnedMeshPart(old.getVertices(), null,
                    () -> this.transformByPart[index]);
            part.initVBO(old.getPartVBO());
            entry.setValue(part);
        }
    }

    public void setRuntimeModelId(String modelId) {
        this.runtimeModelId = modelId;
    }

    public String getRuntimeModelId() {
        return this.runtimeModelId;
    }

    public void setRuntimeTransform(String partName, OpenMatrix4f transform) {
        Integer index = this.partIndex.get(partName);
        if (index != null) {
            this.transformByPart[index] = transform;
        }
    }

    /** Number of mesh parts (the bone SSBO of the GPU path has one entry per part). */
    public int getPartCount() {
        return this.transformByPart != null ? this.transformByPart.length : 0;
    }

    /** The current runtime transform of a part by ordinal, or null for identity. */
    public OpenMatrix4f getPartTransform(int ordinal) {
        if (this.transformByPart == null || ordinal < 0 || ordinal >= this.transformByPart.length) {
            return null;
        }
        return this.transformByPart[ordinal];
    }

    public void clearRuntimeTransforms() {
        if (this.transformByPart != null) {
            java.util.Arrays.fill(this.transformByPart, null);
        }
    }

    /** Typed view of this mesh's part entries for the runtime evaluator. */
    public Set<Map.Entry<String, MeshPart>> getPartEntrySetSafe() {
        return (Set<Map.Entry<String, MeshPart>>) (Set<?>) this.getPartEntry();
    }

    public void setTextureOverride(ResourceLocation texture) {
        this.textureOverride = texture;
    }

    private ResourceLocation resolveTexture() {
        if (this.textureOverride != null) {
            return this.textureOverride;
        }
        if (this.getRenderProperties() != null && this.getRenderProperties().customTexturePath() != null) {
            return this.getRenderProperties().customTexturePath();
        }
        return null;
    }

    /**
     * The texture this mesh draws with (the per-frame override, or the mesh
     * JSON's render_properties texture). Used by the CPU skinning path to bind
     * the model texture directly.
     */
    public ResourceLocation getResolvedTexture() {
        return resolveTexture();
    }

    @Override
    public void draw(PoseStack poseStack, MultiBufferSource bufferSources, RenderType renderType,
                     Mesh.DrawingFunction drawingFunction, int packedLight, float r, float g, float b, float a,
                     int overlay, @Nullable Armature armature, OpenMatrix4f[] poses) {
        // 体型适配：Epic Fight 的战斗动画围绕绑定姿势（Steve 体型）的关节旋转，
        // 而转换后的 YSM 网格按自身关节轴心刚性蒙皮，挥砍时四肢会绕 Steve 的
        // 关节位置旋转导致与身体分离。这里将动画姿势重新求值到该模型自己的
        // YSM 绑定骨架（关节平移来自 YSM 骨骼 pivot，旋转帧与拓扑不变），使
        // 旋转轴心落在模型的真实关节上；绑定姿势不变式（pose x toOrigin = I）
        // 保证静止形态不受影响。仅当 poses 是当前 armature 的实时姿势矩阵时
        // 才生效（EntitySnapshot 等快照路径传独立数组，保持原样）。
        boolean rebindApplied = false;
        if (this.runtimeModelId != null && armature != null && poses != null
                && poses == armature.getPoseMatrices()) {
            yesman.epicfight.api.animation.Pose captured = YsmBindArmature.findPose(armature);
            if (captured != null) {
                yesman.epicfight.model.armature.HumanoidArmature bind = YsmBindArmature.getArmature(this.runtimeModelId, this);
                if (bind != null) {
                    bind.setPose(captured);
                    armature = bind;
                    poses = bind.getPoseMatrices();
                    rebindApplied = true;
                }
            }
        }
        // 防御：EntitySnapshot（残影/特效快照）捕获时的 poseMatrices 基于当时的
        // armature 生成；若女仆在战斗中切换武器（EFTLM 按物品切换 armature），
        // 渲染时 armature 关节数变小，poses 比关节多，Epic Fight 的 compute 路径
        // 会因 searchJointById(i) 返回 null 而崩溃。这里将 poses 裁剪到当前
        // armature 的关节数（同时保证 YSMRuntimeBridge 与 compute 路径拿到一致数据）。
        if (armature != null && poses != null && poses.length > armature.getJointNumber()) {
            poses = java.util.Arrays.copyOf(poses, armature.getJointNumber());
        }
        boolean maidEntity = isMaidEntity();
        YSMRuntimeBridge.apply(this, armature, poses);
        ResourceLocation texture = resolveTexture();
        // EpicFight_TouhouLittleMaid renders maids through its MaidPatch with a
        // built-in 0.8 model-matrix scale (MaidPatch#getModelMatrix), tuned for
        // its own maid-sized meshes (~1.37 blocks tall). Our converted YSM meshes
        // are authored at the model's native (player-sized) scale, so that same
        // shrink would render a maid's YSM model noticeably too small compared to
        // its non-battle YSM render. Counter the scale around the entity origin
        // (feet) so battle mode shows the model at its native size again.
        if (maidEntity) {
            poseStack.pushPose();
            poseStack.scale(MAID_SCALE_COMPENSATION, MAID_SCALE_COMPENSATION, MAID_SCALE_COMPENSATION);
        }
        try {
            // Real Camera's vertex-catcher passes (its tetrahedral binding
            // probes and its first-person body render) only see vertices
            // written into their own buffer source: the direct-GL paths (GPU
            // skinning, CPU skinning, compute shaders) would bypass it, so the
            // camera could neither find the head plane nor render the body.
            // Draw those passes through Epic Fight's CPU-skinned drawPosed,
            // which emits into the catcher. Over-capacity meshes (drawPosed
            // would overflow Epic Fight's static pose array) skip the draw.
            // The capture flag keeps SkinnedMeshCpuRenderMixin's CPU-skinning
            // hijack (which renders direct-GL, bypassing the catcher) from
            // intercepting this drawPosed when no shader pack is active.
            if (com.ysmef.compat.realcamera.YsmRealCameraBridge.isCameraCapture(bufferSources)) {
                if (poses != null && poses.length <= yesman.epicfight.main.EpicFightSharedConstants.MAX_JOINTS) {
                    RenderType captureRenderType = texture != null
                            ? EpicFightRenderTypes.replaceTexture(texture, renderType)
                            : renderType;
                    com.ysmef.compat.cpu.YsmCpuRenderPath.pushCapturePass();
                    try {
                        this.drawPosed(poseStack, bufferSources.getBuffer(EpicFightRenderTypes.makeTriangulated(captureRenderType)),
                                drawingFunction, packedLight, r, g, b, a, overlay, armature, poses);
                    } finally {
                        com.ysmef.compat.cpu.YsmCpuRenderPath.popCapturePass();
                    }
                }
                logDrawDiagOnce(runtimeModelId, armature, poses, rebindApplied, maidEntity, "realcamera", poseStack);
                com.ysmef.compat.YsmDiag.onMeshDrawEnd();
                return;
            }
            // ModernYSM-style direct GPU skinning path (bone SSBO + skinning shader):
            // one glDrawArrays per model, vertex skinning fully on the GPU. Falls back
            // to Epic Fight's compute-shader path automatically when unavailable.
            if (texture != null && YsmGpuRenderPath.tryRender(this, poseStack, bufferSources, texture,
                    packedLight, r, g, b, a, overlay, armature, poses)) {
                logDrawDiagOnce(runtimeModelId, armature, poses, rebindApplied, maidEntity, "gpu", poseStack);
                com.ysmef.compat.YsmDiag.onMeshDrawEnd();
                return;
            }
            RenderType finalRenderType = texture != null
                    ? EpicFightRenderTypes.replaceTexture(texture, renderType)
                    : renderType;
            drawWithPreferredPath(poseStack, bufferSources, finalRenderType, drawingFunction,
                    packedLight, r, g, b, a, overlay, armature, poses);
            logDrawDiagOnce(runtimeModelId, armature, poses, rebindApplied, maidEntity, "compute", poseStack);
        } finally {
            if (maidEntity) {
                poseStack.popPose();
            }
        }
        com.ysmef.compat.YsmDiag.onMeshDrawEnd();
    }

    /** Once per model: which render path draws it and with which armature/pose data. */
    private static final Set<String> DIAG_MESH_DRAW = ConcurrentHashMap.newKeySet();

    private static void logDrawDiagOnce(String modelId, Armature armature, OpenMatrix4f[] poses,
                                        boolean rebindApplied, boolean maidEntity, String path, PoseStack poseStack) {
        if (!com.ysmef.compat.YsmDiag.isEnabled()) {
            return;
        }
        String key = (modelId == null ? "n/a" : modelId) + "|" + path + "|" + maidEntity;
        if (!DIAG_MESH_DRAW.add(key)) {
            return;
        }
        // Bound of the pose translations feeding the skinning: a runaway value
        // here (>> model height) is exactly what a "stretched" model looks like.
        float maxPoseTranslation = 0.0f;
        if (poses != null) {
            for (OpenMatrix4f pose : poses) {
                if (pose == null) {
                    continue;
                }
                maxPoseTranslation = Math.max(maxPoseTranslation,
                        Math.max(Math.abs(pose.m30), Math.max(Math.abs(pose.m31), Math.abs(pose.m32))));
            }
        }
        // Translation state of the poseStack at the mesh draw vs the entity's real
        // camera offset: the GPU path reconstructs the world transform from these,
        // and a mismatch (poseStack translation 0 while the entity is blocks away)
        // is exactly what renders the mesh at the camera - stretched/invisible.
        net.minecraft.world.entity.LivingEntity entity = YSMRuntimeBridge.getCurrentEntity();
        float poseStackTranslationLen = -1.0f;
        double entityCameraDist = -1.0d;
        if (poseStack != null) {
            org.joml.Matrix4f top = poseStack.last().pose();
            // JOML translation components are m30/m31/m32 (column-major field naming).
            poseStackTranslationLen = (float) Math.sqrt(
                    (double) top.m30() * top.m30() + (double) top.m31() * top.m31() + (double) top.m32() * top.m32());
        }
        if (entity != null && entity.level() != null && net.minecraft.client.Minecraft.getInstance().gameRenderer != null) {
            net.minecraft.world.phys.Vec3 cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            double dx = entity.getX() - cam.x;
            double dy = entity.getY() - cam.y;
            double dz = entity.getZ() - cam.z;
            entityCameraDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] mesh draw: model={} path={} maid={} entity={} armature={} joints={} poses={} rebind={} maxPoseTranslation={} poseStackTranslationLen={} entityCameraDist={}",
                modelId, path, maidEntity,
                entity != null ? entity.getClass().getSimpleName() : "null",
                armature != null ? armature.getClass().getName() : "null",
                armature != null ? armature.getJointNumber() : -1,
                poses != null ? poses.length : -1, rebindApplied, maxPoseTranslation,
                String.format("%.2f", poseStackTranslationLen), String.format("%.2f", entityCameraDist));
    }

    /**
     * The inverse of EpicFight_TouhouLittleMaid's built-in maid scale
     * (MaidPatch#getModelMatrix, 0.8F). Update this if EFTLM changes it.
     */
    private static final float MAID_SCALE_COMPENSATION = 1.0f / 0.8f;

    /**
     * Meshes at or below this many unique positions may use the CPU skinning
     * path; larger meshes prefer Epic Fight's compute path so the render thread
     * is not skinning hundreds of thousands of vertices per frame in
     * many-model scenes.
     */
    private static final int CPU_PATH_MAX_VERTICES = 8192;

    private static final Set<String> COMPUTE_PREFERRED_LOGGED = ConcurrentHashMap.newKeySet();

    /** Once per model: the compute path took over because the mesh is too large for CPU skinning. */
    private void logComputePreferredOnce(int positionCount) {
        String key = this.runtimeModelId == null ? "n/a" : this.runtimeModelId;
        if (COMPUTE_PREFERRED_LOGGED.add(key)) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                    "YSM-EF Compat: model '{}' has {} unique vertices (>{}); using Epic Fight's compute path "
                            + "instead of CPU skinning to keep the render thread bounded in many-model scenes",
                    key, positionCount, CPU_PATH_MAX_VERTICES);
        }
    }

    private static volatile Boolean TLM_PRESENT;

    /**
     * Whether the mesh is about to draw a Touhou Little Maid entity rendered by
     * EpicFight_TouhouLittleMaid's patched renderer. Guarded by an isLoaded check
     * so the EntityMaid reference is never resolved when TLM is absent.
     */
    private static boolean isMaidEntity() {
        LivingEntity entity = YSMRuntimeBridge.getCurrentEntity();
        if (entity == null) {
            return false;
        }
        Boolean tlm = TLM_PRESENT;
        if (tlm == null) {
            tlm = net.minecraftforge.fml.ModList.get().isLoaded("touhou_little_maid");
            TLM_PRESENT = tlm;
        }
        return tlm && entity instanceof com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
    }

    /**
     * Epic Fight's SkinnedMesh#draw only uses the compute-shader path while the
     * client config use_compute_shader is enabled; with the default (disabled)
     * value it falls back to the CPU skinning path (drawPosed), which renders
     * the converted YSM meshes with missing faces (verified empirically:
     * flipping use_compute_shader reproduces/removes the artifact). The
     * compute-shader path renders the same part data correctly, so it is used
     * whenever the mesh has a compute setup, regardless of the config. Without
     * a compute setup (unsupported GPU) the drawPosed fallback is reached, where
     * SkinnedMeshCpuRenderMixin substitutes this mod's CPU skinning render path
     * (CPU skin -> dynamic VBO -> cpu_skin shader) - see YsmCpuRenderPath. The
     * ysm_ef_compat.force_cpu_render system property skips the compute shader
     * even when available, so the CPU path can be verified on capable hardware.
     *
     * Routing preference: when the GPU skinning path could not take this draw
     * (GUI previews, TLM maids whose poseStack lacks the entity-camera
     * translation, ...), the CPU skinning path is preferred over Epic Fight's
     * compute shader for small meshes: it is a plain vertex-pipeline draw (no
     * compute dispatch, no output-SSBO round trip, no pipeline barrier), which
     * is cheaper on weak / integrated GPUs (measured ~0.5 ms per draw for the
     * compute path on the render thread alone - the iGPU work comes on top).
     * Meshes above {@link #CPU_PATH_MAX_VERTICES} skip the CPU path and use the
     * compute shader in world renders, so high-poly scenes do not pay per-vertex
     * CPU skinning for every visible model. GUI entity previews keep the CPU
     * path at any size (Epic Fight disables its compute pass there). Outline
     * passes are never taken over (the compute path renders the outline state
     * correctly; the CPU path has no outline pass).
     */
    private void drawWithPreferredPath(PoseStack poseStack, MultiBufferSource bufferSources, RenderType renderType,
                                       Mesh.DrawingFunction drawingFunction, int packedLight,
                                       float r, float g, float b, float a, int overlay,
                                       @Nullable Armature armature, OpenMatrix4f[] poses) {
        yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup setup = computeShaderSetup();
        if (setup != null && !com.ysmef.compat.cpu.YsmCpuRenderPath.isForced()) {
            int positionCount = this.positions().length / 3;
            // The CPU path skins every visible vertex on the render thread every
            // frame. Large YSM models (10k+ faces, and 51-maid scenes in
            // particular) would spend milliseconds per model there, so once a
            // mesh passes this size the Epic Fight compute path is preferred:
            // its vertex skinning runs on the GPU and its render-thread cost
            // stays bounded no matter how many high-poly models are visible.
            // GUI entity previews keep the CPU preference for every size: only
            // one preview is drawn there, and Epic Fight itself switches its
            // compute path off for those passes.
            boolean guiEntityPreview = YsmGpuRenderPath.isGuiEntityProjection()
                    || YsmGpuRenderPath.isYsmPreviewMode();
            if (!guiEntityPreview && positionCount > CPU_PATH_MAX_VERTICES) {
                logComputePreferredOnce(positionCount);
            }
            if ((guiEntityPreview || positionCount <= CPU_PATH_MAX_VERTICES)
                    && !(bufferSources instanceof net.minecraft.client.renderer.OutlineBufferSource)
                    && com.ysmef.compat.cpu.YsmCpuRenderPath.tryRender(this, poseStack, drawingFunction,
                            packedLight, r, g, b, a, overlay, armature, poses)) {
                return;
            }
            // Optimized Iris compute path: identical visual output to Epic Fight's
            // IrisComputeShaderSetup (Epic Fight's own iris compute shader skins
            // the mesh, the pack's entity shader draws it), but without its
            // per-draw costs - joint-only pose uploads with exact byte counts, a
            // change-gated part section, cached uniform locations and a
            // change-gated vertex-format specification. Its per-mesh pose SSBO is
            // dynamically sized, so it also renders meshes exceeding Epic Fight's
            // MAX_JOINTS capacity. Falls through to Epic Fight's path when
            // unavailable (no Oculus, setup failure).
            long tIris = com.ysmef.compat.YsmDiag.isEnabled() ? System.nanoTime() : 0L;
            if (com.ysmef.compat.gpu.YsmIrisComputePath.tryRender(this, setup, poseStack, bufferSources,
                    renderType, packedLight, r, g, b, a, overlay, armature, poses)) {
                com.ysmef.compat.YsmDiag.addNanos(com.ysmef.compat.YsmDiag.SLOT_COMPUTE_PATH, System.nanoTime() - tIris);
                return;
            }
            // Epic Fight's compute paths (VanillaComputeShaderSetup and the Iris
            // variant used under shader packs) stage poses.length + partCount
            // matrices in the static ComputeShaderSetup.TOTAL_POSES array, whose
            // capacity is EpicFightSharedConstants.MAX_JOINTS (1000). A converted
            // YSM model with more joints+parts than that overflows the array
            // (TOTAL_POSES[poses.length + partIdx] and the POSE_BO upload) and
            // crashes the game with an ArrayIndexOutOfBoundsException - draw it
            // with this mod's own CPU skinning path instead, even under a shader
            // pack (the pack's shaders are bypassed for this model, but the
            // alternative is a crash).
            if (poses != null && poses.length + this.getAllParts().size()
                    > yesman.epicfight.main.EpicFightSharedConstants.MAX_JOINTS) {
                renderOverCapacity(poseStack, bufferSources, drawingFunction, packedLight,
                        r, g, b, a, overlay, armature, poses, "compute");
                return;
            }
            long t0 = com.ysmef.compat.YsmDiag.isEnabled() ? System.nanoTime() : 0L;
            setup.drawWithShader(this, poseStack, bufferSources, renderType,
                    packedLight, r, g, b, a, overlay, armature, poses);
            com.ysmef.compat.YsmDiag.addNanos(com.ysmef.compat.YsmDiag.SLOT_COMPUTE_PATH, System.nanoTime() - t0);
            return;
        }
        logCpuFallbackOnce();
        // Epic Fight's drawPosed stages poses.length matrices in the same static
        // TOTAL_POSES array (MAX_JOINTS = 1000), so the same overflow guard
        // applies to the CPU fallback.
        if (poses != null && poses.length > yesman.epicfight.main.EpicFightSharedConstants.MAX_JOINTS) {
            renderOverCapacity(poseStack, bufferSources, drawingFunction, packedLight,
                    r, g, b, a, overlay, armature, poses, "drawPosed");
            return;
        }
        // Root cause of the original CPU-path missing faces: EpicFightRenderTypes
        // keeps ONE cache (TRIANGLED_RENDERTYPES_BY_NAME_TEXTURE) shared by
        // getTriangulated / addRenderType / replaceTexture. replaceTexture writes
        // the texture-replaced render type - with the ORIGINAL mode, QUADS for
        // vanilla entity render types - into that cache (L552-553), and the later
        // getTriangulated call hits the cache (L83-84) and returns the QUADS type
        // as-is. drawPosed then writes triangle-triplet vertices into a QUADS-mode
        // BufferBuilder, so the upload regroups every 4 vertices as a quad and
        // faces scramble/disappear. The compute path is immune because it draws
        // with a hardcoded glDrawArrays(TRIANGLES). makeTriangulated is the
        // cache-independent triangulator (already-TRIANGLES types pass through),
        // so the final Epic Fight drawPosed fallback receives a proper TRIANGLES
        // render type and renders the converted meshes completely.
        this.drawPosed(poseStack, bufferSources.getBuffer(EpicFightRenderTypes.makeTriangulated(renderType)),
                drawingFunction, packedLight, r, g, b, a, overlay, armature, poses);
    }

    private static final Set<String> DIAG_CPU_FALLBACK_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * Renders (or skips) a mesh whose joint/part count exceeds Epic Fight's
     * static pose-array capacity (EpicFightSharedConstants.MAX_JOINTS), which
     * every Epic Fight render path would overflow. The last-resort CPU skinning
     * path has no fixed capacity; outline passes are skipped entirely (a missing
     * outline beats a crash, and the main pass still renders the model).
     */
    private void renderOverCapacity(PoseStack poseStack, MultiBufferSource bufferSources,
                                    Mesh.DrawingFunction drawingFunction, int packedLight,
                                    float r, float g, float b, float a, int overlay,
                                    @Nullable Armature armature, OpenMatrix4f[] poses, String blockedPath) {
        logOverCapacityOnce(poses, blockedPath);
        if (bufferSources instanceof net.minecraft.client.renderer.OutlineBufferSource) {
            return;
        }
        com.ysmef.compat.cpu.YsmCpuRenderPath.tryRenderLastResort(this, poseStack, drawingFunction,
                packedLight, r, g, b, a, overlay, armature, poses);
    }

    private static final Set<String> OVER_CAPACITY_LOGGED = ConcurrentHashMap.newKeySet();

    /** Once per model + path: Epic Fight's pose array is too small for this mesh. */
    private void logOverCapacityOnce(OpenMatrix4f[] poses, String blockedPath) {
        String key = (this.runtimeModelId == null ? "n/a" : this.runtimeModelId) + "|" + blockedPath;
        if (OVER_CAPACITY_LOGGED.add(key)) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: model '{}' has {} joints + {} parts, exceeding Epic Fight's pose array "
                            + "capacity (MAX_JOINTS={}); the {} path would crash with an ArrayIndexOutOfBoundsException. "
                            + "Rendering with this mod's CPU skinning path instead (shader packs are bypassed for this model).",
                    key, poses.length, this.getAllParts().size(),
                    yesman.epicfight.main.EpicFightSharedConstants.MAX_JOINTS, blockedPath);
        }
    }

    private static void logCpuFallbackOnce() {
        if (DIAG_CPU_FALLBACK_LOGGED.add("cpu-fallback")) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: no compute shader setup available, using the CPU skinning path "
                            + "(SkinnedMeshCpuRenderMixin substitutes this mod's CPU renderer; "
                            + "Epic Fight's drawPosed remains the final fallback)");
        }
    }

    private static final java.lang.reflect.Field COMPUTE_SETUP_FIELD = findComputeSetupField();

    private static java.lang.reflect.Field findComputeSetupField() {
        try {
            java.lang.reflect.Field field = SkinnedMesh.class.getDeclaredField("computerShaderSetup");
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: cannot access Epic Fight compute shader setup field, using CPU path");
            return null;
        }
    }

    @Nullable
    private yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup computeShaderSetup() {
        if (COMPUTE_SETUP_FIELD == null) {
            return null;
        }
        try {
            return (yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup) COMPUTE_SETUP_FIELD.get(this);
        } catch (Throwable t) {
            return null;
        }
    }
}
