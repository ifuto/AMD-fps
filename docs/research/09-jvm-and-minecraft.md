# The JVM / Minecraft side — where the real bottleneck often is

Sources studied: LWJGL 3 docs (`MemoryUtil` / `MemoryStack`, GLFW, Vulkan
bindings), SpongePowered Mixin docs, Fabric API / NeoForge rendering docs,
Project Panama / FFM (JEP 454), `VarHandle`, G1/ZGC, JMH, async-profiler /
JFR / spark, and Minecraft's own chunk meshing / lighting / culling code.

## The Minecraft rendering pipeline (1.21.11)

```
ClientChunkManager (network → WorldChunk)
  └─ ChunkBuilder (worker threads) ──► ChunkTask (meshing)
       └─ SectionRenderDispatcher / ChunkBuildOutput
            └─ WorldRenderer.render(...)  ← the GL draw
                 └─ GameRenderer.renderWorld  ← per-frame camera/frustum
                      └─ Minecraft.render      ← frame begin/end, GUI
                           └─ RenderSystem.flipFrame  ← glfwSwapBuffers
```

AMD-Faster hooks the last two rungs (frame begin/end) and, in overlay mode,
the `GameRenderer.renderWorld` rung. The chunk pipeline above it is left
untouched in v0.1.

## Mixin — how we get in

- `Minecraft.runTick(boolean)` HEAD → `Backend.beginFrame` + overlay key poll.
- `Minecraft.render(boolean)` HEAD → cancel when overlay active.
- `Minecraft.stop()` → save pipeline cache, tear down.
- `GameRenderer.renderWorld(RenderTickCounter)` HEAD → cancel when overlay active.
- `RenderSystem.flipFrame(Window, TracyFrameCapturer)` → redirect
  `glfwSwapBuffers` → `Backend.endFrame`.
- `Framebuffer.blitToScreen()` HEAD → cancel when overlay active.
- `BakedQuad` accessor → sprite / shade / tintIndex for the mesher.

All mixins are client-side and listed in `amdfaster.client.mixins.json`.

## LWJGL — the native bridge

- `org.lwjgl.system.MemoryStack` — every Vulkan call's scratch structs.
  **Rule: never allocate structs on the Java heap in a hot loop; use
  `MemoryStack.stackPush()` / `stackPop()`.** All our vk code follows this.
- `org.lwjgl.system.MemoryUtil.memAlloc/memFree` — direct `ByteBuffer`s for
  SPIR-V upload and mapped memory views.
- `org.lwjgl.glfw.GLFWVulkan` — `glfwGetRequiredInstanceExtensions`,
  `glfwCreateWindowSurface`.
- `org.lwjgl.vulkan.VK10/11/12/13`, `KHRSwapchain`, `KHRSurface`,
  `KHRDynamicRendering`, `KHRSynchronization2`, `KHRTimelineSemaphore`,
  `EXTDebugUtils`, `EXTMemoryBudget`.

The `lwjgl-vulkan` jar is **pure Java** — the actual Vulkan loader
(`libvulkan.so.1` / `vulkan-1.dll`) comes from the OS / GPU driver. This
is why jar-in-jar works and the Modrinth App install is one file.

## JVM performance

- **Allocation is the enemy.** Every `new` in a per-frame path is a GC
  pressure point. Our mesher reuses scratch arrays (`MeshBuilder.positions`,
  `uvs`); the overlay's `LineOverlay` uses one pre-allocated direct buffer.
- **Escape analysis.** Small structs that don't escape are stack-allocated
  by C2 — keep helper methods small and non-escaping where it matters.
- **Project Panama / FFM (JEP 454).** Not needed in v0.1 — LWJGL's
  `MemoryUtil` already gives us off-heap access. Panama would matter if we
  call Vulkan directly without LWJGL, which we don't.
- **GC choice.** G1 is the default and fine; ZGC/Shenandoah help if the
  user runs a huge heap. We don't touch GC settings, but the overlay's
  frame-time graph makes GC pauses visible (a spike = likely a GC cycle).
- **JMH.** For micro-benchmarks of the mesher (e.g. "is the quad decoder
  faster than Sodium's?"). Not wired into the build yet; the harness lives
  in `tools/`.
- **async-profiler / JFR / spark.** `spark` is the Minecraft-specific
  profiler mod — the overlay's stats complement it (spark = CPU+alloc,
  our overlay = GPU+VRAM).

## Minecraft internals we depend on

- **Chunk sections** (`ChunkSection` / `LevelChunkSection` in Mojmap):
  `getBlockState(x,y,z)`, `isEmpty()`, `getBlockStateContainer()`.
- **Chunk access:** `World.getChunk(int,int)` (WorldView) → `WorldChunk`,
  `chunk.getSectionArray()`, `level.getSectionIndex(y)` (HeightLimitView).
- **Models:** `Minecraft.getBakedModelManager().getBlockModels().getModel(state)`
  → `BlockStateModel.collectParts(random)` → `BlockModelPart.getQuads(face)`
  → `BakedQuad.getPosition(i)` / `getTexcoords(i)` / `face()` /
  `hasTint()` + accessor for `sprite`/`shade`/`tintIndex`.
- **Lighting:** `BlockRenderView.getLightLevel(LightType, BlockPos)`.
- **Culling:** `BlockState.isOpaqueFullCube()`, `isSolidBlock(view, pos)`,
  `isAir()`, `getRenderType() == BlockRenderType.MODEL`.
- **Atlas:** `Minecraft.getAtlasManager().getAtlasTexture(id)` →
  `SpriteAtlasTexture`; `Sprite.getMinU/getMinV/getMaxU/getMaxV`.
- **Window:** `Minecraft.getWindow().getHandle()` (GLFW `long`),
  `getFramebufferWidth/Height`.
- **Input:** `InputUtil.isKeyPressed(window, key)` for the F6 toggle.

All Yarn names verified against the 1.21.11 yarn mappings.
