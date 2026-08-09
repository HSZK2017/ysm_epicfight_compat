#version 310 es

// GPU skinning vertex shader for converted YSM-EF meshes - GLES 3.1 variant
// for Android launchers (Fold Craft Launcher / Zalith). Same math as the
// desktop bone_skin.vsh (#version 430): the BoneBlock SSBO holds the joint
// matrices (indices 0..jointCount-1) followed by the per-part YSM bind-space
// deltas; every vertex carries its joint id (a_boneId) and part id (a_partId),
// and the skinning product (joint x delta) is computed here on the GPU.

precision highp float;
precision highp int;

layout(location = 0) in vec3 a_position;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in vec4 a_normal;
layout(location = 3) in uint a_boneId;
layout(location = 4) in uint a_partId;
layout(location = 5) in float a_cullable;

out float v_cullable;

struct BoneData {
    mat4 transform;
    mat4 normal;
    int  packedLight;
    int  isHidden;
    int  pad0;
    int  pad1;
};

layout(std430, binding = 0) readonly buffer BoneBlock {
    BoneData bones[];
};

uniform mat4 u_proj;
uniform vec4 u_color;
uniform int  u_fogShape;
uniform vec3 u_light0;
uniform vec3 u_light1;
uniform uint u_partOffset;
uniform int  u_packedLight;

out vec2  v_uv;
out vec3  v_normal;
out vec4  v_color;
out float v_vertexDistance;
flat out int v_packedLight;

float fogDistance(vec3 pos, int shape) {
    if (shape == 0) {
        return length(pos);
    } else {
        return max(length(pos.xz), abs(pos.y));
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
    BoneData joint = bones[a_boneId];
    BoneData part = bones[u_partOffset + a_partId];
    if (part.isHidden != 0) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        v_uv = vec2(0.0);
        v_normal = vec3(0.0, 0.0, 1.0);
        v_color = vec4(0.0);
        v_vertexDistance = 0.0;
        v_packedLight = 0;
        return;
    }
    mat4 boneMat = joint.transform * part.transform;
    vec4 eyePos = boneMat * vec4(a_position, 1.0);
    gl_Position = u_proj * eyePos;

    vec3 nrm = normalize((boneMat * vec4(a_normal.xyz, 0.0)).xyz);

    v_uv = a_uv;
    v_normal = nrm;
    v_color = minecraft_mix_light(u_light0, u_light1, nrm, u_color);
    v_vertexDistance = fogDistance(eyePos.xyz, u_fogShape);
    v_packedLight = u_packedLight;
    v_cullable = a_cullable;
}
