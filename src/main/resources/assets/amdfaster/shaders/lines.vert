#version 450
// Debug line shader: region wireframes, frustum edges and the frame-time graph
// of the diagnostics overlay. Same FrameData UBO as the terrain pipeline so a
// single descriptor set layout serves both.

layout(set = 0, binding = 0) uniform FrameData {
    mat4 uProj;
    mat4 uView;
    vec4 uCamFog;
    vec4 uFogSky;
    vec4 uFogColor;
} frame;

layout(push_constant) uniform PushData {
    vec4 uRegionOrigin;
} push;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aColor;

layout(location = 0) out vec4 vColor;

void main() {
    vec3 world = aPosition + push.uRegionOrigin.xyz;
    gl_Position = frame.uProj * frame.uView * vec4(world, 1.0);
    vColor = aColor;
}
