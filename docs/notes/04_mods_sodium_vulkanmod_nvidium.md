# 04 — 既存Modのソース精読（Sodium / VulkanMod / Nvidium）

**実際にクローンして読んだもの**（すべて `/home/user/.cache/refs` 配下に blobless clone）:

| Mod | リポジトリ | 取得したもの | 対象MC |
|---|---|---|---|
| **Sodium** | `CaffeineMC/Sodium` @ `8aa723c` | 開発ブランチ（0.9.3-alpha.1） | **MC 26.3** |
| **Sodium** | 同上 tag `mc1.21.11-0.8.12` | **0.8.12（= 1.21.11 用の現行安定版）** | **MC 1.21.11** |
| **VulkanMod** | `xCollateral/VulkanMod` @ `087ef24` | **0.6.8+1.21.11-dev** | **MC 1.21.11** |
| **Nvidium** | `MCRcortex/nvidium` @ `f2028b2`（dev ブランチ、tag 無し） | 0.3.0 | **MC 1.21（＝更新停止気味）** |

> 注: `CaffeineMC/Nvidium` と `FabricMC/Nvidium` は **404（存在しない）**。現行は `MCRcortex/nvidium`。
> GitHub 検索で見つかるのはフォーク（`Seristic/nvidium` 等）のみ。
> → **「ベンダ特化バックエンド Mod」の先行例は存在するが、1.21.11 には追随していない。**

---

## 0. ★最重要の発見: Minecraft は 26.1 からバージョン体系が変わり、**公式 Vulkan バックエンド（RenderPearl）が入った**

- Fabric Meta API `https://meta.fabricmc.net/v2/versions/game`（実際に取得）で確認した版列:
  - `1.21.11`（stable）が **1.x 系の最後**。
  - それ以降は `26.1`, `26.1.1`, `26.1.2`, `26.2`, `26.3`（stable）、`26.4-snapshot-1..3`。
- **Sodium 開発ブランチのソースが Mojang の新 API を直接 import している**（実コードで確認）:
  ```java
  import com.mojang.renderpearl.api.device.GpuDevice;
  import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
  ...
  if (((GpuDeviceAccessor) device).sodium$getBackend() instanceof VulkanDevice) {
      if (device.getDeviceInfo().features().multiDrawDirectInterleaved()) return VK_MULTIDRAW;
      else if (device.getDeviceInfo().features().multiDrawIndirect())     return VK_INDIRECT;
  }
  ```
  （`net/caffeinemc/mods/sodium/client/gpu/device/backend/DrawBackend.java`）
- つまり **MC 26.x には「OpenGL / Vulkan を抽象する Mojang 公式の GpuDevice 層（RenderPearl）」が存在**し、
  Sodium 0.9.x はそれを前提に **`gl_multidraw` / `ext_multidraw` / `indirect` の3バックエンド**を持つ。

**AMD-Faster への影響（設計判断の分岐点）**:
1. **1.21.11 対象**（ユーザー指定）なら、Minecraft はまだ Blaze3D/OpenGL のみ。
   → VulkanMod 型の「Blaze3D を丸ごと置き換える」アプローチが必要。
2. **26.x 対象**なら、**Minecraft 側に Vulkan デバイスがある**ので、
   「自前 Vulkan インスタンスを立てる」のではなく **公式 Vulkan デバイスの上で
   AMD 最適化された draw パスを提供する**という、はるかに軽い設計が可能になる。
   → **Nvidium 的なベンダ特化層を Vulkan 上でやるのが自然**。
3. ただし 26.1+ は **Java 25 必須**（1.21.11 は Java 21）。ビルド構成と CI が変わる。

---

## 1. Sodium 0.8.12（MC 1.21.11）— AMD-Faster が差し込む場所

### 1.1 ビルド構成（`buildSrc/src/main/kotlin/BuildConfig.kt` より実測）
```
MINECRAFT_VERSION      = "1.21.11"
NEOFORGE_VERSION       = "21.11.42"
FABRIC_LOADER_VERSION  = "0.19.2"
FABRIC_API_VERSION     = "0.140.0+1.21.11"
MOD_VERSION            = 0.8.12
```
- マルチモジュール構成: `common` / `fabric` / `frapi` / `neoforge`（+ `common/src/{api,boot,desktop}`）。
- **`common/src/api` に公開 API がある**（`VertexBufferWriter`, `VertexSerializer(Registry)`,
  `VertexFormatRegistry`, `MemoryIntrinsics`, `ColorABGR/ARGB`, `NormI8`, `SpriteUtil`,
  `BlockEntityRenderHandler`, `ConfigEntryPoint` …）。
  → **AMD-Faster はここだけを使う**のが正しい結合面。内部クラスへの Mixin は極力避ける。

### 1.2 GL 抽象層（`client/gl/**`）＝ AMD-Faster が置き換える層
```
gl/arena/{GlBufferArena, GlBufferSegment, PendingUpload, PendingBufferCopyCommand,
          staging/{StagingBuffer, MappedStagingBuffer, FallbackStagingBuffer}}
gl/buffer/, gl/attribute/, gl/device/{RenderDevice, CommandList, DrawCommandList,
          GLRenderDevice, MultiDrawBatch}, gl/functions/, gl/shader/, gl/state/,
gl/sync/{GlFence...}, gl/tessellation/{GlTessellation, GlVertexArrayTessellation,
          GlPrimitiveType, GlIndexType, TessellationBinding}, gl/util/
```

### 1.3 アップロード経路（★★AMD で最も効く箇所）

`gl/arena/staging/MappedStagingBuffer.java` から実測:
```java
STORAGE_FLAGS = { PERSISTENT, CLIENT_STORAGE, MAP_WRITE }
MAP_FLAGS     = { PERSISTENT, INVALIDATE_BUFFER, WRITE, EXPLICIT_FLUSH }
default capacity = 1024*1024*16  // 16 MB のリング
```
動作（`enqueueCopy` / `addTransfer` / `flush`）:
1. `mappedBuffer.map.write(data, offset)` — **CPU が persistent-mapped リングに直接書く**
2. `commandList.flushMappedRange(map, start, len)` — `glFlushMappedBufferRange`
3. `commandList.copyBufferSubData(ring → arena)` — **サーバ側コピーでアリーナへ**
4. `GlFence` 付き領域で再利用を管理（`FencedMemoryRegion`）
5. リングが一杯なら `FallbackStagingBuffer`（= `glBufferSubData` 相当）へフォールバック

**AMD-Faster の改善ポイント（実装候補）**:
- **ステップ3の「リング→アリーナ」コピーは、APU（UMA）や ReBAR 有効時には不要**。
  GPUOpen RDNA Performance Guide の
  「ReBAR 有効時は `DEVICE_LOCAL|HOST_VISIBLE` に CPU が直書きして**2バッファ間コピーを消す**」
  そのもの。→ **アリーナ自体を persistent-map する経路**を AMD/APU 用に用意する。
- `EXPLICIT_FLUSH` + `glFlushMappedBufferRange` の**フラッシュ粒度**は AMD ドライバで
  コスト特性が違う可能性 → **フラッシュをまとめて回数を減らす**（1フレーム1回）実験価値あり。
- 検証手段: **Mesa の `AMD_DEBUG=nowc`**（GTT write combining 無効）で
  この経路の性能寄与を定量化できる（03章参照）。

### 1.4 地形ドローの実際の形（`render/chunk/DefaultChunkRenderer.java`）

- クラスコメント（原文）:
  > "Renders the terrain for a particular render pass. **Each region is rendered with one draw call.**
  >  The command buffer for each draw command is filled by iterating the sections and adding
  >  the draw commands for each section."
- 実装の流れ:
  1. `renderLists.iterator(renderPass.isTranslucent())` で **可視 ChunkRenderList を反復**
  2. `region.getCachedBatch(renderPass)` → **region × pass ごとにキャッシュされた `MultiDrawBatch`**
  3. `fillCommandBuffer(...)` で section を回して draw 命令を詰める
  4. `prepareTessellation` / `prepareIndexedTessellation`（VAO）
  5. `setModelMatrixUniforms(shader, region, camera, region.getResources().prepareChunkData(commandList))`
  6. `executeDrawBatch` → `commandList.beginTessellating(tessellation)` →
     **`drawCommandList.multiDrawElementsBaseVertex(batch, GlIndexType.UNSIGNED_INT)`**
- 半透明のみ `useIndexedTessellation`（= section 別 index buffer）を使い、それ以外は
  **`SharedQuadIndexBuffer`（共有インデックスバッファ）**で draw を結合する最適化がある
  （コメント: "draw commands can be combined when using a shared index buffer"）。

**含意**: Sodium の地形 draw 数は **可視 region 数 × pass 数** まで既に減っている。
- **AMD-Faster が「draw 呼び出し回数を減らす」だけで稼げる幅は小さい**。
- 効くのは **① region 間のパイプライン/状態変更の削減（= 全 region を 1 draw に畳む）**、
  **② GPU 側カル（region/section を GPU で間引く）**、**③ アップロード経路**、
  **④ 半透明ソートの GPU 化**、**⑤ CPU 側のメッシュビルド**（＝JVM 側）。

---

## 2. VulkanMod 0.6.8（MC 1.21.11）— Vulkan バックエンドの実装教科書

### 2.1 規模と構成（実測）
```
net/vulkanmod  317 java files
  vulkan/  63   (device, memory, queue, pass, framebuffer, shader, texture, util)
  render/ 112   (chunk, engine, model, shader, sky, texture, vertex, profiling)
  mixin/   86   (chunk, render, texture, vertex, window, wayland, matrix, ...)
  config/  38,  gl/ 7,  interfaces/ 10
```
対象: `minecraft_version = 1.21.11`, `yarn_mappings = 1.21.11+build.2`,
`loader_version = 0.18.4`, `fabric_version = 0.140.0+1.21.11`, `mod_version = 0.6.8+1.21.11-dev`

### 2.2 ★配布設計（Modrinth で入れられる形）— `build.gradle` から実測

```gradle
project.ext.lwjglVersion = "3.3.3"
winNatives="natives-windows"; linuxNatives="natives-linux"
macosNatives="natives-macos"; macosArmNatives="natives-macos-arm64"

include(implementation("org.lwjgl:lwjgl-vulkan:$lwjglVersion"))

ext.includeNatives = { name ->
    include(implementation("$name:$lwjglVersion"))
    include(runtimeOnly("$name:$lwjglVersion:$winNatives"))
    include(runtimeOnly("$name:$lwjglVersion:$linuxNatives"))
    include(runtimeOnly("$name:$lwjglVersion:$macosNatives"))
    include(runtimeOnly("$name:$lwjglVersion:$macosArmNatives"))
}
includeNatives("org.lwjgl:lwjgl-vma")      // ← AMD 純正 VMA が LWJGL バインディングで入る
includeNatives("org.lwjgl:lwjgl-shaderc")  // GLSL→SPIR-V の実行時コンパイル
includeNatives("org.lwjgl:lwjgl-spvc")     // SPIRV-Cross
include(runtimeOnly("org.lwjgl:lwjgl-vulkan:$lwjglVersion:$macosNatives"))
```
- さらに Fabric API は **必要なモジュールだけを `includeModule(...)` で JiJ**
  （`fabric-api-base`, `fabric-resource-loader-v0/v1`, `fabric-rendering-v1`,
  `fabric-renderer-api-v1`, `fabric-rendering-fluids-v1`, `fabric-block-view-api-v2`,
  `fabric-lifecycle-events-v1`, `fabric-transitive-access-wideners-v1`）。

**AMD-Faster への含意（配布設計の結論）**:
> **ネイティブ（Vulkan/VMA/shaderc）は「LWJGL の natives 分類子を JiJ する」だけで
> Modrinth の1ファイル配布になる。** 自前 JNI ライブラリをビルド/署名/配布する必要が無い。
> → バックエンドを別リポジトリの「ネイティブライブラリ」にせず、
> **LWJGL バインディング + Java 実装**にするのが最も配布が楽で、Modrinth アプリと相性が良い。

### 2.3 メモリ管理は VMA そのもの（`vulkan/memory/MemoryManager.java`）
- `org.lwjgl.util.vma.Vma.*` を直接使用。`vmaCreateBuffer`, `vmaCreateImage`,
  `vmaMapMemory`/`vmaUnmapMemory`, `vmaDestroyBuffer`。
- **`vmaGetHeapBudgets(...)` でヒープの usage/budget を取得**（01章の
  「`VK_EXT_memory_budget` を使え／毎フレーム聞くな」の実装例）。
- `MemoryType` は `DEVICE_LOCAL` / `HOST_LOCAL` の2種類だけを抽象化。
- `MemoryTypes.GPU_MEM` という既定ヒープを参照。

### 2.4 キュー構成（AMD 推奨と一致）
`vulkan/queue/` に **`GraphicsQueue`, `ComputeQueue`, `TransferQueue`, `PresentQueue`, `Queue`,
`CommandPool`**。各 Queue は `vkGetDeviceQueue(device, familyIndex, 0, pQueue)` で1本取得し、
`QueueFamilyIndices` で family を解決。`PresentQueue` だけ `initCommandPool=false`
（＝**present 専用キューはコマンドプールを持たない**）。

**含意**: GPUOpen の「async compute queue / copy queue / present from another queue」が
そのまま実装されている。**AMD-Faster はこれを踏襲しつつ、
`RADV_QUEUE_DISABLE=compute` などでフォールバック検証する**（03章）。

### 2.5 地形データ構造
- `render/chunk/ChunkAreaManager` → `ChunkArea` → `RenderSection`（`SectionGrid`, `ChunkStatusMap`）
- `render/chunk/buffer/`:
  - `AreaBuffer` — **大きなバッファ上のセグメントアロケータ**（Sodium の arena と同思想。
    `Segment`、`freeSegment`、`upload(buffer, oldOffset, paramsPtr)`）
  - `DrawParametersBuffer` — **section × TerrainRenderType × QuadFacing の draw パラメータ表**
    （`getParamsPtr(areaIndex, renderType, facing)`, `getFirstIndex`, `getIndexCount`,
    `setVertexOffset` …）
  - `DrawBuffers` — area 単位の index/vertex バッファ群と
    `sectionDataBuffer = new UniformBuffer(AREA_SIZE*2*4, MemoryTypes.HOST_MEM)`
  - `UploadManager`
- `vulkan/memory/buffer/IndirectBuffer` は `VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT` のみを持つ
  シンプルなバッファ（＝ indirect draw の引数用）。
- 即時描画側は `vulkan/Drawer.java` が `vkCmdDrawIndexed` / `vkCmdDraw` を直接発行。

**含意**: VulkanMod は「**CPU が draw パラメータ表を作って indirect draw する**」段階。
Nvidium のような **GPU 側カル・GPU 側ソートは無い**。
→ **AMD-Faster の差別化余地はここに明確にある。**

---

## 3. Nvidium 0.3.0（MC 1.21）— ベンダ特化バックエンドの設計と「反面教師」

### 3.1 有効化条件（`Nvidium.java` 実コード）
```java
boolean supported = cap.GL_NV_mesh_shader
                 && cap.GL_NV_uniform_buffer_unified_memory
                 && cap.GL_NV_vertex_buffer_unified_memory
                 && cap.GL_NV_representative_fragment_test
                 && cap.GL_ARB_sparse_buffer
                 && cap.GL_NV_bindless_multi_draw_indirect;
```
→ **6つのうち5つが `GL_NV_*`（NVIDIA 専用）**。唯一の共通拡張は `GL_ARB_sparse_buffer`。

### 3.2 ジオメトリ管理＝疎な巨大バッファ + GPU アドレス
`gl/buffers/PersistentSparseAddressableBuffer.java`（実コード）:
```java
// 1MB ページにした理由（原文コメント）:
// "the nv driver doesnt defrag the sparse allocations easily
//  meaning smaller pages result in more fragmented memory and not happy for the driver"
public static final long PAGE_SIZE = 1<<20;
glNamedBufferStorage(id, size, GL_SPARSE_STORAGE_BIT_ARB);
glMakeNamedBufferResidentNV(id, GL_READ_WRITE);
glGetNamedBufferParameterui64vNV(id, GL_BUFFER_GPU_ADDRESS_NV, holder);  // GPUアドレス取得
```
シェーダは `NV_shader_buffer_load`（GPU アドレスで直接読む）＋
`GL_NV_bindless_multi_draw_indirect` で **1 draw に全チャンクを畳む**。

**★★AMD では絶対に真似してはいけない（根拠つき）**:
- GPUOpen RDNA Performance Guide（01章 §5）が明確に否定:
  > **Tiled/Sparse resources: We don't recommend them** based on their performance hit both on GPU and CPU.
  > Instead use sub allocations and the Copy/Transfer queue to defrag memory.
  > **Never place high traffic resources in Tiled/Sparse**
- しかも `GL_NV_shader_buffer_load`（GPU アドレス）は **AMD の OpenGL ドライバに存在しない**。
- → **AMD-Faster のジオメトリアドレッシングは「巨大アリーナ + オフセット表（bindless 化しない）」
  または Vulkan なら `descriptor_indexing` / `VK_EXT_descriptor_heap` で行う。**

### 3.3 パイプライン構成（`RenderPipeline.java` 実測）
```
PrimaryTerrainRasterizer   … 本描画（task + mesh shader）
RegionRasterizer           … region 単位のオクルージョン raster
SectionRasterizer          … section 単位のオクルージョン raster
TemporalTerrainRasterizer  … テンポラル整合（前フレームの可視性で先に描く）
TranslucentTerrainRasterizer
SortRegionSectionPhase     … GPU 側ソート
```
シェーダ（`assets/nvidium/shaders/`）:
```
occlusion/{scene.glsl, queries/region/{mesh.glsl,fragment.frag},
           region_raster/{mesh.glsl,fragment.frag},
           section_raster/{task.glsl,mesh.glsl,fragment.glsl}}
sorting/{region_section_sorter.comp, sorting_network.glsl}
terrain/{task.glsl, task_common.glsl, mesh.glsl, frag.frag, fog.glsl,
         vertex_format.glsl, temporal_task.glsl,
         translucent/{task.glsl,mesh.glsl}}
```
- **オクルージョンカリングを「mesh shader で極小の region/section 代理ジオメトリを
  depth-only FBO にラスタライズして、前フレームの depth と比較」**という GPU 完結方式で実装。
- **半透明ソートも GPU（sorting network compute）**。
- 設定項目（`NvidiumConfig`）: `extra_rd=100`, `enable_temporal_coherence=true`,
  `max_geometry_memory=2048`(MB), `automatic_memory=true`, `async_bfs=true`,
  `region_keep_distance=32`, `render_fog=true`,
  `translucency_sorting_level=QUADS`, `statistics_level=NONE`。

### 3.4 Sodium への結合の深さ（＝注意すべき点）
`mixin/sodium/` に **`MixinRenderSectionManager`, `MixinRenderRegionManager`,
`MixinChunkBuildOutput`, `MixinChunkBuilder`, `MixinChunkBuilderMeshingTask`,
`MixinChunkJobQueue`, `MixinOptionFlag`, `MixinSodiumOptionsGUI`, `MixinSodiumWorldRenderer`,
`MixinRenderSection`, `SodiumWorldRendererAccessor`** があり、
`sodiumCompat/` に `NvidiumCompactChunkVertex`, `RepackagedSectionOutput`,
`IRepackagedResult`, `SodiumResultCompatibility`, `IRenderSectionExtension`,
`INvidiumWorldRenderer{Getter,Setter}`, `IrisCheck`, `NvidiumOptionFlags`, `ShaderLoader`。
→ **Sodium の内部クラスに深く Mixin しており、Sodium の更新で壊れやすい**。
かつ **Nvidium は 1.21 以降追随していない**（tag 無し・dev のみ・対象 1.21）。

**AMD-Faster への教訓**:
1. **Sodium の公開 API（`common/src/api`）と、安定した少数の Mixin 点だけを使う。**
2. **Sodium のチャンク頂点フォーマットを再パッケージする**設計（NvidiumCompactChunkVertex 相当）は
   有効だが、**バージョン差分を1箇所に閉じ込める**こと。
3. **ベンダ判定は「機能ベース」で行う**（Nvidium は拡張の有無で判定している＝正しい）。
   AMD-Faster も「`VK_EXT_mesh_shader` があるか」「`descriptor_indexing` があるか」
   「memory type に `DEVICE_LOCAL|HOST_VISIBLE` があるか」で判定し、**ベンダ文字列で分岐しない**。

---

## 4. Iris（参考: シェーダパック共存）
- `IrisShaders/Iris` を clone 済み（7.3MB）。
- **Nvidium 側に `sodiumCompat/IrisCheck.java` があり、Iris 検出時に機能を落とす実装がある**
  → AMD-Faster も **Iris 併用時のフォールバック**を初日から設計に入れる必要がある。

## 5. この章からの「AMD-Faster 実装方針」

| 論点 | 結論 |
|---|---|
| 対象MC | 1.21.11（Java 21, Fabric Loader 0.19.x, Fabric API 0.140.0+1.21.11）／**26.3 なら公式 Vulkan があるため設計が激変（要確認）** |
| バックエンド | **LWJGL の Vulkan + VMA + shaderc を JiJ**（VulkanMod 方式）。自前 JNI は不要 |
| ジオメトリ | **疎バッファ禁止**。巨大アリーナ + オフセット表（+ Vulkan なら descriptor indexing） |
| カル | **GPU 側カルを自前実装**（RADV は GFX11+ で NGG culling 既定 OFF。03章） |
| ドロー | region 単位 multi-draw → **全可視を 1〜2 draw に畳む**（indirect + count buffer + コンパクション） |
| 半透明 | **GPU ソート**（Nvidium の sorting network を AMD の wave32/LDS 制約に合わせて再設計） |
| 結合面 | Sodium `common/src/api` + 最小 Mixin。Sodium 内部への深い Mixin は避ける |
| Iris | 初日から併用フォールバックを設計 |
| 検証 | `RADV_DEBUG=psocachestats/nofastclears/nodcc`, `AMD_DEBUG=nowc/nonggc`, `MESA_VK_TRACE=rgp` |
