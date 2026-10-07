# GPUOpen Performance Guides & "Breaking Down Barriers" — applied

Source: gpuopen.com/performance/ and the "Breaking Down Barriers" series.

## The checklist, mapped to AMD-Faster

| GPUOpen advice | Where it lands in our code |
|---|---|
| Maximise occupancy | Small shaders (terrain.frag ~30 VGPRs), no spill. See `01-rdna-isa.md`. |
| Keep the L2 cache hot | Region-relative vertex buffers (one VkBuffer per region) so vertex fetches are spatially local. `RegionArena`. |
| Use wave32 on RDNA | Compute threadgroups sized in multiples of 32 (future passes). |
| Minimise barriers | Dynamic rendering (no render-pass barriers), single queue family on AMD (graphics == present). `Device` picks the universal family. |
| Batch draws | One `vkCmdDrawIndexed` per section, regions batch sections. Future: `vkCmdDrawIndexedIndirectCount`. |
| Avoid CPU-GPU sync | Timeline semaphore (one wait per frame), no `vkQueueWaitIdle` in the hot path. `FrameSync`. |
| Persistent-mapped uploads | The default on ReBAR / APU. `MemoryManager` + `GpuBuffer`. |
| Use the right heap | `DEVICE_LOCAL` for GPU-only, `DEVICE_LOCAL\|HOST_VISIBLE` for CPU-written, `HOST_VISIBLE\|COHERENT` for staging. `MemoryManager.Preference`. |
| Profile with RGP / RGA | `docs/research/10-toolchain-rdts-gpa.md`. |

## "Breaking Down Barriers" — the lessons we took

The series is about removing pipeline barriers by restructuring the work.
The Minecraft-specific translation:

1. **Don't sync per chunk.** Vanilla GL has to flush/sync around buffer
   uploads; Vulkan lets us record everything and submit once. Our meshing
   writes into persistently mapped memory and the GPU reads it next frame —
   no `glFinish`, no fence per chunk.
2. **Don't sync per draw.** One command buffer per frame, one submit.
3. **Don't sync CPU↔GPU for visibility.** Future: GPU-driven occlusion
   (compute shader writes a draw-indirect buffer, the draw reads it) — the
   Nvidium technique, ported to AMD.

## What we deliberately did NOT take from the guides (yet)

- **Async compute queues** for meshing. RDNA supports it, but Minecraft's
  meshing is already on worker threads; moving it to a compute queue is a
  v0.3+ project and needs careful interaction with the Java heap.
- **Sampler feedback / residency**. Big win for texture streaming, but the
  atlas is small and resident; not worth the complexity in v0.1.
- **VK_EXT_mesh_shader** for terrain. v0.2 headline feature.
