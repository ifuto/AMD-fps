// Block geometry fragment shader.
//
// Deliberately has no discard. AMD's guidance is that discard disables early depth and forces the
// GPU to keep fragments it could otherwise have rejected before shading, which on a scene that is
// mostly leaves, grass and fences costs more than it saves. Cutout geometry here runs a Z pre-pass
// with depth test EQUAL instead, so by the time this shader runs every surviving fragment is one
// that will be drawn. See docs/notes/07.
//
// The bindings match net.amdfaster.gpu.BlockBindings; BlockBindingsTest asserts that against this
// source.

#version 450

layout(set = 1, binding = 1) uniform sampler2D blockAtlas;
layout(set = 1, binding = 2) uniform sampler2D lightMap;

layout(location = 0) in vec2  inUV;
layout(location = 1) in float inShade;
layout(location = 2) in vec2  inLightMap;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 texel = texture(blockAtlas, inUV);
    // The lightmap is looked up once per fragment rather than pre-multiplied on the CPU because it
    // changes with time of day, and re-meshing every loaded section to follow the sun is not an
    // option. The lookup is a single texel fetch from a 16x16 texture that stays resident in cache.
    vec3 brightness = texture(lightMap, inLightMap).rgb;

    outColor = vec4(texel.rgb * brightness * inShade, texel.a);
}
