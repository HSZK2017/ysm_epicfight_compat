package com.ysmef.compat.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/**
 * GPU capability probe for the direct skinning path (ported from ModernYSM's
 * GpuCapability, minus the native-library check - this mod's GPU path is pure
 * GL). Requires either desktop OpenGL 4.3 (or the equivalent ARB extensions)
 * or OpenGL ES 3.1 (Android launchers like Fold Craft Launcher / Zalith, where
 * SSBO, layout bindings, explicit attribute locations and the packed
 * 2_10_10_10 vertex format are all core features).
 */
public final class YsmGpuCapability {

    private static volatile boolean checked = false;
    private static volatile boolean available = false;
    private static volatile boolean gles = false;
    private static volatile String reason = null;

    private YsmGpuCapability() {}

    public static boolean isAvailable() {
        if (!checked) {
            check();
        }
        return available;
    }

    /** Whether the active context is OpenGL ES (Android); only valid after check(). */
    public static boolean isGles() {
        if (!checked) {
            check();
        }
        return gles;
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

            if (glVersion.startsWith("OpenGL ES")) {
                // Android launchers (Fold Craft Launcher / Zalith) expose OpenGL ES.
                // ES 3.1 provides SSBO, layout(binding=...), layout(location=...)
                // and 2_10_10_10 vertices as core features; the SSBO function
                // pointer presence is the final gate.
                if (esAtLeast31(glVersion) && caps.glShaderStorageBlockBinding != 0) {
                    gles = true;
                    available = true;
                    reason = "ok (GLES " + glVersion + ", " + glRenderer + ")";
                    return;
                }
                reason = "OpenGL ES 3.1 not supported (got " + glVersion + ")";
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

    /** Parse "OpenGL ES 3.1 <renderer>" and require >= 3.1. */
    private static boolean esAtLeast31(String glVersion) {
        String rest = glVersion.substring("OpenGL ES".length()).trim();
        int space = rest.indexOf(' ');
        String ver = space > 0 ? rest.substring(0, space) : rest;
        String[] parts = ver.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 3 || (major == 3 && minor >= 1);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
