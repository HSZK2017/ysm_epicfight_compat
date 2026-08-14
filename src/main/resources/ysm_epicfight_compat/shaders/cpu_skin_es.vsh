#version 300 es

// CPU skinning vertex shader for converted YSM-EF meshes - GLES 3.0 variant
// for Android launchers (Fold Craft Launcher / Zalith) whose GLES is below
// 3.1 (no compute shaders / SSBO). Same math as the desktop cpu_skin.vsh:
// the skinning and the poseStack run on the CPU, camera-space positions/
// normals arrive as attributes, and the uniforms are the plain RenderSystem
// proj / model-view / inverse-view-rotation the vanilla entity shader gets.

precision highp float;
precision highp int;

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
    v_vertexDistance = fogDistance(u_mv, u_ivr * eyePos.xyz, u_fogShape);
    v_packedLight = u_packedLight;
    v_cullable = 0.0;
}
