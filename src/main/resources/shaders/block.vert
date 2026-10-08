// Block geometry vertex shader.
//
// Reads the three vertex streams Meshlet writes: positions, texture coordinates and light. Light is
// unpacked here rather than in the fragment shader, because one vertex is shaded once while its
// fragments may number in the hundreds -- the interpolator carries a small float instead of a word
// and an unpack repeated per pixel.
//
// The layout numbers match net.amdfaster.gpu.BlockBindings; BlockBindingsTest reads this file and
// asserts they agree, because a binding that silently does not match reads another buffer's memory
// and only on some drivers.

#version 450

// set 1: set 0 belongs to the culling pipeline and is declared compute-only.
layout(std140, set = 1, binding = 0) uniform Frame {
    mat4  viewProjection;
    vec4  frustumPlanes[6];
    vec4  cameraOrigin;       // camera in blocks; everything is kept relative to it for float range
    uint  meshletCount;
    uint  hizWidth;
    uint  hizHeight;
    uint  _pad0;
};

layout(push_constant) uniform Push {
    vec4 sectionOrigin;       // xyz = this section's origin in blocks
};

// R16G16B16A16_SINT: x,y,z in blocks within the section, then a flags word currently unused.
layout(location = 0) in ivec4 inPosition;
// R32G32_SFLOAT: atlas coordinates, already scaled by the face's tile count.
layout(location = 1) in vec2  inUV;
// R32_UINT: block in bits 4..7, sky in bits 20..23, ambient occlusion in bits 24..25.
layout(location = 2) in uint  inLight;

layout(location = 0) out vec2  outUV;
layout(location = 1) out float outShade;
layout(location = 2) out vec2  outLightMap;

// Mirrors net.amdfaster.light.VertexLight.SHADE. Both tables describe the same four values and a
// test holds them together, because a shader and a Java constant drifting apart is invisible until
// somebody notices the lighting looks slightly off and has no idea which one moved.
const float SHADE[4] = float[4](0.2, 0.6, 0.8, 1.0);

// The lightmap is 16x16, one channel per axis. Sampling at the texel centre is what keeps a corner
// at level 0 from bleeding in its neighbour's brightness, which at the dark end is most of it.
const float LIGHTMAP_TEXELS = 16.0;

void main() {
    vec3 relative = vec3(inPosition.xyz) + sectionOrigin.xyz - cameraOrigin.xyz;
    gl_Position = viewProjection * vec4(relative, 1.0);

    outUV = inUV;

    uint block = (inLight >>  4u) & 0xFu;
    uint sky   = (inLight >> 20u) & 0xFu;
    uint ao    = (inLight >> 24u) & 0x3u;

    outLightMap = (vec2(float(block), float(sky)) + 0.5) / LIGHTMAP_TEXELS;
    outShade = SHADE[ao];
}
