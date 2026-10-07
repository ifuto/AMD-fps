# The Vulkan path

The Vulkan path is where AMD-Faster stops working *through* the driver's OpenGL implementation and
starts telling the GPU what to do itself. It is also the only way to reach features that simply do
not exist in OpenGL: mesh shaders, descriptor indexing, dynamic VGPR control, and a draw count that
the GPU writes without a CPU round trip.

## 1. Shape of the path

```
    Minecraft (Blaze3D, OpenGL)                AMD-Faster (Vulkan)
    ─────────────────────────────              ───────────────────────────
    entities, particles, sky, GUI   ← shares framebuffer →   terrain only
                                                              │
                                    ┌─────────────────────────┴──────────────────────┐
                                    │ region graph (8x4x8 sections per region)       │
                                    │ section metadata: 16 bytes per section         │
                                    │ visibility: BFS over regions + depth pyramid   │
                                    │ payload: compact 16-byte vertices per section  │
                                    │ submission: indirect draw batches, GPU count   │
                                    └────────────────────────────────────────────────┘
```

Two variants exist in the plan:

* `VULKAN_INDIRECT` - classic pipeline: vertex pulling from the section buffers, indirect draws.
  Works on every Vulkan 1.2+ AMD driver from GCN on.
* `VULKAN_MESH` - `VK_EXT_mesh_shader` (RDNA 2 and newer): a task shader reads section metadata and
  emits mesh workgroups; the mesh shader builds the actual triangles. Nothing about this is
  expressible in OpenGL.

## 2. Required extensions and why

| Extension | Need |
|---|---|
| `VK_KHR_timeline_semaphore` | frame pacing without stalling on the upload ring |
| `VK_KHR_synchronization2` | cheaper barrier batching per pass |
| `VK_KHR_dynamic_rendering` (core 1.3) | terrain passes without a framebuffer object per target |
| `VK_EXT_descriptor_indexing` | bindless texture table (this is the thing Windows OpenGL refuses to give us) |
| `VK_KHR_draw_indirect_count` (core 1.2) | GPU-written draw count - the point of the whole design |
| `VK_EXT_subgroup_size_control` | wave32 for divergent passes, wave64 for bulk passes, per pipeline |
| `VK_EXT_mesh_shader` | the task/mesh variant only |
| `VK_EXT_memory_budget` | sizing the heaps against what the driver will actually give |
| `VK_EXT_calibrated_timestamps` | honest frame timing in the debug overlay |

The probe (`client/VkDeviceProbe.java`) enumerates these without creating a device, so the mod can
decide which backend to use before anything expensive happens.

## 3. Memory: the AMD-specific part

Vulkan Memory Allocator is used deliberately, and not only because it is convenient: VMA came out of
AMD and its allocation model matches AMD's memory architecture.

* Discrete cards: `DEVICE_LOCAL` for section buffers, `HOST_VISIBLE | DEVICE_LOCAL` for the upload
  ring **only when the BAR is large** (Resizable BAR / Smart Access Memory). With the default 256 MiB
  BAR, writing to host-visible device memory goes over PCIe and is slower than staging through a
  small ring.
* Integrated parts (Vega 8, 660M/680M, 740M/760M/780M, 890M): the distinction collapses - the memory
  is the system's memory. The only lever that matters is bandwidth, so the plan shrinks the ring
  (system RAM / 128, clamped to 32-128 MiB), merges uploads more aggressively, and prefers the
  compact vertex format even at the cost of extra ALU.
* The probe reads `VkPhysicalDeviceMemoryProperties` and reports the size of the
  `DEVICE_LOCAL | HOST_VISIBLE` heaps, which is exactly the usable BAR window. That number decides
  whether `HOST_VISIBLE_HEAP` is worth enabling at all.

## 4. Shaders

* GLSL 4.60 source is compiled to SPIR-V by **shaderc** (optionally bundled, `includeShaderCompiler`)
  or, when it is not bundled, by precompiled SPIR-V shipped in the jar (`resources/amdfaster/shaders`).
  Shipping precompiled SPIR-V is what keeps the mod usable on machines where the shader compiler is
  unavailable - and it is also what makes the first frame fast, because AMD's driver still compiles
  SPIR-V to ISA at pipeline creation.
* **SPIRV-Cross** (`lwjgl-spvc`) is bundled behind the same flag: it lets the mod print the GLSL that
  corresponds to a generated variant, which is the fastest way to debug a bad variant.
* The optional AMD toolchain (`./gradlew fetchAmdToolchain`, `includeAmdToolchain=true`) downloads
  **amdllpc** (AMD's LLVM-based SPIR-V compiler, from the `llvm-project` release with the `+amdgpu`
  suffix) and uses it to compile the same SPIR-V **offline**, so `Radeon GPU Analyzer` can be pointed
  at the mod's own shaders for VGPR/SGPR/occupancy inspection. This is the "offline pipeline
  compilation" technique in the plan: it does not change what is executed, it removes first-run
  stutter and it makes the mod's shaders verifiable.

## 5. Driver stacks this path runs on

| Stack | Compiler | Notes |
|---|---|---|
| AMD Windows Vulkan (PAL + LLPC) | LLPC -> LLVM AMDGPU backend | default on Windows; mesh shaders from RDNA 2 |
| AMDVLK (Linux/Windows) | LLPC | AMD's open source Vulkan driver |
| Mesa RADV | ACO | shader cache keys include the pipeline state; `RADV_PERFTEST`/`RADV_DEBUG` are useful when validating |

The mod does not depend on any of them at runtime: everything it needs is behind a Vulkan extension,
and every extension it uses is probed rather than assumed. What the driver *does* affect is
`DriverKind`, which feeds the tuner's decisions about upload sizes and about which paths to trust.

## 6. Debug tooling used while developing this path

* **Radeon GPU Analyzer (RGA)** - GLSL/SPIR-V -> ISA, VGPR/SGPR counts and occupancy. This is the
  ground truth for every occupancy number in `01-amd-hardware-notes.md`.
* **Radeon GPU Detective (RGD)** - crash/hang analysis for Vulkan.
* **Radeon GPU Profiler (RGP)** - wave occupancy and barrier timelines. RGP has no OpenGL support,
  which is one of the reasons this mod ships a Vulkan path at all: without Vulkan there is no honest
  way to see what the GPU is doing on an AMD card.
* **Radeon Memory Visualizer (RMV)** - VRAM allocation map; used to confirm that the heaps the tuner
  asks for are the heaps the driver actually hands out.
* **AMD GPUPerfAPI** - counter collection, including OpenGL, for the cases where the GL path is the
  one being measured.
