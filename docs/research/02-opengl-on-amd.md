# OpenGL on AMD: what AMD-Faster does with the 4.6 path

Minecraft renders through Blaze3D, which on 1.21.11 is a thin abstraction over OpenGL (and, since
1.21.6, over a `GpuDevice` interface that a mod can implement with something else - that is how
VulkanMod works). The GL path in AMD-Faster does not replace Blaze3D: it changes how Blaze3D *uses
the driver*.

## 1. The extensions that matter, and why

| Extension | Use in AMD-Faster | Notes |
|---|---|---|
| `ARB_buffer_storage` | persistent mapped upload ring (`GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT`) | core in GL 4.4; every AMD 4.6 driver has it |
| `AMD_pinned_memory` | zero-copy uploads from pinned host memory | Mesa exposes it; AMD's Windows GL driver does not |
| `ARB_multi_draw_indirect` | one call for all visible terrain sections of a pass | core in GL 4.3 |
| `ARB_indirect_parameters` | GPU-written draw count (`glMultiDrawElementsIndirectCount`) | core in GL 4.6 |
| `ARB_shader_draw_parameters` | `gl_DrawID`/`gl_BaseInstance` so batched draws can index per-draw data | core in GL 4.6 |
| `ARB_shader_storage_buffer_object` | section metadata and draw lists live in SSBOs | core in GL 4.3 |
| `ARB_compute_shader` | region BFS traversal and mesh expansion | core in GL 4.3 |
| `ARB_clip_control` | reversed-Z float depth | core in GL 4.5 |
| `ARB_bindless_texture` | texture table without per-draw binds | **not** core; not exposed by AMD's Windows GL driver, so the GL path uses array textures plus a material index instead |
| `ARB_direct_state_access` | removing redundant binding work | core in GL 4.5 |
| `ARB_sparse_buffer` | virtual section buffers | optional, only used when present |

The single most important fact in that table is the `ARB_bindless_texture` row: on Windows the AMD
OpenGL driver does not expose it (NVIDIA does), so a design that depends on bindless textures cannot
be a portable GL design. AMD-Faster therefore treats bindless as a Vulkan-only technique
(`VK_EXT_descriptor_indexing`) and uses a texture-array + material-index scheme on the GL path - the
same conclusion Sodium reached.

## 2. Driver-specific behaviour the mod compensates for

* **Windows AMD GL**: high fixed cost per small `glBufferSubData`. Uploads are therefore coalesced
  (the tuner asks for >= 128 KiB batches when `DriverKind.isWeakGlSubmission()`), and per-section
  draw calls are replaced by indirect batches wherever the hardware allows it.
* **Mesa radeonsi**: shader compilation happens in a background thread pool and is cached per
  pipeline; the cost of a new permutation is a stall only if it happens mid-frame. AMD-Faster
  pre-warms the permutations it knows it needs during level load.
* **Both**: `glMultiDrawElementsIndirectCount` is core in GL 4.6 but the Windows AMD driver treats
  the count as a hint more than a contract; the GL path keeps a CPU-side count so a driver that
  ignores the GPU-written count cannot produce a wrong frame.

## 3. What the GL path actually changes

1. **Uploads**: one persistent mapped ring buffer per frame instead of per-buffer `glBufferSubData`
   calls; the ring is sized from the hardware (see `TuningPlan.stagingRingMiB()`).
2. **Submission**: section draws are packed into indirect command buffers and issued in batches
   (`TuningPlan.maxCommandsPerBatch()`), which is what the RDNA command processor wants: long bursts
   instead of thousands of short draws interleaved with state changes.
3. **Visibility**: region-based BFS culling on the GPU with a depth pyramid from the previous frame
   (`Technique.REGION_BFS_CULLING`, `Technique.DEPTH_PYRAMID_OCCLUSION`).
4. **Vertex data**: the compact 16-byte section vertex format (10-bit per-axis offsets, the
   `MAX_TRANSFORMATION_SIZE_BITS = 10` layout Nvidium uses), which cuts section memory traffic.
5. **State**: shadow-state tracking so unchanged state is never re-issued.

Everything else - sky, entities, particles, GUI - stays Blaze3D's own code path. That is deliberate:
it keeps Iris and shader packs possible and it keeps the breakage surface small.

## 4. What this path cannot do

* Bindless textures (driver support), mesh shaders (GL has no equivalent), and dynamic VGPR
  allocation (a Vulkan/compiler feature). Those are the reasons the mod also ships a Vulkan path.

## 5. Reference implementations consulted

| Project | Licence | What was taken |
|---|---|---|
| CaffeineMC/sodium-fabric | LGPL-3.0 | the *design* of persistent-mapped staging + indirect terrain submission; no code copied |
| MCRcortex/nvidium | LGPL-3.0 | region grid geometry (8x4x8), the 16-byte section metadata idea, 10-bit transformation layout |
| xCollateral/VulkanMod | LGPL-3.0 | the pattern for bundling LWJGL Vulkan modules as nested jars, and the observation that Blaze3D's `GpuDevice` can be replaced wholesale |
| Igalia/mesa + llvm/llvm-project | MIT / Apache-2.0 | occupancy formulas and ISA facts used by the tuning core (see `01-amd-hardware-notes.md`) |

AMD-Faster is LGPL-3.0-or-later itself; the clean-room statement is in `NOTICE`.
