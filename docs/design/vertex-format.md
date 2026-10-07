# The AMD-Faster vertex format

28 bytes per vertex, region-relative positions. Matches
`TerrainPipeline`'s vertex input and `terrain.vert`.

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | `float` | `x` | position, region-relative (0..regionSize) |
| 4 | `float` | `y` | |
| 8 | `float` | `z` | |
| 12 | `float` | `u` | atlas texcoord |
| 16 | `float` | `v` | |
| 20 | `u8` | `r` | tint × AO × face shade, red |
| 21 | `u8` | `g` | |
| 22 | `u8` | `b` | |
| 23 | `u8` | `a` | 255 unless translucent layer |
| 24 | `u16` | `sky` | lightmap sky (0..15 → 0..1 in shader) |
| 26 | `u16` | `block` | lightmap block (0..15 → 0..1 in shader) |

## Why 28 bytes

- **Small enough for L2.** A 16³ section of solid blocks is at most
  16³ × 6 faces × 4 verts = 24576 verts ≈ 672 KiB. That fits comfortably in
  RDNA's 2 MiB L2 per shader engine, so vertex fetches stay local.
- **No padding.** 28 is not 4-aligned-friendly, but Vulkan does not require
  attribute alignment — the binding stride just has to match.
- **One attribute for color.** Packing tint × AO × face shade into a single
  `R8G8B8A8_UNORM` attribute removes a vertex attribute (and a fetch) vs
  separate floats.

## Why region-relative positions

Float32 has ~7 decimal digits. At world coordinate 100 000 (plausible in a
long Minecraft world), `100000 + 0.001` rounds badly — you get z-fighting
and jitter. Sodium and Nvidium both keep vertex positions **relative to a
nearby origin** (the region origin) and add the origin in the shader via a
push constant. We do the same: the vertex stores `blockPos - regionOrigin`
(exact, since both are integers), and the push constant carries the origin.

## Light packing

Vanilla packs lightmap coordinates as `(sky << 4 | block) << 4` in a single
int (`LightmapTextureManager.pack`). We store sky and block separately as
`u16` so the shader can read them with one attribute fetch and decode with
two shifts — no 16×16 lightmap texture fetch needed.

In v0.1 the fragment shader approximates the lightmap response analytically
(`lightCurve` in `terrain.frag`). A future version will bind the actual
lightmap texture for exact parity.

## Line vertex (overlay)

16 bytes: `vec3 position` + `vec4 color (UNORM8)`. World-space (the line
pipeline's push constant origin is 0).
