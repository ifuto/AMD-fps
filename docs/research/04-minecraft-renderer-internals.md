# Minecraft 1.21.11 renderer internals (verified, not guessed)

This file records what the **actual** Minecraft 1.21.11 classes look like, read from the mapped jar
that the CI build produces. The method signatures below were extracted with
`.research/tools/javapy.py` from class files pulled out of the CI run through the `exfil` task - not
from documentation, not from another version, and not from memory. That matters because 1.21.5-1.21.11
replaced the old `RenderType`/`VertexBuffer` terrain path with the `GpuDevice` + `RenderPipeline`
abstraction, and any code written against the old shape would simply not compile.

## 1. Terrain pipeline, as it exists in 1.21.11

```
LevelRenderer.renderLevel(...)
  └── prepareChunkRenders(Matrix4fc, double, double, double)
        └── SectionRenderDispatcher  (one per level)
              └── RenderSection                              (one per 16³ section)
                    ├── CompileTask (abstract)
                    │     ├── RebuildTask       -> SectionCompiler.compile(...)
                    │     └── ResortTransparencyTask
                    └── mesh: CompiledSectionMesh  (implements SectionMesh)
                          └── buffers: Map<ChunkSectionLayer, SectionBuffers>
```

Verified signatures:

* `net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask`
  * `protected final AtomicBoolean isCancelled`, `isCompleted`, `protected final boolean isRecompile`
  * `public abstract CompletableFuture<?> doTask(SectionBufferBuilderPack)`
  * `public abstract void cancel()`, `protected abstract String name()`
  * `public BlockPos getRenderOrigin()`
* `...RenderSection$RebuildTask`: `protected final RenderSectionRegion region`,
  ctor `(RenderSection, RenderSectionRegion, boolean)`, `doTask(SectionBufferBuilderPack)`
* `...RenderSection$ResortTransparencyTask`: holds a `CompiledSectionMesh`
* `net.minecraft.client.renderer.chunk.SectionMesh` (interface):
  * `SectionBuffers getBuffers(ChunkSectionLayer)`
  * `boolean isEmpty(ChunkSectionLayer)`, `boolean hasRenderableLayers()`,
    `boolean hasTranslucentGeometry()`, `boolean isDifferentPointOfView(TranslucencyPointOfView)`
  * `List<?> getRenderableBlockEntities()`, `boolean facesCanSeeEachother(Direction, Direction)`
  * `void close()`
* `net.minecraft.client.renderer.chunk.SectionBuffers`:
  * fields: `GpuBuffer vertexBuffer`, `GpuBuffer indexBuffer`, `int indexCount`,
    `VertexFormat$IndexType indexType`
  * ctor `(GpuBuffer, GpuBuffer, int, VertexFormat$IndexType)` plus getters/setters for all four
    fields and `close()`
  * **This is the object AMD-Faster wants to own**: the terrain vertex/index storage is already a
    pair of `GpuBuffer` objects with an index count, i.e. exactly what a persistent arena plus
    indirect drawing needs.
* `net.minecraft.client.renderer.chunk.CompiledSectionMesh`:
  * `public static final SectionMesh UNCOMPILED`, `EMPTY`
  * fields: `List renderableBlockEntities`, `VisibilitySet visibilitySet`,
    `MeshData$SortState transparencyState`, `TranslucencyPointOfView translucencyPointOfView`,
    `Map buffers`
  * ctor `(TranslucencyPointOfView, SectionCompiler$Results)`
* `net.minecraft.client.renderer.chunk.ChunkSectionsToRender` (record):
  * components: `GpuTextureView textureView`, `EnumMap drawsPerLayer`, `int maxIndicesRequired`,
    `GpuBufferSlice[] chunkSectionInfos`
  * `void renderGroup(ChunkSectionLayerGroup, GpuSampler)`
  * **This is the submission point**: one texture view plus a map of per-layer draw lists, rendered
    as a group. Replacing `renderGroup` with a batched indirect submission changes only this class,
    which keeps the change small and reversible.
* Also present (not yet analysed): `SectionCompiler$Results`, `SectionCopy`,
  `CompileTaskDynamicQueue`, `RenderRegionCache`, `RenderSectionRegion`, `TranslucencyPointOfView`,
  `VisGraph`, `VisibilitySet`, `ChunkSectionLayer`, `ChunkSectionLayerGroup`.

## 2. Where AMD-Faster intervenes, and why there

| Hook | Purpose | Technique |
|---|---|---|
| `SectionRenderDispatcher.RenderSection` mesh build (RebuildTask path) | capture vertex/index data as it is produced, so it can be written into AMD-Faster's own arena instead of a per-section VBO | `PERSISTENT_STAGING_RING`, `COMPACT_TERRAIN_VERTEX` |
| `SectionBuffers` | hand out slices of the arena instead of individual buffers | `PERSISTENT_STAGING_RING` |
| `ChunkSectionsToRender.renderGroup` | replace per-section draws with indirect batches, and take the draw count from the GPU | `MULTI_DRAW_INDIRECT`, `GPU_DRIVEN_DRAW_COUNT` |
| `SectionRenderDispatcher` visibility (region graph, `VisGraph`/`VisibilitySet`) | region-based BFS with a depth pyramid instead of the flat per-section sweep | `REGION_BFS_CULLING`, `DEPTH_PYRAMID_OCCLUSION` |

Two properties of this plan are worth stating explicitly:

1. **Every hook is on the terrain path only.** Sky, entities, particles and the GUI keep their
   vanilla code, so a failure in AMD-Faster's path degrades to "terrain is not drawn accelerated"
   rather than "the game crashes".
2. **The vertex format change is additive.** `SectionBuffers` already carries an
   `IndexType` and an index count; the compact 16-byte format is a second representation that the
   tuner can select, not a replacement of the vanilla data.

## 3. What is still unknown and how it will be resolved

* The exact `MeshData`/`VertexFormat` element layout that `SectionCompiler` produces in 1.21.11 -
  needed before writing the vertex repacker. Resolved by pulling `MeshData`, `VertexFormat`,
  `DefaultVertexFormat`, `SectionCompiler$Results` and `SectionBufferBuilderPack` through the same
  `exfil` channel.
* The `GpuBuffer`/`GpuDevice` API surface (buffer creation, mapping, `GpuBufferSlice` semantics).
  Resolved the same way, from `com.mojang.blaze3d.buffers.GpuBuffer`,
  `...buffers.GpuBufferSlice`, `...systems.GpuDevice`, `...opengl.GlDevice`.
* Whether `RenderSystem` still exposes `recordRenderCall`-style thread hopping in 1.21.11, which
  decides how the terrain submission is scheduled onto the render thread.

Each of these is a one-run `exfil=classes:...` batch, decoded locally with
`.research/tools/javapy.py`.
