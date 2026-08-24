package com.ysmef.compat.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * Snapshot/restore of the small set of GL states the direct skinning paths
 * modify (cull face, blend, depth test, depth write).
 *
 * The vanilla renderer usually re-establishes its own state per RenderType,
 * but non-standard render chains (custom blend/depth-mask render types, other
 * mods' passes) rely on callers leaving GL state untouched, and leaving
 * depthMask(true) behind after a translucent draw lets that draw pollute depth
 * for everything after it. Both the GPU and the CPU skinning path therefore
 * capture before and restore after their draw.
 *
 * Render-thread only (glIsEnabled/glGetBooleanv and the restore calls must run
 * on the GL thread); the scratch buffer is a static because the paths are
 * single-threaded by contract.
 */
public final class GlRenderState {

    private static final ByteBuffer DEPTH_WRITE_SCRATCH = ByteBuffer.allocateDirect(16);

    private GlRenderState() {}

    /** Immutable snapshot of the four managed states. */
    public static final class Snapshot {
        public final boolean cull;
        public final boolean blend;
        public final boolean depthTest;
        public final boolean depthWrite;

        private Snapshot(boolean cull, boolean blend, boolean depthTest, boolean depthWrite) {
            this.cull = cull;
            this.blend = blend;
            this.depthTest = depthTest;
            this.depthWrite = depthWrite;
        }
    }

    public static Snapshot capture() {
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        DEPTH_WRITE_SCRATCH.clear();
        GL11.glGetBooleanv(GL11.GL_DEPTH_WRITEMASK, DEPTH_WRITE_SCRATCH);
        boolean depthWrite = DEPTH_WRITE_SCRATCH.get(0) != 0;
        return new Snapshot(cull, blend, depthTest, depthWrite);
    }

    /** Restore the exact states captured earlier. */
    public static void restore(Snapshot snapshot) {
        if (snapshot.cull) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }
        if (snapshot.blend) {
            RenderSystem.enableBlend();
        } else {
            RenderSystem.disableBlend();
        }
        if (snapshot.depthTest) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        RenderSystem.depthMask(snapshot.depthWrite);
    }
}
