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
* `/amdfaster` — `summary`, `report`, `extensions`, `json`, `vk`.
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

**Vulkan backend** (`net.amdfaster.vk`)

* `MemoryTypeSelector`, `QueueSelector`, `DeviceScorer`, `RootSignatureBudget`, `FrameRing` — the
  *decisions* the backend makes, with no LWJGL type in them, so they are unit-tested against four
  real topologies: discrete without Resizable BAR, discrete with it, an APU, and the classic
  256 MiB BAR window. Encoding a rule as a testable function is how a tuning detail survives
  contact with a refactor.
* `VkContext` — instance, physical device, logical device and three queues. It decides nothing; it
  calls. `create()` returns a context that explains its own failure rather than throwing, so a
  driver that cannot initialise never takes Minecraft down.
* `/amdfaster vk` — creates a real `VkDevice` against Minecraft's window, prints which adapter,
  queue families and memory types were chosen, and tears it down again.

**Lighting** (`net.amdfaster.light`)

* `LightValue` — packed lightmap coordinates in Minecraft's own bit layout, so a value computed here
  can be handed to vanilla's lightmap without conversion. The twelve unused bits between the two
  channels are what let the four-sample average be a single integer add.
* `LightCache` — an 18³ snapshot of the light and opacity around a section, read once. Smooth
  lighting wants four samples per face corner, and answering those by walking the level's chunk map
  makes lighting the dominant cost of meshing rather than a rounding error on it.
* `SmoothLight` / `FaceRef` / `VertexLight` — per-corner ambient occlusion and interpolated light.
  Solid neighbours are excluded from the average rather than averaged in, so a glowing block does not
  light the corner beside it. No winding table is involved: a corner's outward direction is derived
  from which end of the face it sits on.

**Entity batching** (`net.amdfaster.entity`)

* `EntityBatcher` — same-model instances grouped into one instanced draw with a merged bounding box,
  so a hundred dropped items cost one draw call and one frustum test instead of a hundred of each.
  Batches are capped on instance count *and* on bounds span, because an unbounded merged box
  spanning a whole farm is never culled.
* `EntityGpuLayout` — the 32-byte instance record, padded from 28 so two records fit a cache line
  instead of most of them straddling one.

**GPU-driven culling** (`net.amdfaster.cull`, `net.amdfaster.gpu`)

* `Frustum` — six planes from a view-projection matrix, using the **reversed-Z [0,1]** convention
  the renderer runs. With a conventional [-1,1] pair in those slots everything near the camera is
  culled, which is the failure mode easiest to ship and hardest to notice.
* `MeshletCuller` — the same test against a meshlet's 4-byte packed bounds, with the section origin
  applied so the frustum stays camera-relative. Back-face rejection is deliberately not here: every
  quad in a meshlet shares its bucket's orientation, so that is decided once per section.
* `HiZ` — picks the pyramid level for an occlusion test (the coarsest whose texels still cover the
  box, which makes it one fetch instead of a gather) and projects a box to pixels. A box with a
  corner behind the camera yields an *unreliable* rectangle that is never allowed to be off-screen.
* `meshlet_cull.comp` / `meshlet_occlusion.comp` — two passes at 64 threads per work group. The
  first compacts survivors with an atomic slot so the draw buffer comes out dense and the CPU never
  reads a count back; the second walks that compacted array and zeroes the instance count of
  anything behind the previous frame's depth pyramid.
* `CullBindings` / `CullShaderTest` — the descriptor layout, with a test that parses the GLSL and
  checks every binding number, the work group size, the push constant width and the std140 offsets
  against the Java. A binding that drifts does not fail to compile; it draws the wrong buffer on
  some drivers.
* `MeshUploadLayout` / `MeshletGpuLayout` / `GpuBuffer` — the upload path and the record layout the
  culling shader reads.

Nothing is drawn by this mod yet; Minecraft still renders through its own pipeline.

## Roadmap

1. **Measurement** — done.
2. **Section meshing** — done, CPU side.
3. **Greedy mesher** — done, against a Minecraft-free `VoxelView` abstraction.
4. **Minecraft adapter** — `ChunkSection` and `BlockRenderManager` behind `VoxelView`, including
   the one-block border from neighbouring sections that makes section boundaries correct.
5. **Vulkan bring-up** — instance, device, queues, surface selection and the swapchain are done.
   Swapchain *recreation* on resize is not; see the notes for why that is deferred.
6. **GPU-driven culling** — the two-pass shaders, their CPU mirrors and the compute pipelines are
   done. What is left is writing the descriptors and the barrier between the passes. Culling should
   dispatch 16 meshlets per thread, not one.
7. **SPIR-V in the jar** — done. `shaderc` compiles the GLSL during the build and `verifyJar`
   asserts the modules are in the jar. The compiler found two real errors on its first run.
8. **Compute pipelines** — done. `gpu/CullingPipeline` creates the frustum and occlusion
   pipelines from the jar's SPIR-V; the descriptor layout is held as data in `gpu/CullBindings`
   so the shaders, the layouts and the writes cannot drift apart.
9. **Lighting into the vertex stream** — done. `Quad` carries four packed corner lights instead of
   one, `SectionMesher` computes them from the light cache during meshing, and light is its own
   vertex stream so re-lighting a section does not rewrite its geometry. Light became part of the
   vertex dedup key: two corners can share a position and a texture coordinate while being lit
   differently, and merging them would make a seam depend on mesher visit order.
10. **Minecraft block adapter** — done. `net.amdfaster.mc` is the only package that touches
   `net.minecraft`, and it reduces a `BlockState` to the integers the mesher needs, so everything
   downstream stays testable without a running client.
11. **Block shaders** — done. `block.vert` unpacks the per-vertex light word and `block.frag`
   applies the lightmap and the occlusion shade. No `discard`: cutout runs a Z pre-pass with depth
   test `EQUAL`, because `discard` would disable early depth on the pass drawing most of the scene.
12. **Command recording** — the dispatches, the barrier between the two passes, and the indirect
   draw that consumes the count buffer. The shaders now exist, so there is something to draw.
13. **Swapchain recreation** — deliberately not done yet; see the notes. Getting it wrong shows
    a black window instead of throwing, which is worse than a crash.
14. **Transparent sorting on the GPU** — radix sort with on-chip local sort. Not bitonic: bitonic
   is O(n log²n), needs a power of two, and scatters to global memory.
15. **Entity rendering that ignores entity count** — done on the CPU side. `EntityBuffer` keeps a
    frame's entities in primitive arrays so nothing is allocated per entity, `EntityCuller` bounds
    what reaches the expensive stages by distance, frustum and a budget, and
    `SpatialEntityBatcher` makes the draw count depend on where entities are rather than on the
    order they arrived in. Measured on the same geometry, the greedy batcher this replaces produced
    20 draws when entities arrived grouped and 1000 when they arrived interleaved.
16. **APU path** — persistent mapped buffers, no staging copy, and bandwidth-aware LOD.

## Requirements

* Minecraft 1.21.11 with Fabric Loader ≥ 0.18.0
* [Fabric API](https://modrinth.com/mod/fabric-api) (installed automatically by the Modrinth app)
* A Vulkan 1.1 or newer driver; 1.2 unlocks dynamic rendering and the descriptor-buffer paths. On
  Windows the AMD proprietary driver (AMDVLK) and on Linux RADV are both supported; RADV/ACO is the
  better-tested of the two.

If the probe fails, AMD-Faster logs why and stays out of the way — it never breaks the vanilla
renderer.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. Tests compile the GLSL to SPIR-V with `shaderc`, so a shader typo
fails the build here rather than at runtime on a player's machine. CI runs the same command on every
push and then `verifyJar` asserts the result: that the tests ran and passed, that the jar holds the entrypoint and the probe
classes, that `fabric.mod.json`'s version matches the project version, and that `lwjgl-vulkan` is
actually jar-in-jar'd (Minecraft ships lwjgl, lwjgl-glfw and lwjgl-opengl but not the Vulkan
bindings).

## Design notes

Everything that informed the design is written up in [`docs/notes/`](docs/notes/00_INDEX.md):
the RDNA optimisation guidance, Mesa/RADV and ACO, a source reading of Sodium, VulkanMod and
Nvidium, the Vulkan/VMA API surface, queue and barrier behaviour, the AMD-Faster design itself,
JVM/LWJGL/Fabric constraints, the paper and forum survey behind the roadmap above, plus a record of
each implementation stage as it landed — including the bugs the tests caught and the assumptions
that turned out to be wrong.

## Licence

LGPL-3.0-only. Sodium is PolyForm Shield and its source is **not** a basis for anything here;
VulkanMod, Nvidium and Iris are LGPL-3.0 and were read as prior art.
