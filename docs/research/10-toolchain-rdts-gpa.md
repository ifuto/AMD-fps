# Toolchain: RDTS, GPUPerfAPI, Compressonator, shader compilers

## AMD Radeon Developer Tool Suite (RDTS) — gpuopen.com/tools

| Tool | What it does | How AMD-Faster uses it |
|---|---|---|
| **Radeon GPU Profiler (RGP)** | Frame capture + timeline for **DX12, Vulkan, OpenCL, HIP**. *Not OpenGL.* | Primary profiler for the Vulkan backend. Capture a few seconds of the overlay, look at wave occupancy, L2 hit rate, memory bandwidth. |
| **Radeon GPU Analyzer (RGA)** | Static shader analysis: ISA, VGPR count, occupancy, LDS usage. Takes **GLSL / SPIR-V / HLSL**. | Offline check of `terrain.vert` / `terrain.frag` / `lines.*`. Run on the committed SPIR-V in `src/main/resources/assets/amdfaster/spirv/`. |
| **Radeon Memory Visualizer (RMV)** | VRAM allocation visualiser. | Validate that our `RegionArena` allocations land in the heap we expect (ReBAR `DEVICE_LOCAL\|HOST_VISIBLE` vs `DEVICE_LOCAL`). |
| **Radeon GPU Detective** | Post-mortem crash / device-lost analysis. | When a user reports a `VK_ERROR_DEVICE_LOST`, ask for an RGD capture. |

The RGP "no OpenGL" limitation is one of the reasons we went Vulkan — see
`06-opengl-amd-quirks.md`.

## GPUPerfAPI (GPA)

Hardware counter library. **Supports OpenGL too**, so it is the fallback if
we ever need to profile the GL side (e.g. comparing against a Sodium
baseline). For Vulkan we prefer RGP.

## Compressonator

BCn texture compression. Relevant when we add our own atlas management
(v0.2+): compress the block atlas to BC7 on RDNA3/4 (hardware-decoded,
4 bpp vs 32 bpp RGBA). Not used in v0.1 (we sample the vanilla atlas
format).

## Shader compilation

We ship **precompiled SPIR-V** so users never need a shader toolchain.

```
GLSL source  ──glslangValidator──▶  SPIR-V (.spv)  ──▶  jar resource
src/main/resources/.../shaders/*.vert
src/main/resources/.../shaders/*.frag
src/main/resources/.../spirv/*.spv   (committed)
```

Build script: `tools/compile_shaders.sh` (requires `glslangValidator` on
PATH; CI installs it from the distro package).

- Target: `--target-env vulkan1.2` (SPIR-V 1.5) — supported by every driver
  we care about (RADV since Mesa 20, AMDVLK since 2022, Adrenalin since 22.x).
- The GLSL sources are the human-readable truth and the RGA input.
- At runtime `ShaderModule` loads the `.spv` from the classpath into a
  direct `ByteBuffer` and calls `vkCreateShaderModule`.

Alternatives considered:

- **shaderc** (C++ lib + Java bindings) — heavier, native dependency, no
  benefit for 4 tiny shaders.
- **SPIRV-Cross** — for transpiling to GLSL/HLSL/MSL. Not needed (Vulkan
  only).
- **glslang via JNI** — would require a native lib; precompiled SPIR-V is
  simpler and smaller.

## CI

`.github/workflows/build.yml` runs `./gradlew build`. A separate
`compile-shaders` step regenerates SPIR-V and fails the build if the
committed `.spv` files are stale (keeps sources and binaries in sync).
