package com.ysmef.compat.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.client.model.SkinnedMesh.SkinnedMeshPart;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup;
import yesman.epicfight.client.renderer.shader.compute.backend.buffers.DynamicSSBO;
import yesman.epicfight.client.renderer.shader.compute.backend.buffers.OutputSSBO;
import yesman.epicfight.client.renderer.shader.compute.backend.buffers.StaticSSBO;
import yesman.epicfight.client.renderer.shader.compute.loader.ComputeShaderProvider;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * GPU-side resources of one converted YSM mesh for the optimized Iris compute
 * path (see YsmIrisComputePath). The buffer layout replicates Epic Fight's
 * IrisComputeShaderSetup exactly, so Epic Fight's own iris_mesh_transformer.comp
 * produces byte-identical output:
 *
 * - static SSBOs (built once): ElementsPool (binding 1), VertexBuffer (2),
 *   JointsPool (3), midUV (bound directly as a vertex attribute), and the 15-float
 *   per-vertex output SSBO (5);
 * - a per-mesh pose SSBO (0) sized joints+parts instead of Epic Fight's shared
 *   1000-entry POSE_BO: the joint section (poses x toOrigin) is re-uploaded
 *   every frame with an EXACT byte count (Epic Fight's DynamicSSBO.updateFromTo
 *   uploads the whole 64 KB buffer per draw), and the part section (bind-space
 *   deltas, identity in battle mode) is uploaded only when its content changes
 *   (the same change-gating YsmGpuMesh uses for its part section);
 * - a hidden-flag bitfield SSBO (4), refreshed every frame (a few dozen bytes);
 * - a VAO whose attribute specification is re-issued only when the shader's
 *   vertex format changes (Epic Fight re-specifies ~12 attributes per draw).
 *
 * The pose SSBO has no MAX_JOINTS cap, so this path also renders the
 * over-capacity meshes that Epic Fight's static arrays cannot.
 */
public final class YsmIrisMesh {

    static final int MAT4_FLOATS = 16;
    static final int MAT4_BYTES = 64;
    /** Floats per output vertex (Epic Fight's iris outBufferSize): 60-byte stride. */
    private static final int OUT_FLOATS = 15;

    public final int vao;
    public final int vertexCount;
    public final int partCount;
    public final StaticSSBO<ComputeShaderSetup.ElemInfo> elementsBO;
    public final StaticSSBO<ComputeShaderSetup.VertexObj> vObjBO;
    public final StaticSSBO<ComputeShaderSetup.WeightInfo> jointBO;
    public final StaticSSBO<Float> midUVBO;
    public final DynamicSSBO<Integer> hiddenFlagsBO;
    public final OutputSSBO outBO;

    public int poseSsbo;
    private int poseCapacity;
    private FloatBuffer jointStaging;
    private FloatBuffer partStaging;
    private final Integer[] hiddenFlags;

    /** Change-gating for the part section (mirrors YsmGpuMesh's part cache). */
    private final boolean[] cachedPartIdentity;
    private boolean partSectionValid = false;
    private int lastJointCount = -1;

    /** Vertex format currently specified in the VAO (re-spec only on change). */
    private VertexFormat lastFormat;

    private boolean disposed = false;

    /** Per-armature to-origin matrices (joint space -> model space). */
    private static final Map<Armature, OpenMatrix4f[]> TO_ORIGIN_CACHE = new IdentityHashMap<>();
    private static final Map<Armature, Integer> POSE_LENGTH_CACHE = new IdentityHashMap<>();

    private static final OpenMatrix4f jointScratch = new OpenMatrix4f();

    private YsmIrisMesh(int vao, int vertexCount, int partCount,
                        StaticSSBO<ComputeShaderSetup.ElemInfo> elementsBO,
                        StaticSSBO<ComputeShaderSetup.VertexObj> vObjBO,
                        StaticSSBO<ComputeShaderSetup.WeightInfo> jointBO,
                        StaticSSBO<Float> midUVBO,
                        DynamicSSBO<Integer> hiddenFlagsBO, Integer[] hiddenFlags,
                        OutputSSBO outBO, int poseSsbo, int poseCapacity) {
        this.vao = vao;
        this.vertexCount = vertexCount;
        this.partCount = partCount;
        this.elementsBO = elementsBO;
        this.vObjBO = vObjBO;
        this.jointBO = jointBO;
        this.midUVBO = midUVBO;
        this.hiddenFlagsBO = hiddenFlagsBO;
        this.hiddenFlags = hiddenFlags;
        this.outBO = outBO;
        this.poseSsbo = poseSsbo;
        this.poseCapacity = poseCapacity;
        this.jointStaging = BufferUtils.createFloatBuffer(poseCapacity * MAT4_FLOATS);
        this.partStaging = BufferUtils.createFloatBuffer(partCount * MAT4_FLOATS);
        this.cachedPartIdentity = new boolean[partCount];
        Arrays.fill(this.cachedPartIdentity, true);
    }

    /**
     * Build the GL resources of a mesh on the render thread, replicating the
     * buffer construction of Epic Fight's ComputeShaderSetup constructor (same
     * element pool dedup, same weight ranges, same midUV precompute).
     */
    @Nullable
    public static YsmIrisMesh build(com.ysmef.compat.model.YSMMesh mesh, int jointCount) {
        RenderSystem.assertOnRenderThread();

        Map<VertexBuilder, Integer> vertexBuilderMap = new HashMap<>();
        List<ComputeShaderSetup.ElemInfo> elements = new ArrayList<>();
        List<Float> uvList = new ArrayList<>();

        int partIdx = 0;
        int partCount = 0;
        for (SkinnedMeshPart part : mesh.getAllParts()) {
            for (VertexBuilder vb : part.getVertices()) {
                Integer poolIdx = vertexBuilderMap.get(vb);
                if (poolIdx == null) {
                    poolIdx = vertexBuilderMap.size();
                    vertexBuilderMap.put(vb, poolIdx);
                    uvList.add(mesh.uvs()[vb.uv * 2]);
                    uvList.add(mesh.uvs()[vb.uv * 2 + 1]);
                }
                elements.add(new ComputeShaderSetup.ElemInfo(poolIdx, partIdx));
            }
            partIdx++;
            partCount++;
        }
        if (elements.isEmpty()) {
            return null;
        }

        ComputeShaderSetup.VertexObj[] vertexObjs = new ComputeShaderSetup.VertexObj[vertexBuilderMap.size()];
        List<ComputeShaderSetup.WeightInfo> jointList = new ArrayList<>();
        float[] positions = mesh.positions();
        float[] normals = mesh.normals();
        float[] uvs = mesh.uvs();
        int[] jointCounts = mesh.affectingJointCounts();
        int[][] jointIndices = mesh.affectingJointIndices();
        int[][] weightIndices = mesh.affectingWeightIndices();
        float[] weights = mesh.weights();

        for (Map.Entry<VertexBuilder, Integer> entry : vertexBuilderMap.entrySet()) {
            VertexBuilder vb = entry.getKey();
            int idx = entry.getValue();
            int startPos = jointList.size();
            int count = vb.position < jointCounts.length ? jointCounts[vb.position] : 0;
            for (int i = 0; i < count; i++) {
                int jointIndex = jointIndices[vb.position][i];
                int weightIndex = weightIndices[vb.position][i];
                float weight = weightIndex < weights.length ? weights[weightIndex] : 0.0f;
                jointList.add(new ComputeShaderSetup.WeightInfo(jointIndex, weight));
            }
            vertexObjs[idx] = new ComputeShaderSetup.VertexObj(
                    positions[vb.position * 3], positions[vb.position * 3 + 1], positions[vb.position * 3 + 2],
                    normals[vb.normal * 3], normals[vb.normal * 3 + 1], normals[vb.normal * 3 + 2],
                    uvs[vb.uv * 2], uvs[vb.uv * 2 + 1],
                    startPos, startPos + count);
        }

        // Per-face averaged UVs (Epic Fight's initAttachmentSSBO, verbatim).
        List<Float> midUVList = new ArrayList<>();
        float[] midUVs = new float[(elements.size() / 3) * 2];
        for (int i = 0; i < elements.size(); i++) {
            int vertPoolIdx = elements.get(i).poolId();
            float u = uvList.get(vertPoolIdx * 2);
            float v = uvList.get(vertPoolIdx * 2 + 1);
            int faceIdx = i / 3;
            if (i % 3 == 0) {
                midUVs[faceIdx * 2] = u / 3;
                midUVs[faceIdx * 2 + 1] = v / 3;
            } else {
                midUVs[faceIdx * 2] += u / 3;
                midUVs[faceIdx * 2 + 1] += v / 3;
            }
        }
        for (int i = 0; i < elements.size(); i++) {
            int faceIdx = i / 3;
            midUVList.add(midUVs[faceIdx * 2]);
            midUVList.add(midUVs[faceIdx * 2 + 1]);
        }

        Integer[] hiddenFlags = new Integer[(partCount + 31) / 32];
        Arrays.fill(hiddenFlags, 0);

        int vao = GlStateManager._glGenVertexArrays();
        StaticSSBO<ComputeShaderSetup.ElemInfo> elementsBO = new StaticSSBO<>(elements, 2, ComputeShaderSetup.ElemInfo::store);
        StaticSSBO<ComputeShaderSetup.VertexObj> vObjBO = new StaticSSBO<>(Arrays.asList(vertexObjs), 10, ComputeShaderSetup.VertexObj::store);
        StaticSSBO<ComputeShaderSetup.WeightInfo> jointBO = new StaticSSBO<>(jointList, 2, ComputeShaderSetup.WeightInfo::store);
        StaticSSBO<Float> midUVBO = new StaticSSBO<>(midUVList, 1, (v, b) -> b.put(v));
        DynamicSSBO<Integer> hiddenFlagsBO = (DynamicSSBO<Integer>) ComputeShaderProvider.createDynamicBuffer(
                hiddenFlags, 1, (v, b) -> b.put(Float.intBitsToFloat(v)));
        OutputSSBO outBO = new OutputSSBO((short) OUT_FLOATS, elements.size(), DynamicSSBO.DataMode.STREAM);

        int poseCapacity = Math.max(jointCount + partCount, 64);
        int poseSsbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, poseSsbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) poseCapacity * MAT4_BYTES, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        return new YsmIrisMesh(vao, elements.size(), partCount, elementsBO, vObjBO, jointBO,
                midUVBO, hiddenFlagsBO, hiddenFlags, outBO, poseSsbo, poseCapacity);
    }

    /**
     * Write and upload the pose SSBO for this frame. The joint section
     * (poses[j] x toOrigin(j), EF's TOTAL_POSES equivalent) is uploaded every
     * frame with an exact byte count; the part section (bind-space deltas) is
     * uploaded only when a delta appears/changes/disappears or the joint count
     * moved the section offset. Hidden flags are refreshed every frame (tiny).
     */
    public void fillPoses(com.ysmef.compat.model.YSMMesh mesh, @Nullable Armature armature, OpenMatrix4f[] poses) {
        int jointCount = poses.length;
        ensurePoseCapacity(jointCount + this.partCount);
        OpenMatrix4f[] toOrigin = toOriginOf(armature, jointCount);

        // hidden flags: one bit per part, recomputed every frame (a few ints).
        Arrays.fill(this.hiddenFlags, 0);
        int i = 0;
        for (SkinnedMeshPart part : mesh.getAllParts()) {
            if (part.isHidden()) {
                this.hiddenFlags[i / 32] |= (1 << (i % 32));
            }
            i++;
        }
        this.hiddenFlagsBO.updateAll();

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.poseSsbo);

        // joints: exact-size upload every frame
        FloatBuffer joints = this.jointStaging;
        joints.clear();
        for (int j = 0; j < jointCount; j++) {
            jointScratch.load(poses[j]);
            jointScratch.mulBack(toOrigin[j]);
            jointScratch.store(joints);
        }
        joints.flip();
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, joints);

        // parts: refresh the staging every frame (cheap CPU), upload only on change
        boolean dirty = !this.partSectionValid || this.lastJointCount != jointCount;
        FloatBuffer parts = this.partStaging;
        for (int p = 0; p < this.partCount; p++) {
            OpenMatrix4f delta = mesh.getPartTransform(p);
            parts.position(p * MAT4_FLOATS);
            if (delta != null) {
                if (this.cachedPartIdentity[p]) {
                    this.cachedPartIdentity[p] = false;
                    dirty = true;
                }
                delta.store(parts);
            } else {
                // a transform fading back to identity is a content change too
                if (!this.cachedPartIdentity[p]) {
                    this.cachedPartIdentity[p] = true;
                    dirty = true;
                }
                OpenMatrix4f.IDENTITY.store(parts);
            }
        }
        if (dirty) {
            parts.position(0);
            parts.limit(this.partCount * MAT4_FLOATS);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long) jointCount * MAT4_BYTES, parts);
            this.partSectionValid = true;
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        this.lastJointCount = jointCount;
    }

    private void ensurePoseCapacity(int entries) {
        if (entries <= this.poseCapacity) {
            return;
        }
        int newCap = Math.max(entries, this.poseCapacity * 2);
        GL15.glDeleteBuffers(this.poseSsbo);
        this.poseSsbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.poseSsbo);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) newCap * MAT4_BYTES, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        this.jointStaging = BufferUtils.createFloatBuffer(newCap * MAT4_FLOATS);
        this.poseCapacity = newCap;
        // the part section must be re-uploaded after a capacity move
        this.partSectionValid = false;
    }

    /**
     * Specify the VAO's attribute layout for the given shader vertex format
     * (Epic Fight's IrisComputeShaderSetup#bindBufferFormat). Re-issued only
     * when the format instance changes; consecutive draws with the same shader
     * cost a single reference comparison. The attribute state lives in this
     * path's own VAO, and the caller pairs the draw with
     * BufferUploader.invalidate(), so the vanilla pipeline re-specifies its own
     * state afterwards (no per-draw clearBufferState like Epic Fight).
     */
    public void bindFormatIfNeeded(VertexFormat format) {
        if (format == this.lastFormat) {
            return;
        }
        this.lastFormat = format;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.outBO.glSSBO);

        int midUvPos = -1;
        List<VertexFormatElement> elems = format.getElements();
        for (int i = 0; i < elems.size(); i++) {
            VertexFormatElement elem = elems.get(i);
            if (elem == DefaultVertexFormat.ELEMENT_POSITION) {
                GL20.glVertexAttribPointer(i, 3, GL11.GL_FLOAT, false, 60, 0L);
                GL20.glEnableVertexAttribArray(i);
            } else if (elem == DefaultVertexFormat.ELEMENT_UV) {
                GL20.glVertexAttribPointer(i, 2, GL11.GL_FLOAT, false, 60, 28L);
                GL20.glEnableVertexAttribArray(i);
            } else if (elem == DefaultVertexFormat.ELEMENT_COLOR) {
                GL20.glVertexAttribPointer(i, 4, GL11.GL_FLOAT, true, 60, 12L);
                GL20.glEnableVertexAttribArray(i);
            } else if (elem == DefaultVertexFormat.ELEMENT_NORMAL) {
                GL20.glVertexAttribPointer(i, 3, GL11.GL_BYTE, true, 60, 36L);
                GL20.glEnableVertexAttribArray(i);
            } else if (elem == DefaultVertexFormat.ELEMENT_UV1) {
                GL30.glVertexAttribIPointer(i, 2, GL11.GL_UNSIGNED_SHORT, 60, 40L);
                GL20.glEnableVertexAttribArray(i);
            } else if (elem == DefaultVertexFormat.ELEMENT_UV2) {
                GL30.glVertexAttribIPointer(i, 2, GL11.GL_UNSIGNED_SHORT, 60, 44L);
                GL20.glEnableVertexAttribArray(i);
            } else if (elem == YsmIrisComputePath.IRIS_ENTITY_ID_ELEMENT) {
                GL30.glVertexAttribIPointer(i, 3, GL11.GL_UNSIGNED_SHORT, 60, 48L);
                GL20.glEnableVertexAttribArray(i);
            } else if (elem == YsmIrisComputePath.IRIS_MID_TEXTURE_ELEMENT) {
                midUvPos = i;
            } else if (elem == YsmIrisComputePath.IRIS_TANGENT_ELEMENT) {
                GL20.glVertexAttribPointer(i, 4, GL11.GL_BYTE, false, 60, 56L);
                GL20.glEnableVertexAttribArray(i);
            }
        }

        if (midUvPos >= 0) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.midUVBO.glSSBO);
            GL20.glVertexAttribPointer(midUvPos, 2, GL11.GL_FLOAT, false, 0, 0L);
            GL20.glEnableVertexAttribArray(midUvPos);
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static OpenMatrix4f[] toOriginOf(@Nullable Armature armature, int jointCount) {
        synchronized (TO_ORIGIN_CACHE) {
            OpenMatrix4f[] cached = TO_ORIGIN_CACHE.get(armature);
            Integer cachedLen = POSE_LENGTH_CACHE.get(armature);
            if (cached != null && cachedLen != null && cachedLen == jointCount) {
                return cached;
            }
            OpenMatrix4f[] toOrigin = new OpenMatrix4f[jointCount];
            for (int j = 0; j < jointCount; j++) {
                Joint joint = armature != null ? armature.searchJointById(j) : null;
                toOrigin[j] = joint != null ? joint.getToOrigin() : OpenMatrix4f.IDENTITY;
            }
            TO_ORIGIN_CACHE.put(armature, toOrigin);
            POSE_LENGTH_CACHE.put(armature, jointCount);
            return toOrigin;
        }
    }

    public void dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        this.elementsBO.close();
        this.vObjBO.close();
        this.jointBO.close();
        this.midUVBO.close();
        this.hiddenFlagsBO.close();
        this.outBO.close();
        if (this.poseSsbo != 0) {
            GL15.glDeleteBuffers(this.poseSsbo);
            this.poseSsbo = 0;
        }
        RenderSystem.glDeleteVertexArrays(this.vao);
        synchronized (TO_ORIGIN_CACHE) {
            TO_ORIGIN_CACHE.clear();
            POSE_LENGTH_CACHE.clear();
        }
    }
}
