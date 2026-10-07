# AMD-Faster — Architecture

## What this mod is

A **client-side Fabric mod for Minecraft 1.21.11** that ships its own
**Vulkan rendering backend** with AMD-first tuning. It is:

- **Self-contained.** One jar. `lwjgl-vulkan` is bundled jar-in-jar; the
  Vulkan loader itself comes from the OS / GPU driver. No manual installs,
  no native libraries, installs from the Modrinth App / Prism / MultiMC.
- **AMD-first but not AMD-only.** The tuning tables (`AmdTuning`) go deep
  on RDNA / GCN / APU; on Intel or NVIDIA hardware the mod still works,
  it just doesn't pretend to know their quirks.
- **A backend, not a full renderer (yet).** v0.1 ships the Vulkan backend
  (instance → device → swapchain → pipelines → sync) plus a
  **diagnostics / benchmark overlay** (F6). The full world-render
  replacement is the roadmap.

## Why Vulkan and not "optimised OpenGL"

See [../research/06-opengl-amd-quirks.md](../research/06-opengl-amd-quirks.md).
Short version: AMD's GL driver loses on small buffer sub-updates, uniform
updates and shader-switch cost; RGP (AMD's profiler) doesn't support GL at
all; and Sodium's persistent-mapped + indirect-draw design is exactly what
a Vulkan backend gives us natively. Vulkan also unlocks the whole RDTS
toolchain (RGP / RGA / RMV).

## Module map

```
dev.ifuto.amdfaster
├── AmdFaster               static facade: config, state, logger
├── AmdFasterClient         Fabric ClientModInitializer entrypoint
├── config/AmdFasterConfig  config/amdfaster.json (GSON, versioned)
├── platform/
│   ├── GpuProbe            GL + Vulkan GPU identification
│   ├── AmdArchitecture     GCN / RDNA / APU / Steam Deck classification
│   └── AmdTuning           frames-in-flight, staging, ReBAR budget, batch caps
├── vk/                     the Vulkan backend
│   ├── VkUtil              result checking, resource loading
│   ├── Instance            instance + surface + debug messenger
│   ├── Device              physical device selection + logical device + features
│   ├── MemoryManager       memory-type policy + persistent mapping + staging
│   ├── GpuBuffer           VkBuffer + VkDeviceMemory (+ optional mapping)
│   ├── Swapchain           swapchain + depth, lazy recreation
│   ├── FrameSync           timeline semaphore + per-slot binary semaphores
│   ├── PipelineCache       persisted vkPipelineCache
│   ├── ShaderModule        loads precompiled SPIR-V from the jar
│   ├── TerrainPipeline     terrain VS/PS, dynamic rendering, vertex format
│   ├── LinePipeline        overlay lines (region wireframe, frame graph)
│   ├── DescriptorAllocator tiny pool + set allocator
│   ├── RegionArena         per-region vertex+index buffer pair
│   ├── FrameConstants      192-byte std140 FrameData UBO
│   └── Backend             owns everything; beginFrame/endFrame/shutdown
├── mesh/
│   ├── VertexRecord        the 28-byte packed vertex
│   ├── QuadDecoder         BakedQuad → vertex inputs (via accessor mixin)
│   ├── MeshBuilder         BlockStateModel → vertices+indices
│   └── SectionMesher       one 16³ section → vertices+indices
├── overlay/
│   ├── OverlayController   F6 toggle, stats, lifecycle
│   ├── OverlayScene        meshes sections around the player, draws them
│   └── LineOverlay         immediate-mode line renderer
└── mixin/
    ├── MinecraftMixin      runTick/render/stop hooks
    ├── GameRendererMixin   skip world render in overlay mode
    ├── RenderSystemMixin   replace glfwSwapBuffers with our present
    ├── RenderTargetMixin   skip the GL blit in overlay mode
    └── BakedQuadAccessor   sprite/shade/tintIndex accessors
```

## Frame lifecycle

```
Minecraft.runTick(HEAD)
  └─ Backend.initIfNeeded(window)        (once)
  └─ Backend.beginFrame()                acquire swapchain image
  └─ OverlayController.tick()            poll F6
Minecraft.render(HEAD)                    cancel if overlay active
GameRenderer.renderWorld                  cancel if overlay active
  └─ (overlay) OverlayScene.renderFrame() records Vulkan draws into the cmd buffer
RenderSystem.flipFrame
  └─ RenderSystemMixin redirect glfwSwapBuffers → Backend.endFrame()
       └─ record + submit + present (timeline semaphore signals frame N+1)
```

## Memory policy (the AMD part)

```
DEVICE_FAST    -> DEVICE_LOCAL               (uploaded via staging copy)
                  ...unless a big DEVICE_LOCAL|HOST_VISIBLE heap exists
                     (ReBAR / APU): then that heap, persistently mapped,
                     zero-copy, until AmdTuning.directUploadBudget runs out
DIRECT_UPLOAD  -> DEVICE_LOCAL|HOST_VISIBLE when available, else HOST_VISIBLE|COHERENT
STAGING        -> HOST_VISIBLE|COHERENT (short-lived transfer src)
```

See [../research/04-vma-memory.md](../research/04-vma-memory.md) and
[../research/02-gcn-and-apu.md](../research/02-gcn-and-apu.md).

## Device selection

Among present-capable Vulkan 1.2+ devices:

1. **AMD wins** (`preferAmdDevice`, default on) — the mod exists to make
   the red team fast; on Intel/NVIDIA the tuning tables go quiet but the
   backend still works.
2. **Discrete over integrated** (hybrid laptops → the dGPU).
3. **Higher Vulkan version** as a tiebreaker.

## What v0.1 renders

The **overlay**: a configurable cube of sections around the player, meshed
with `SectionMesher` into `RegionArena`s, drawn with `TerrainPipeline`,
plus a `LineOverlay` (region wireframes + frame-time graph). The vanilla
world renderer is skipped while the overlay is active — that is the
benchmark condition (same world, same camera, only the renderer changes).

## Roadmap

- **v0.2** — mesh-shader terrain (`VK_EXT_mesh_shader`, RDNA2+): sections
  become task/mesh dispatches, the vertex pass disappears. BC7 atlas
  compression via Compressonator.
- **v0.3** — GPU-driven culling: a compute shader writes the
  `VkDrawIndexedIndirectCommand` buffer, the draw reads it. Port of
  Nvidium's occlusion pipeline to AMD.
- **v0.4** — compute translucency sorting (wave32 bitonic sort in LDS).
- **v0.5** — full world-render replacement (entities, sky, particles, GUI
  on Vulkan), at which point this stops being "an overlay" and becomes
  "the renderer".
