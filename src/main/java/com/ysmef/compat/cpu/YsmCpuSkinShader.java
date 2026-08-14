package com.ysmef.compat.cpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.ysmef.compat.YSMEpicFightCompat;
import com.ysmef.compat.gpu.GpuShaderUtil;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLCapabilities;

/**
 * The GLSL program of the CPU skinning render path. The vertex shader takes
 * CPU-skinned model-space positions/normals as plain attributes (no SSBO, no
 * compute shader) and applies the same u_proj = proj x mv x poseStack matrix
 * reconstruction the GPU path uses, so the two paths are pixel-identical.
 *
 * The desktop shader is "#version 330 core" (minimum desktop OpenGL 3.3) and
 * the Android variant is "#version 300 es" (minimum OpenGL ES 3.0) - both far
 * below the GPU path's OpenGL 4.3 / ES 3.1 requirement, which is the whole
 * point of the CPU fallback.
 */
public final class YsmCpuSkinShader {

    private static final String VSH_PATH = "/ysm_epicfight_compat/shaders/cpu_skin.vsh";
    private static final String FSH_PATH = "/ysm_epicfight_compat/shaders/cpu_skin.fsh";
    private static final String VSH_ES_PATH = "/ysm_epicfight_compat/shaders/cpu_skin_es.vsh";
    private static final String FSH_ES_PATH = "/ysm_epicfight_compat/shaders/cpu_skin_es.fsh";

    private static int program = 0;
    private static int locProj = -1;
    private static int locMv = -1;
    private static int locIvr = -1;
    private static int locColor = -1;
    private static int locOverlay = -1;
    private static int locFogStart = -1;
    private static int locFogEnd = -1;
    private static int locFogColor = -1;
    private static int locFogShape = -1;
    private static int locLight0 = -1;
    private static int locLight1 = -1;
    private static int locAlphaMode = -1;
    private static int locPackedLight = -1;
    private static boolean failed = false;

    private static volatile String capabilityReason = "not checked";

    private YsmCpuSkinShader() {}

    /**
     * Whether the active context can run the CPU path shaders: desktop OpenGL
     * 3.3+ (or newer) or OpenGL ES 3.0+. This is deliberately independent of
     * the GPU path's OpenGL 4.3 / ES 3.1 gate - the CPU path exists precisely
     * for contexts below that.
     */
    public static boolean isCapabilityAvailable() {
        try {
            RenderSystem.assertOnRenderThreadOrInit();
            GLCapabilities caps = GL.getCapabilities();
            String glVersion = GL11.glGetString(GL11.GL_VERSION);
            if (glVersion == null) {
                capabilityReason = "GL version not available";
                return false;
            }
            if (glVersion.startsWith("OpenGL ES")) {
                if (esAtLeast30(glVersion)) {
                    capabilityReason = "ok (GLES " + glVersion + ")";
                    return true;
                }
                capabilityReason = "OpenGL ES 3.0 not supported (got " + glVersion + ")";
                return false;
            }
            if (!caps.OpenGL33) {
                capabilityReason = "OpenGL 3.3 not supported (got " + glVersion + ")";
                return false;
            }
            capabilityReason = "ok (GL " + glVersion + ")";
            return true;
        } catch (Throwable t) {
            capabilityReason = "GL capabilities not available: " + t.getMessage();
            return false;
        }
    }

    public static String getCapabilityReason() {
        return capabilityReason;
    }

    /** Whether the active context is OpenGL ES (Android); only valid after a capability probe. */
    public static boolean isEsContext() {
        try {
            String glVersion = GL11.glGetString(GL11.GL_VERSION);
            return glVersion != null && glVersion.startsWith("OpenGL ES");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Parse "OpenGL ES 3.0 <renderer>" and require >= 3.0. */
    private static boolean esAtLeast30(String glVersion) {
        String rest = glVersion.substring("OpenGL ES".length()).trim();
        int space = rest.indexOf(' ');
        String ver = space > 0 ? rest.substring(0, space) : rest;
        String[] parts = ver.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 3 || (major == 3 && minor >= 0);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static synchronized boolean ensureCompiled() {
        if (program != 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        RenderSystem.assertOnRenderThreadOrInit();

        try {
            boolean es = isEsContext();
            int vs = GpuShaderUtil.compileShaderFromResource(GL20.GL_VERTEX_SHADER, es ? VSH_ES_PATH : VSH_PATH);
            int fs = GpuShaderUtil.compileShaderFromResource(GL20.GL_FRAGMENT_SHADER, es ? FSH_ES_PATH : FSH_PATH);
            int prog = GpuShaderUtil.linkProgramWith(p -> {
                GL20.glBindAttribLocation(p, 0, "a_position");
                GL20.glBindAttribLocation(p, 1, "a_uv");
                GL20.glBindAttribLocation(p, 2, "a_normal");
            }, vs, fs);

            locProj = GL20.glGetUniformLocation(prog, "u_proj");
            locMv = GL20.glGetUniformLocation(prog, "u_mv");
            locIvr = GL20.glGetUniformLocation(prog, "u_ivr");
            locColor = GL20.glGetUniformLocation(prog, "u_color");
            locOverlay = GL20.glGetUniformLocation(prog, "u_packedOverlay");
            locFogStart = GL20.glGetUniformLocation(prog, "u_fogStart");
            locFogEnd = GL20.glGetUniformLocation(prog, "u_fogEnd");
            locFogColor = GL20.glGetUniformLocation(prog, "u_fogColor");
            locFogShape = GL20.glGetUniformLocation(prog, "u_fogShape");
            locLight0 = GL20.glGetUniformLocation(prog, "u_light0");
            locLight1 = GL20.glGetUniformLocation(prog, "u_light1");
            locAlphaMode = GL20.glGetUniformLocation(prog, "u_alphaMode");
            locPackedLight = GL20.glGetUniformLocation(prog, "u_packedLight");

            int locSampler0 = GL20.glGetUniformLocation(prog, "Sampler0");
            int locSampler1 = GL20.glGetUniformLocation(prog, "Sampler1");
            int locSampler2 = GL20.glGetUniformLocation(prog, "Sampler2");
            GL20.glUseProgram(prog);
            if (locSampler0 >= 0) {
                GL20.glUniform1i(locSampler0, 0);
            }
            if (locSampler1 >= 0) {
                GL20.glUniform1i(locSampler1, 1);
            }
            if (locSampler2 >= 0) {
                GL20.glUniform1i(locSampler2, 2);
            }
            GL20.glUseProgram(0);

            program = prog;
            return true;
        } catch (Throwable t) {
            YSMEpicFightCompat.LOGGER.error(
                    "YSM-EF Compat: failed to compile the CPU skinning shader, keeping Epic Fight's CPU skinning path", t);
            failed = true;
            return false;
        }
    }

    public static int program() {
        return program;
    }

    public static int locProj() {
        return locProj;
    }

    public static int locMv() {
        return locMv;
    }

    public static int locIvr() {
        return locIvr;
    }

    public static int locColor() {
        return locColor;
    }

    public static int locOverlay() {
        return locOverlay;
    }

    public static int locFogStart() {
        return locFogStart;
    }

    public static int locFogEnd() {
        return locFogEnd;
    }

    public static int locFogColor() {
        return locFogColor;
    }

    public static int locFogShape() {
        return locFogShape;
    }

    public static int locLight0() {
        return locLight0;
    }

    public static int locLight1() {
        return locLight1;
    }

    public static int locAlphaMode() {
        return locAlphaMode;
    }

    public static int locPackedLight() {
        return locPackedLight;
    }
}
