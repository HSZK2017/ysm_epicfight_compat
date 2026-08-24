package com.ysmef.compat.gpu;

import com.ysmef.compat.YSMEpicFightCompat;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;

/**
 * The bone-skinning GLSL program used by the GPU render path (ported from
 * ModernYSM's BoneSkinShader). Draws skinned YSM meshes with the model texture,
 * the overlay texture and the lightmap bound to samplers 0/1/2, replicating
 * Minecraft's entity shader semantics (lighting, fog, overlay, cutout alpha).
 */
public final class YsmBoneSkinShader {

    /** GL shader storage buffer binding point for the BoneBlock. */
    public static final int SSBO = 0;

    private static final String VSH_PATH = "/ysm_epicfight_compat/shaders/bone_skin.vsh";
    private static final String FSH_PATH = "/ysm_epicfight_compat/shaders/bone_skin.fsh";
    private static final String VSH_ES_PATH = "/ysm_epicfight_compat/shaders/bone_skin_es.vsh";
    private static final String FSH_ES_PATH = "/ysm_epicfight_compat/shaders/bone_skin_es.fsh";

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
    private static int locPartOffset = -1;
    private static int locPackedLight = -1;
    private static boolean failed = false;

    private YsmBoneSkinShader() {}

    public static synchronized boolean ensureCompiled() {
        if (program != 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        RenderSystem.assertOnRenderThreadOrInit();

        try {
            boolean es = YsmGpuCapability.isGles();
            int vs = GpuShaderUtil.compileShaderFromResource(GL20.GL_VERTEX_SHADER, es ? VSH_ES_PATH : VSH_PATH);
            int fs = GpuShaderUtil.compileShaderFromResource(GL20.GL_FRAGMENT_SHADER, es ? FSH_ES_PATH : FSH_PATH);
            int prog = GpuShaderUtil.linkProgramWith(p -> {
                GL20.glBindAttribLocation(p, 0, "a_position");
                GL20.glBindAttribLocation(p, 1, "a_uv");
                GL20.glBindAttribLocation(p, 2, "a_normal");
                GL20.glBindAttribLocation(p, 3, "a_boneId");
                GL20.glBindAttribLocation(p, 4, "a_partId");
                GL20.glBindAttribLocation(p, 5, "a_cullable");
            }, vs, fs);

            // Both shader variants declare the BoneBlock with an explicit
            // layout(std430, binding = 0), so this explicit re-binding is only
            // belt-and-suspenders for desktop GL. It must NOT run on OpenGL ES:
            // GL43.glGetProgramResourceIndex is a desktop-only entry point whose
            // function pointers are absent on GLES contexts (Android launchers),
            // which previously made every ES compile fail and silently fell back.
            if (!es) {
                int ssboBlock = GL43.glGetProgramResourceIndex(prog, GL43.GL_SHADER_STORAGE_BLOCK, "BoneBlock");
                if (ssboBlock != GL43.GL_INVALID_INDEX) {
                    GL43.glShaderStorageBlockBinding(prog, ssboBlock, SSBO);
                }
            }

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
            locPartOffset = GL20.glGetUniformLocation(prog, "u_partOffset");
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
            YSMEpicFightCompat.LOGGER.error("YSM-EF Compat: failed to compile the GPU skinning shader, falling back to the Epic Fight compute path", t);
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

    public static int locPartOffset() {
        return locPartOffset;
    }

    public static int locPackedLight() {
        return locPackedLight;
    }
}
