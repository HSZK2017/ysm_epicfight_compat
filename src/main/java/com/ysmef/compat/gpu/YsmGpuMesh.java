package com.ysmef.compat.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.ysmef.compat.model.YSMMesh;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;
import yesman.epicfight.api.client.model.SkinnedMesh.SkinnedMeshPart;
import yesman.epicfight.api.client.model.VertexBuilder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GPU-side resources of one converted YSM-EF mesh (ported from ModernYSM's
 * GpuMesh/GpuMeshBuilder, adapted to Epic Fight's SkinnedMesh data model):
 *
 * - a static VBO with one entry per mesh element (32 bytes:
 *   pos 3f + uv 2f + normal 2_10_10_10 + boneId ushort + partId ushort +
 *   cullable byte), built once from the mesh's bind-pose vertices. Every
 *   converted mesh uses rigid 1.0 joint weights, so each vertex carries a
 *   single bone id (its Epic Fight joint) plus its part id; a mesh with
 *   multi-joint vertices is rejected and the Epic Fight compute path keeps
 *   rendering it.
 * - a dynamic bone SSBO with 144-byte BoneData entries: the joint matrices
 *   (poses x toOrigin, indices 0..jointCount-1) followed by the per-part YSM
 *   bind-space deltas (indices jointCount..), refilled every frame. The vertex
 *   shader combines them per vertex (joint x delta), exactly like Epic Fight's
 *   compute shader does.
 *
 * The mesh is drawn with a single glDrawArrays over all elements; hidden parts
 * are moved offscreen by the vertex shader (BoneData.isHidden).
 */
public final class YsmGpuMesh {

    /** Bytes per vertex: pos3f + uv2f + normal(2_10_10_10) + boneId(ushort) + partId(ushort) + cullable(byte) + pad. */
    static final int VERTEX_STRIDE = 32;
    /** Bytes per bone entry: mat4 transform + mat4 normal + packedLight + isHidden + pad0 + pad1. */
    static final int BONE_STRIDE = 144;

    public final int vao;
    public final int vbo;
    public final int boneSsbo;
    public final int vertexCount;
    /** Number of SSBO entries: armature joints + mesh parts. */
    public final int boneCount;
    /** Epic Fight joint id of each part (or -1 for empty parts). */
    public final int[] jointOfPart;
    public final ByteBuffer perFrameBoneBuffer;

    private boolean disposed = false;

    private YsmGpuMesh(int vao, int vbo, int boneSsbo, int vertexCount, int boneCount,
                       int[] jointOfPart, ByteBuffer perFrameBoneBuffer) {
        this.vao = vao;
        this.vbo = vbo;
        this.boneSsbo = boneSsbo;
        this.vertexCount = vertexCount;
        this.boneCount = boneCount;
        this.jointOfPart = jointOfPart;
        this.perFrameBoneBuffer = perFrameBoneBuffer;
    }

    /**
     * Build the GPU resources of a mesh on the render thread. Returns null when
     * the mesh cannot be uploaded (unsupported vertex data, no geometry) - the
     * caller then keeps using Epic Fight's compute-shader path.
     *
     * @param jointCount number of armature joints (poses.length of the first draw)
     */
    static YsmGpuMesh build(YSMMesh mesh, int jointCount) {
        RenderSystem.assertOnRenderThread();

        float[] positions = mesh.positions();
        float[] normals = mesh.normals();
        float[] uvs = mesh.uvs();
        int[] jointCounts = mesh.affectingJointCounts();
        int[][] jointIndices = mesh.affectingJointIndices();
        int[][] weightIndices = mesh.affectingWeightIndices();
        float[] weights = mesh.weights();

        int partCount = mesh.getPartCount();
        int[] jointOfPart = new int[partCount];
        int totalVertices = 0;
        int partIdx = 0;
        for (SkinnedMeshPart part : mesh.getAllParts()) {
            if (partIdx >= partCount) {
                return null;
            }
            for (VertexBuilder vb : part.getVertices()) {
                int vcount = vb.position < jointCounts.length ? jointCounts[vb.position] : 0;
                if (vcount != 1 || vb.position >= jointIndices.length || vb.position >= weightIndices.length) {
                    return null;
                }
                int joint = jointIndices[vb.position][0];
                float weight = weightIndices[vb.position][0] < weights.length
                        ? weights[weightIndices[vb.position][0]] : 0.0f;
                if (weight < 0.999f || joint < 0 || joint >= jointCount || joint > 0xFFFF) {
                    return null;
                }
                if (vb.position * 3 + 2 >= positions.length || vb.normal * 3 + 2 >= normals.length
                        || vb.uv * 2 + 1 >= uvs.length) {
                    return null;
                }
                jointOfPart[partIdx] = joint;
                totalVertices++;
            }
            partIdx++;
        }
        if (totalVertices == 0 || partCount == 0) {
            return null;
        }

        ByteBuffer vertexBuf = MemoryUtil.memAlloc(totalVertices * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
        try {
            partIdx = 0;
            for (SkinnedMeshPart part : mesh.getAllParts()) {
                for (VertexBuilder vb : part.getVertices()) {
                    int p = vb.position * 3;
                    int n = vb.normal * 3;
                    int u = vb.uv * 2;
                    vertexBuf.putFloat(positions[p]);
                    vertexBuf.putFloat(positions[p + 1]);
                    vertexBuf.putFloat(positions[p + 2]);
                    vertexBuf.putFloat(uvs[u]);
                    vertexBuf.putFloat(uvs[u + 1]);
                    vertexBuf.putInt(packNormal(normals[n], normals[n + 1], normals[n + 2]));
                    vertexBuf.putShort((short) jointOfPart[partIdx]); // boneId at 24 (ushort)
                    vertexBuf.putShort((short) partIdx);              // partId at 26 (ushort)
                    vertexBuf.put((byte) 0);                          // cullable at 28: EF renders both faces
                    vertexBuf.put((byte) 0);                          // pad 29
                    vertexBuf.put((byte) 0);                          // pad 30
                    vertexBuf.put((byte) 0);                          // pad 31
                }
                partIdx++;
            }
            if (vertexBuf.position() != totalVertices * VERTEX_STRIDE) {
                // layout regression guard: the attribute stride must match the written bytes
                return null;
            }
            vertexBuf.flip();

            int vao = GL30.glGenVertexArrays();
            int vbo = GlStateManager._glGenBuffers();
            int boneSsbo = GlStateManager._glGenBuffers();

            GL30.glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuf, GL15.GL_STATIC_DRAW);

            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL15.GL_FLOAT, false, VERTEX_STRIDE, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL15.GL_FLOAT, false, VERTEX_STRIDE, 12L);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 4, GL33.GL_INT_2_10_10_10_REV, true, VERTEX_STRIDE, 20L);
            GL20.glEnableVertexAttribArray(3);
            GL30.glVertexAttribIPointer(3, 1, GL11.GL_UNSIGNED_SHORT, VERTEX_STRIDE, 24L);
            GL20.glEnableVertexAttribArray(4);
            GL30.glVertexAttribIPointer(4, 1, GL11.GL_UNSIGNED_SHORT, VERTEX_STRIDE, 26L);
            GL20.glEnableVertexAttribArray(5);
            GL20.glVertexAttribPointer(5, 1, GL11.GL_UNSIGNED_BYTE, false, VERTEX_STRIDE, 28L);

            GL30.glBindVertexArray(0);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            GlStateManager._glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, boneSsbo);
            GL43.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long) (jointCount + partCount) * BONE_STRIDE, GL15.GL_DYNAMIC_DRAW);
            GlStateManager._glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            return new YsmGpuMesh(vao, vbo, boneSsbo, totalVertices, jointCount + partCount, jointOfPart,
                    MemoryUtil.memAlloc((jointCount + partCount) * BONE_STRIDE).order(ByteOrder.nativeOrder()));
        } finally {
            MemoryUtil.memFree(vertexBuf);
        }
    }

    /** Pack a normal into the GL_INT_2_10_10_10_REV layout (same unpack as bone_skin.vsh). */
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

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        GlStateManager._glDeleteBuffers(vbo);
        GlStateManager._glDeleteBuffers(boneSsbo);
        GL30.glDeleteVertexArrays(vao);
        MemoryUtil.memFree(perFrameBoneBuffer);
    }
}
