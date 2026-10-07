# AMD-Faster

An **AMD-first, Vulkan-based rendering backend** for Minecraft — the AMD analogue of Nvidium.

> **Status: pre-alpha.** Right now the mod contains the measurement layer only: it probes the
> Vulkan adapters on your machine at startup, classifies the AMD part, and reports what the
> hardware actually offers. Nothing about how Minecraft draws is changed yet.

Client-side · Fabric · Minecraft **1.21.11** · Java 21 · LGPL-3.0-only

---

## Why another renderer

Sodium and Embeddium optimise *within* OpenGL. Nvidium replaces the backend outright, but only
for NVIDIA — it leans on mesh shaders and `VK_NV_mesh_shader`, which AMD parts do not have.
VulkanMod proves an independent Vulkan backend for Minecraft is practical, but it is written for
every vendor at once and so cannot specialise.

AMD-Faster is written for **one vendor on purpose**. That buys the freedom to schedule everything
against a known execution model instead of the least common denominator:

| What gets tuned | Where the number comes from |
| --- | --- |
| Subgroup / wavefront size (32 vs 64) | `VkPhysicalDeviceSubgroupProperties`, cross-checked against the RDNA generation |
| Work-group granularity | 4 wavefronts ⇒ **128 threads on RDNA, 256 on GCN** |
| LDS budget per compute stage | `maxComputeSharedMemorySize`, cross-checked against 128 KB (RDNA 1/2) vs 64 KB (RDNA 3+) |
| Whether chunk geometry can be written straight into VRAM | a large `DEVICE_LOCAL \| HOST_VISIBLE` heap — Resizable BAR on a discrete card, unified memory on an APU |
| 8-bit indices, mesh shaders, descriptor buffers, `VK_EXT_multi_draw` | the device extension list, reported per adapter |

APUs are first-class targets. A Ryzen 7840HS's Radeon 780M has no VRAM of its own at all — it is
one big heap the CPU can already write to, which makes the persistent-mapped-buffer path not a
nice-to-have but the only sensible one.

## What is in the mod today

**Measurement** (`net.amdfaster.platform`)

* `GpuReport` — creates a throw-away `VkInstance`, enumerates every adapter, reads properties,
  limits, memory heaps/types, driver identity and the extension list, then destroys the instance.
* `AmdArchitecture` / `GpuIdentity` — maps what the driver says (`AMD Radeon RX 7900 XTX (RADV
  NAVI31)`, `AMD Radeon(TM) RX 6800 XT`, `gfx1103`, …) onto a generation, and records *which*
  token matched so a wrong guess is always explainable. An unrecognised part is a supported
  outcome: the driver-reported numbers still drive everything. Also carries the tuning constants
  quoted from AMD's RDNA Performance Guide: 64-thread work-groups, 32 LDS banks, a 13-DWORD
  root-signature budget, 10 draws minimum per command buffer, D32 + reversed-Z.
* `/amdfaster` — `summary`, `report`, `extensions`, `json`.
* `config/amdfaster/gpu-report.json` — the same data as a file, written at startup.

**Section meshing** (`net.amdfaster.mesh`)

* `Orientation` — six axis-aligned buckets. `visibleMask()` decides which of them a camera can
  possibly see, so a section the camera is not inside draws 3 of 6: roughly half the triangles
  never reach the vertex stage, with no second pass and no per-triangle test.
* `Meshlet` / `MeshletBuilder` — clusters of 62 quads (124 triangles) with 8-bit local indices and
  a 4-byte packed AABB, the unit the GPU will cull. Index data is a triangle list rather than a
  strip, because closing strips needs a primitive restart index and AMD's guide says to avoid
  those.
* `SectionMesh` / `SectionMeshBuilder` — one 16³ section, split into the six buckets while it is
  meshed. Splitting an already-built mesh would multiply driver overhead six-fold.

**Greedy meshing** (`net.amdfaster.mesh.voxel`)

* `VoxelView` — the block data the mesher reads, with no Minecraft type in it, so the algorithm
  runs on synthetic volumes in a unit test. The merge key has to carry the sprite, the tint *and*
  the light: leaving light out is the classic greedy-meshing bug, where one block's lightmap value
  gets smeared across a whole merged quad.
* `GreedyMesher` / `MergedFace` — coplanar runs of identically-keyed faces merged into rectangles.
  This is what actually reduces the vertex count, because corner deduplication cannot (see above).
  A solid 16³ section goes from 1536 visible unit faces to 6 quads.
* `SectionMesher` — one merged rectangle becomes one quad whose texture tiles across it, so a 4x4
  merged face repeats its sprite rather than stretching it.

Nothing is drawn by this mod yet; Minecraft still renders through its own pipeline.

## Roadmap

1. **Measurement** — done.
2. **Section meshing** — done, CPU side.
3. **Greedy mesher** — done, against a Minecraft-free `VoxelView` abstraction.
4. **Minecraft adapter** — `ChunkSection` and `BlockRenderManager` behind `VoxelView`, including
   the one-block border from neighbouring sections that makes section boundaries correct.
5. **Vulkan bring-up** — instance, device, swapchain, render graph; the renderer takes over.
6. **GPU-driven culling** — two-pass occlusion culling against the previous frame's Hi-Z, with the
   visible set living in a GPU buffer so the CPU never rebuilds it. Culling dispatches 16
   sections per wave, not one.
7. **Transparent sorting on the GPU** — radix sort with on-chip local sort. Not bitonic: bitonic
   is O(n log²n), needs a power of two, and scatters to global memory.
8. **APU path** — persistent mapped buffers, no staging copy, and bandwidth-aware LOD.

## Requirements

* Minecraft 1.21.11 with Fabric Loader ≥ 0.18.0
* [Fabric API](https://modrinth.com/mod/fabric-api) (installed automatically by the Modrinth app)
* A Vulkan 1.1 driver. On Windows the AMD proprietary driver (AMDVLK) and on Linux RADV are both
  supported; RADV/ACO is the better-tested of the two.

If the probe fails, AMD-Faster logs why and stays out of the way — it never breaks the vanilla
renderer.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. CI runs the same command on every push and then `verifyJar`
asserts the result: that the tests ran and passed, that the jar holds the entrypoint and the probe
classes, that `fabric.mod.json`'s version matches the project version, and that `lwjgl-vulkan` is
actually jar-in-jar'd (Minecraft ships lwjgl, lwjgl-glfw and lwjgl-opengl but not the Vulkan
bindings).

## Design notes

Everything that informed the design is written up in [`docs/notes/`](docs/notes/00_INDEX.md):
the RDNA optimisation guidance, Mesa/RADV and ACO, a source reading of Sodium, VulkanMod and
Nvidium, the Vulkan/VMA API surface, queue and barrier behaviour, the AMD-Faster design itself,
JVM/LWJGL/Fabric constraints, and the paper and forum survey behind the roadmap above.

## Licence

LGPL-3.0-only. Sodium is PolyForm Shield and its source is **not** a basis for anything here;
VulkanMod, Nvidium and Iris are LGPL-3.0 and were read as prior art.
