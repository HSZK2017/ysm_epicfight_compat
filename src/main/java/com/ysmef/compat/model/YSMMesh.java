package com.ysmef.compat.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ysmef.compat.gpu.YsmGpuRenderPath;
import com.ysmef.compat.model.runtime.YSMRuntimeBridge;
import net.minecraft.client.Minecraft;
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

    @Override
    public void draw(PoseStack poseStack, MultiBufferSource bufferSources, RenderType renderType,
                     Mesh.DrawingFunction drawingFunction, int packedLight, float r, float g, float b, float a,
                     int overlay, @Nullable Armature armature, OpenMatrix4f[] poses) {
        boolean maidEntity = isMaidEntity();
        if (Minecraft.getInstance().screen != null) {
            logPreviewDrawOnce(maidEntity, renderType);
        }
        YSMRuntimeBridge.apply(this, armature, poses);
        ResourceLocation texture = resolveTexture();
        logDrawDiagOnce(texture);
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
            // ModernYSM-style direct GPU skinning path (bone SSBO + skinning shader):
            // one glDrawArrays per model, vertex skinning fully on the GPU. Falls back
            // to Epic Fight's compute-shader path automatically when unavailable.
            if (texture != null && YsmGpuRenderPath.tryRender(this, poseStack, bufferSources, texture,
                    packedLight, r, g, b, a, overlay, armature, poses)) {
                return;
            }
            RenderType finalRenderType = texture != null
                    ? EpicFightRenderTypes.replaceTexture(texture, renderType)
                    : renderType;
            drawWithPreferredPath(poseStack, bufferSources, finalRenderType, drawingFunction,
                    packedLight, r, g, b, a, overlay, armature, poses);
        } finally {
            if (maidEntity) {
                poseStack.popPose();
            }
        }
    }

    /**
     * The inverse of EpicFight_TouhouLittleMaid's built-in maid scale
     * (MaidPatch#getModelMatrix, 0.8F). Update this if EFTLM changes it.
     */
    private static final float MAID_SCALE_COMPENSATION = 1.0f / 0.8f;

    private static volatile boolean PREVIEW_DIAG_LOGGED;

    /** One-time diagnostic: what the mesh draws with while a GUI screen is open. */
    private static void logPreviewDrawOnce(boolean maidEntity, RenderType renderType) {
        if (PREVIEW_DIAG_LOGGED) {
            return;
        }
        PREVIEW_DIAG_LOGGED = true;
        LivingEntity entity = YSMRuntimeBridge.getCurrentEntity();
        org.joml.Matrix4f proj = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] mesh draw with screen open: entity={} maid={} projection=({},{},{},{}) m30={} m31={} renderType={}",
                entity == null ? "null" : entity.getClass().getSimpleName(), maidEntity,
                proj.m00(), proj.m11(), proj.m22(), proj.m33(), proj.m30(), proj.m31(),
                renderType);
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
     * a compute setup (unsupported GPU) the CPU path is kept as a fallback.
     */
    private void drawWithPreferredPath(PoseStack poseStack, MultiBufferSource bufferSources, RenderType renderType,
                                       Mesh.DrawingFunction drawingFunction, int packedLight,
                                       float r, float g, float b, float a, int overlay,
                                       @Nullable Armature armature, OpenMatrix4f[] poses) {
        yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup setup = computeShaderSetup();
        if (setup != null) {
            setup.drawWithShader(this, poseStack, bufferSources, renderType,
                    packedLight, r, g, b, a, overlay, armature, poses);
            return;
        }
        logCpuFallbackOnce();
        this.drawPosed(poseStack, bufferSources.getBuffer(EpicFightRenderTypes.getTriangulated(renderType)),
                drawingFunction, packedLight, r, g, b, a, overlay, armature, poses);
    }

    private static final Set<String> DIAG_CPU_FALLBACK_LOGGED = ConcurrentHashMap.newKeySet();

    private static void logCpuFallbackOnce() {
        if (DIAG_CPU_FALLBACK_LOGGED.add("cpu-fallback")) {
            com.ysmef.compat.YSMEpicFightCompat.LOGGER.warn(
                    "YSM-EF Compat: no compute shader setup available, falling back to the CPU skinning path (converted meshes may render incompletely)");
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

    private static final Set<String> DIAG_DRAW_LOGGED = ConcurrentHashMap.newKeySet();

    /** One-time per model: part counts and the resolved texture at first draw. */
    private void logDrawDiagOnce(ResourceLocation texture) {
        if (this.runtimeModelId == null || !DIAG_DRAW_LOGGED.add(this.runtimeModelId)) {
            return;
        }
        int hidden = 0;
        int boneParts = 0;
        for (Map.Entry<String, MeshPart> entry : getPartEntrySetSafe()) {
            if (entry.getKey().startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                boneParts++;
                if (entry.getValue().isHidden()) {
                    hidden++;
                }
            }
        }
        com.ysmef.compat.YSMEpicFightCompat.LOGGER.info(
                "YSM-EF Compat: [diag] draw model='{}' boneParts={} hiddenAtFirstDraw={} texture='{}'",
                this.runtimeModelId, boneParts, hidden, texture);
    }
}
