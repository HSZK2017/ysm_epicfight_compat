package com.ysmef.compat.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/**
 * GPU capability probe for the direct skinning path (ported from ModernYSM's
 * GpuCapability, minus the native-library check - this mod's GPU path is pure
 * GL). Requires OpenGL 4.3 (or the equivalent ARB extensions) for the bone
 * SSBO + the packed 2_10_10_10 vertex format.
 */
public final class YsmGpuCapability {

    private static volatile boolean checked = false;
    private static volatile boolean available = false;
    private static volatile String reason = null;

    private YsmGpuCapability() {}

    public static boolean isAvailable() {
        if (!checked) {
            check();
        }
        return available;
    }

    public static String getReason() {
        if (!checked) {
            check();
        }
        return reason;
    }

    public static synchronized void check() {
        if (checked) {
            return;
        }
        checked = true;

        if (System.getProperty("ysm_ef_compat.disable_gpu") != null) {
            reason = "gpu renderer disabled via system property ysm_ef_compat.disable_gpu";
            return;
        }
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac") || osName.contains("darwin")) {
            reason = "macOS GL is capped at 4.1 and lacks GL_ARB_shader_storage_buffer_object";
            return;
        }

        String glVersion;
        String glRenderer;
        try {
            RenderSystem.assertOnRenderThreadOrInit();
            GLCapabilities caps = GL.getCapabilities();
            glVersion = GL11.glGetString(GL11.GL_VERSION);
            glRenderer = GL11.glGetString(GL11.GL_RENDERER);
            if (glVersion == null) {
                reason = "GL version not available";
                return;
            }
            if (!caps.OpenGL30) {
                reason = "OpenGL 3.0 not supported (got " + glVersion + ")";
                return;
            }
            boolean hasSsbo = caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object;
            boolean hasIfaceQuery = caps.OpenGL43 || caps.GL_ARB_program_interface_query;
            boolean hasLayoutBinding = caps.OpenGL42 || caps.GL_ARB_shading_language_420pack;
            boolean hasExplicitAttrib = caps.OpenGL33 || caps.GL_ARB_explicit_attrib_location;
            boolean hasPackedNormal = caps.OpenGL33 || caps.GL_ARB_vertex_type_2_10_10_10_rev;
            if (!hasSsbo) {
                reason = "SSBO not supported, GL_VERSION=" + glVersion;
                return;
            }
            if (!hasIfaceQuery) {
                reason = "GL_ARB_program_interface_query not supported; GL_VERSION=" + glVersion;
                return;
            }
            if (!hasLayoutBinding) {
                reason = "GL_ARB_shading_language_420pack not supported; GL_VERSION=" + glVersion;
                return;
            }
            if (!hasExplicitAttrib) {
                reason = "GL_ARB_explicit_attrib_location not supported; GL_VERSION=" + glVersion;
                return;
            }
            if (!hasPackedNormal) {
                reason = "GL_ARB_vertex_type_2_10_10_10_rev not supported; GL_VERSION=" + glVersion;
                return;
            }
        } catch (Throwable t) {
            reason = "GL capabilities not available: " + t.getMessage();
            return;
        }

        available = true;
        reason = "ok (GL " + glVersion + ", " + glRenderer + ")";
    }
}
