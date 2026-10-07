#version 450
// AMD-Faster terrain vertex shader.
//
// Vertex data is region-relative (float block units, region <= 128 blocks wide),
// the region origin arrives through push constants. Keeping coordinates relative
// to a nearby origin is what keeps float32 precision exact inside a region and is
// the same trick Sodium/Nvidium use (see docs/research/07-sodium-architecture.md).
//
// UBO layout must match dev.ifuto.amdfaster.vk.FrameUbo (std140, 192 bytes).

layout(set = 0, binding = 0) uniform FrameData {
    mat4 uProj;        // 0
    mat4 uView;        // 64
    vec4 uCamFog;      // 128: xyz camera world pos, w fog start
    vec4 uFogSky;      // 144: x fog far, y sky brightness (0..1), z emissive boost, w unused
    vec4 uFogColor;    // 160
} frame;

layout(push_constant) uniform PushData {
    vec4 uRegionOrigin; // xyz: region origin in world blocks, w: flags (bit0 = ignore fog)
} push;

layout(location = 0) in vec3 aPosition; // region-relative block coords
layout(location = 1) in vec2 aTexCoord; // atlas uv
layout(location = 2) in vec4 aColor;    // tint * AO * face shade (UNORM8)
layout(location = 3) in vec2 aLight;    // x = sky (0..1), y = block (0..1)

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec2 vLight;
layout(location = 3) out float vDepth;

void main() {
    vec3 world = aPosition + push.uRegionOrigin.xyz;
    vec4 viewPos = frame.uView * vec4(world, 1.0);
    gl_Position = frame.uProj * viewPos;

    vTexCoord = aTexCoord;
    vColor = aColor;
    vLight = aLight;
    vDepth = -viewPos.z;
}
