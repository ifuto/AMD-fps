# Sodium's architecture — what we learned (and what we copied)

Source: CaffeineMC/sodium source (LGPL — we do **not** copy code, only
ideas; our implementation is original and targets Vulkan, not GL).

## The big ideas

1. **Replace the renderer, don't patch it.** Sodium throws away vanilla's
   `WorldRenderer` chunk rendering and replaces it with a region-based
   renderer. We do the same at the backend level (Vulkan instead of GL).
2. **Regions, not sections.** A *render region* is a cube of 4×4×4 (Sodium)
   or 8×8×8 (our default) chunk sections sharing one vertex buffer + one
   index buffer. Fewer buffers, fewer binds, better L2 locality.
3. **Persistent-mapped buffers.** One `ARB_buffer_storage` allocation per
   region, mapped once, written by the CPU, read by the GPU. No per-frame
   upload calls.
4. **Multi-draw indirect.** One `glMultiDrawElementsIndirect` per region;
   the per-section draw commands live in a GPU buffer.
5. **Chunk build pipeline.** Meshing runs on worker threads, results are
   handed to the render thread via a `ChunkTask` queue, and the renderer
   swaps in finished sections atomically.
6. **Translucency sorting** as a separate, optional pass (Nvidium extends
   this with compute-shader sorting).

## What we took

- **Region arenas** (`RegionArena`) — one VkBuffer pair per region, one
  push-constant origin, one indirect draw per section.
- **Persistent mapping** as the default upload path (`MemoryManager`,
  `GpuBuffer`).
- **The meshing pipeline shape** — `MeshBuilder` mirrors Sodium's
  `BlockRenderer` flow (collect parts → for each cull face → for each quad
  → emit), but writes our 28-byte vertex format.
- **The culling predicates** — `isFaceCulled` mirrors Sodium's
  `shouldDrawSide` / VulkanMod's `faceNotOccluded`.

## What we did differently

- **Vulkan, not GL.** No `ARB_buffer_storage`, no `glMultiDrawElementsIndirect`
  — instead `vkCmdDrawIndexedIndirect` and persistently mapped `VkDeviceMemory`.
- **No Fabric API dependency.** We talk to Minecraft through Mixin only, so
  we don't fight Sodium/Iris for event buses. (We *can* coexist with them —
  see the README's compatibility section.)
- **Smaller scope in v0.1.** Sodium replaces the *whole* world renderer;
  we ship a Vulkan backend + a diagnostics/benchmark overlay. The full
  world-render replacement is the roadmap.

## License note

Sodium is **PolyForm Shield** (in the 1.21.11 tree) / LGPL in older trees.
We do not copy Sodium code. The ideas above are documented here so the
next contributor understands *why* the code is shaped the way it is.
