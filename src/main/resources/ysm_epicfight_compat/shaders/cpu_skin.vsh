#version 330 core

// CPU skinning vertex shader for converted YSM-EF meshes.
//
// This is the CPU render path's counterpart of bone_skin.vsh: the skinning
// (joint pose x toOrigin x per-part YSM bind-space delta) is computed on the
// CPU every frame, the poseStack is applied on the CPU too, and the resulting
// CAMERA-SPACE positions/normals arrive as plain vertex attributes - no SSBO,
// no compute shader, so the path also runs on GPUs below OpenGL 4.3 (the
// minimum is desktop OpenGL 3.3 / OpenGL ES 3.0).
//
// The uniforms are exactly the ones the vanilla entity shader receives:
//   u_proj = RenderSystem projection matrix,
//   u_mv   = RenderSystem model-view (camera) matrix,
//   u_ivr  = inverse view rotation,
// and the projection/fog math below mirrors the vanilla entity shader.
//
// Hidden parts are skipped on the CPU (their vertices are not written), which
// mirrors BoneData.isHidden in bone_skin.vsh.

layout(location = 0) in vec3 a_position;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in vec4 a_normal;

uniform mat4 u_proj;
uniform mat4 u_mv;
uniform mat3 u_ivr;
uniform vec4 u_color;
uniform int  u_fogShape;
uniform vec3 u_light0;
uniform vec3 u_light1;
uniform int  u_packedLight;

out vec2  v_uv;
out vec3  v_normal;
out vec4  v_color;
out float v_vertexDistance;
flat out int v_packedLight;
out float v_cullable;

// Mirrors Minecraft's fog.glsl exactly: the distance must be computed on the
// view-transformed position (u_mv).
float fogDistance(mat4 modelViewMat, vec3 pos, int shape) {
    if (shape == 0) {
        return length((modelViewMat * vec4(pos, 1.0)).xyz);
    } else {
        float lenXZ = length((modelViewMat * vec4(pos.x, 0.0, pos.z, 1.0)).xyz);
        float lenY = length((modelViewMat * vec4(0.0, pos.y, 0.0, 1.0)).xyz);
        return max(lenXZ, lenY);
    }
}

vec4 minecraft_mix_light(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color) {
    lightDir0 = normalize(lightDir0);
    lightDir1 = normalize(lightDir1);
    float l0 = max(0.0, dot(lightDir0, normal));
    float l1 = max(0.0, dot(lightDir1, normal));
    float lightAccum = min(1.0, (l0 + l1) * 0.6 + 0.4);
    return vec4(color.rgb * lightAccum, color.a);
}

void main() {
    vec4 eyePos = vec4(a_position, 1.0);
    gl_Position = u_proj * (u_mv * eyePos);

    vec3 nrm = normalize(a_normal.xyz);

    v_uv = a_uv;
    v_normal = nrm;
    v_color = minecraft_mix_light(u_light0, u_light1, nrm, u_color);
    // Same fog input as Minecraft's entity shader (IViewRotMat * Position with
    // the model-view matrix); see fogDistance above.
    v_vertexDistance = fogDistance(u_mv, u_ivr * eyePos.xyz, u_fogShape);
    v_packedLight = u_packedLight;
    // Epic Fight renders both faces: never discard on back-facing.
    v_cullable = 0.0;
}
