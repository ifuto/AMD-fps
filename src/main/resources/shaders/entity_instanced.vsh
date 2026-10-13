#version 430 core

// Instanced entity rendering for AMD-Faster.
//
// One draw call covers every visible entity that shares a mesh. Per-entity state comes from two
// shader storage buffers indexed by gl_InstanceID: the 32 byte transform record that
// net.amdfaster.entity.EntityGpuLayout writes, and the bone palette that
// net.amdfaster.entity.BonePalette evaluates on the CPU.
//
// The important decision is what this shader does NOT do. It does not evaluate the animation. The
// bone matrices arrive already composed, so this is a weighted blend followed by one model-to-world
// transform. Evaluating the rig here instead would recompute the same matrices once per vertex: a
// 120 vertex model with 500 entities on screen is 720,000 evaluations a frame against 6,000, and the
// evaluation is dominated by sin and cos, which run at a quarter of full rate on RDNA and an eighth
// on Xe. Every transcendental avoided here is avoided once per vertex, not once per entity.

layout(location = 0) in vec3 inPosition;   // model space
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec4 inBoneWeights;  // up to four influences
layout(location = 4) in ivec4 inBoneIndices;

layout(binding = 0, std430) readonly buffer InstanceData {
    // x, y, z, yaw, packed light, halfWidth, height, pad -- eight floats, see EntityGpuLayout.
    vec4 records[];
};

layout(binding = 1, std430) readonly buffer BoneData {
    mat4 bones[];
};

uniform mat4 uModelViewProjection;
uniform vec3 uCameraPosition;

smooth out vec2 vUv;
smooth out vec3 vNormal;
flat out int vLight;

// MAX_BONES has to match BonePalette.MAX_BONES or every instance past the first reads another
// entity's skeleton.
const int MAX_BONES = 24;

void main() {
    int instance = gl_InstanceID;

    // Two vec4 per instance: the record is 32 bytes and std430 packs it as two vec4.
    vec4 record0 = records[instance * 2];
    vec4 record1 = records[instance * 2 + 1];
    vec3 worldPosition = record0.xyz;
    float bodyYaw = record0.w;
    vLight = floatBitsToInt(record1.x);

    // Blend the influences in model space, then apply the instance transform once. Blending after
    // the transform would need a matrix multiply per influence instead of per vertex.
    vec4 model = vec4(inPosition, 1.0);
    vec3 normal = inNormal;
    int boneBase = instance * MAX_BONES;

    mat4 skin = bones[boneBase + inBoneIndices.x] * inBoneWeights.x;
    skin += bones[boneBase + inBoneIndices.y] * inBoneWeights.y;
    skin += bones[boneBase + inBoneIndices.z] * inBoneWeights.z;
    skin += bones[boneBase + inBoneIndices.w] * inBoneWeights.w;

    vec4 posed = skin * model;
    vec3 posedNormal = mat3(skin) * normal;

    // Body yaw about Y, then the translation to the entity's position. Written out rather than built
    // as a matrix because this is one cos and one sin per vertex against the sixteen multiplies a
    // mat4 construction would cost, and the transcendental rate is the expensive one on this hardware.
    float c = cos(bodyYaw);
    float s = sin(bodyYaw);
    vec3 rotated = vec3(
        posed.x * c + posed.z * s,
        posed.y,
        -posed.x * s + posed.z * c);

    vec3 world = rotated + worldPosition;
    vUv = inUv;
    vNormal = mat3(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c) * posedNormal;

    gl_Position = uModelViewProjection * vec4(world - uCameraPosition, 1.0);
}
