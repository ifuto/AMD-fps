# AMD-Faster — Research Index

This folder is the written-down part of the research the mod is built on.
Each file is a focused note; together they cover every document the project
was asked to study. Read in order.

## AMD / GPUOpen

- [01-rdna-isa.md](01-rdna-isa.md) — RDNA / RDNA2 / RDNA3 / RDNA4 ISA reference,
  the RDNA architecture whitepaper, wave32 vs wave64, VGPR/LDS budgets,
  occupancy targets, and what they mean for a Minecraft chunk renderer.
- [02-gcn-and-apu.md](02-gcn-and-apu.md) — GCN (Polaris/Vega) coverage and the
  APU line specifically called out in the brief: **Vega 8 (Raven/Picasso),
  680M (Rembrandt), 780M (Phoenix), Steam Deck (VanGogh)**. Why APUs change the
  memory strategy (unified memory, no VRAM carve-out, display-engine contention).
- [03-gpuopen-performance.md](03-gpuopen-performance.md) — GPUOpen Performance
  Guides and the "Breaking Down Barriers" series: the practical checklist
  (occupancy, L2, wave32, async compute, barriers) applied to our renderer.
- [04-vma-memory.md](04-vma-memory.md) — Vulkan Memory Allocator (AMD's own
  library), heap selection, `DEVICE_LOCAL | HOST_VISIBLE`, Resizable BAR / SAM,
  and the persistent-mapped upload strategy AMD-Faster uses.
- [05-drivers-radv-aco-amdvlk.md](05-drivers-radv-aco-amdvlk.md) — RADV, ACO,
  AMDVLK, PAL, LLPC, `AMD_DEBUG` / `RADV_PERFTEST` / `RADV_DEBUG`, drirc/driconf
  app profiles, amdgpu kernel docs, AGS.

## OpenGL side (why we left it)

- [06-opengl-amd-quirks.md](06-opengl-amd-quirks.md) — OpenGL 4.6 / GLSL 4.60,
  `GL_AMD_*` / `GL_ARB_*` extensions, and the specific reasons AMD's Windows GL
  driver loses to a Vulkan path (small `glBufferSubData`, uniform updates,
  shader-switch cost). This is the "why Vulkan" document.

## Existing mods (the textbooks)

- [07-sodium-architecture.md](07-sodium-architecture.md) — Sodium's renderer
  rewrite: region buffers, persistent-mapped `ARB_buffer_storage`,
  `ARB_multi_draw_indirect`, chunk build pipeline, translucency sorting.
- [08-nvidium-and-vulkanmod.md](08-nvidium-and-vulkanmod.md) — Nvidium (the
  NVIDIA-specialised mod we are the AMD answer to) and VulkanMod (the
  full-Vulkan-backend reference implementation).

## JVM / Minecraft side

- [09-jvm-and-minecraft.md](09-jvm-and-minecraft.md) — the JVM bottleneck:
  Mixin into Blaze3D, LWJGL 3 `MemoryUtil`/`MemoryStack`, Project Panama / FFM,
  G1/ZGC allocation pressure, JMH, async-profiler / JFR / spark, and Minecraft's
  own chunk meshing / lighting / culling pipeline.

## Toolchain

- [10-toolchain-rdts-gpa.md](10-toolchain-rdts-gpa.md) — RDTS (RGP, RGA, RMV,
  Radeon GPU Detective), GPUPerfAPI, Compressonator, glslang / shaderc /
  SPIRV-Cross, and the shader build pipeline (`tools/compile_shaders.sh`).

## Design

- [../design/architecture.md](../design/architecture.md) — how the research maps
  onto the code: backend lifecycle, memory policy, region arenas, the overlay.
- [../design/vertex-format.md](../design/vertex-format.md) — the 28-byte vertex.
