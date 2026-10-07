#version 450
// AMD-Faster terrain fragment shader.
//
// v0.1 approximates the vanilla lightmap response analytically instead of
// sampling the 16x16 lightmap texture — one texture fetch less per fragment and
// no dependency on the GL lightmap texture (which we cannot share with Vulkan
// without interop yet). The curve shape mirrors LightmapTextureManager:
//   - block light is a steep gamma curve,
//   - sky light scales with the current sky brightness (day/night),
//   - everything is floored by a minimum ambient term.

layout(set = 0, binding = 0) uniform FrameData {
    mat4 uProj;
    mat4 uView;
    vec4 uCamFog;
    vec4 uFogSky;
    vec4 uFogColor;
} frame;

layout(set = 1, binding = 0) uniform sampler2D uAtlas;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec2 vLight;
layout(location = 3) in float vDepth;

layout(location = 0) out vec4 fragColor;

float lightCurve(float x) {
    // gamma-ish response, close to the vanilla lightmap ramp
    return x * x * (3.0 - 2.0 * x);
}

void main() {
    vec4 texel = texture(uAtlas, vTexCoord);
    if (texel.a < 0.5) {
        discard; // cutout
    }

    float block = lightCurve(vLight.y);
    float sky = lightCurve(vLight.x) * frame.uFogSky.y;
    float light = clamp(max(block, sky) + frame.uFogSky.z, 0.06, 1.0);

    vec3 rgb = texel.rgb * vColor.rgb * light;

    float fogStart = frame.uCamFog.w;
    float fogFar = frame.uFogSky.x;
    float fog = clamp((vDepth - fogStart) / max(fogFar - fogStart, 1e-4), 0.0, 1.0);
    fog *= fog;
    rgb = mix(rgb, frame.uFogColor.rgb, fog * frame.uFogColor.a);

    fragColor = vec4(rgb, texel.a * vColor.a);
}
