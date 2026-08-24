package com.ysmef.compat.cpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;
import yesman.epicfight.api.client.model.VertexBuilder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * GPU-side resources of one converted YSM-EF mesh for the CPU skinning path.
 *
 * Unlike {@code YsmGpuMesh} (bone SSBO + skinning vertex shader) this mesh
 * holds only a single DYNAMIC VBO: every frame the CPU skins each vertex
 * (joint pose x toOrigin x per-part YSM bind-space delta) and streams the
 * resulting model-space positions/normals into the VBO, which is then drawn
 * with one glDrawArrays through {@code cpu_skin.vsh} - no SSBO, no compute
 * shader, so the path works on GPUs below OpenGL 4.3 (desktop GL 3.3 /
 * OpenGL ES 3.0) and with far less memory than the compute/GPU pipelines.
 *
 * Layout per vertex (24 bytes): position 3f, uv 2f, normal as
 * GL_INT_2_10_10_10_REV (same packing/unpacking as the GPU path).
 */
public final class YsmCpuMesh {

    /** Bytes per vertex: pos3f + uv2f + normal(2_10_10_10) - see the class doc. */
    static final int VERTEX_STRIDE = 24;

    public final int vao;
    public final int vbo;
    /** Maximum number of vertices the VBO can hold (total across all parts). */
    public final int capacity;
    /** Reused CPU-side accumulation buffer (capacity x VERTEX_STRIDE bytes). */
    public final ByteBuffer cpuBuffer;

    private boolean disposed = false;

    private YsmCpuMesh(int vao, int vbo, int capacity, ByteBuffer cpuBuffer) {
        this.vao = vao;
        this.vbo = vbo;
        this.capacity = capacity;
        this.cpuBuffer = cpuBuffer;
    }

    /**
     * Build the CPU-path resources of a mesh on the render thread. Returns null
     * when the mesh has no geometry - the caller then keeps using Epic Fight's
     * drawPosed CPU skinning.
     *
     * @param partVertices all mesh parts' vertex lists (in part ordinal order)
     */
    static YsmCpuMesh build(List<List<VertexBuilder>> partVertices) {
        RenderSystem.assertOnRenderThread();

        int totalVertices = 0;
        for (List<VertexBuilder> vertices : partVertices) {
            totalVertices += vertices.size();
        }
        if (totalVertices == 0) {
            return null;
        }

        ByteBuffer scratch = MemoryUtil.memAlloc(totalVertices * VERTEX_STRIDE).order(ByteOrder.nativeOrder());
        int vao = GL30.glGenVertexArrays();
        int vbo = GlStateManager._glGenBuffers();

        GL30.glBindVertexArray(vao);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        // capacity only: the per-frame skinned data is streamed via
        // glBufferSubData after an orphaning glBufferData(NULL) - the access
        // pattern is write-once-per-frame (STREAM), not modify-reuse (DYNAMIC)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) totalVertices * VERTEX_STRIDE, GL15.GL_STREAM_DRAW);

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, VERTEX_STRIDE, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, VERTEX_STRIDE, 12L);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL33.GL_INT_2_10_10_10_REV, true, VERTEX_STRIDE, 20L);

        GL30.glBindVertexArray(0);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        return new YsmCpuMesh(vao, vbo, totalVertices, scratch);
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        GlStateManager._glDeleteBuffers(vbo);
        GL30.glDeleteVertexArrays(vao);
        MemoryUtil.memFree(cpuBuffer);
    }
}
