# RDNA / RDNA2 / RDNA3 / RDNA4 ISA — what matters for a Minecraft renderer

Source material studied: AMD's **RDNA / RDNA2 / RDNA3 / RDNA4 ISA Reference
Guides** (GPUOpen), the **RDNA Architecture Whitepaper**, and the
**Machine-Readable ISA** tables on gpuopen.com.

## The one-paragraph summary

RDNA is a SIMD machine organised around **wavefronts of 32 lanes (wave32)**
and **workgroups of up to 1024 threads**. Each Compute Unit has 4 SIMD units,
each SIMD unit has 32 lanes and its own register file slice. The programmer's
mental model is: *occupancy = how many wavefronts are resident per SIMD
unit*, and *the fastest shader is the one that keeps the SIMDs busy without
spilling VGPRs*. For a chunk renderer this translates directly into three
numbers: VGPRs per vertex/fragment shader, LDS bytes per workgroup, and the
number of waves in flight per draw.

## Wave size: wave32 vs wave64

| Generation | Native wave | Notes |
|---|---|---|
| GCN (Polaris/Vega) | wave64 | 64 lanes/wave; RDNA code paths in ACO can still emit wave64 for compute |
| RDNA1/2/3/4 | **wave32** | Graphics always wave32 on RDNA; compute may be wave64 on some parts |

Why we care: **wave32 means a workgroup of 64 threads fills one SIMD unit
with two waves.** Sorting networks, mesh-shader threadgroups and compute-based
culling all want their thread counts to be multiples of 32. Nvidium exploits
this on NVIDIA (warp = 32); the AMD equivalent is the wavefront = 32. Our
compute shaders (future occlusion/sorting passes) are written with
`local_size_x = 64` or `128` so they land on whole waves.

`VkPhysicalDeviceSubgroupProperties.subgroupSize` reports this at runtime —
we read it in `Device` and log it as `wave32`/`wave64` (see
`GpuProbe.vkSubgroupSize`).

## Register budget (VGPRs)

- RDNA SIMD: 4 register-file slices, **256 VGPRs per thread max** (wave32).
- Spilling to LDS/spill stack is the #1 performance killer in fragment shaders.
- Practical target for a terrain fragment shader: **<= 64 VGPRs** so that
  occupancy stays at >= 50% (4+ waves/SIMD).

Our `terrain.frag` is deliberately tiny (one texture fetch, a lightmap
curve, fog) — it sits far below the spill threshold. The vanilla GLSL shaders
with shadow-map PCF, biome blending and 4+ texture stages are exactly the kind
of shader that *does* spill on RDNA; replacing them is most of the win.

## LDS / shared memory

- RDNA: **64 KiB LDS per workgroup** (32 KiB per CU usable by a single
  workgroup on RDNA1, 64 KiB on RDNA2+).
- Nvidium uses compute shaders for translucency sorting with bitonic sort
  networks staged in LDS; the same pattern works on RDNA with wave32-aware
  barriers (`barrier()` + `memoryBarrierShared()`).

## Occupancy targets

| Metric | Target | How we measure |
|---|---|---|
| Waves per SIMD (graphics) | >= 8 | RGA static analysis (offline) |
| VGPRs/thread (terrain VS/FS) | <= 64 | RGA |
| LDS/workgroup | <= 32 KiB | RGA |
| Indirect draws per command buffer | batched by region | our own counter in the overlay |

## Mesh shaders

- `VK_EXT_mesh_shader`: RDNA2+ on RADV (Mesa 23+), RDNA3+ on Windows drivers.
- Mesh shaders let us cull whole sections on-GPU and emit triangles without a
  vertex-shader pass — the single biggest structural win over the classic
  VS/PS pipeline, and exactly what Nvidium does on NVIDIA.
- **v0.1 does not use mesh shaders yet.** The pipeline is deliberately
  classic (VS+FS, dynamic rendering) so the first release is a *correct*
  Vulkan backend; mesh-shader terrain is the v0.2 headline feature and the
  code is structured for it (`RegionArena` already batches per-section draws
  into one indirect command buffer, which is the mesh-shader input shape).

## What we actually tune from the ISA

1. **Wave-aligned compute threadgroups** (future passes).
2. **Small shaders** (no spill) — terrain.frag is ~30 VGPRs.
3. **LDS-budgeted sorting** (future translucency sort).
4. **Region-relative float32 positions** — keeps the VS cheap (no 64-bit
   integer math, no double-precision emulation) which keeps VGPR pressure low.
