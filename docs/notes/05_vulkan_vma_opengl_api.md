# 05 — API 層（Vulkan / VMA / OpenGL / 拡張レジストリ）精読メモ

実際に取得・読んだもの:
- **Khronos Vulkan Guide**（`github.com/KhronosGroup/Vulkan-Guide` を clone、
  `chapters/*.adoc` を直接読んだ: `queues.adoc`, `memory_allocation.adoc`,
  `subgroups.adoc`, `common_pitfalls.adoc`, `pipeline_cache.adoc`, `synchronization.adoc`,
  `extensions/VK_KHR_draw_indirect_count.adoc`, `extensions/VK_EXT_descriptor_indexing.adoc`）
- **Vulkan-Docs**（`github.com/KhronosGroup/Vulkan-Docs` を clone、`xml/vk.xml` と
  `proposals/*.adoc` を参照）
- **VMA**（`github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator` を clone、
  `include/vk_mem_alloc.h` の定数・列挙を実測）
- **OpenGL Extension Registry**（`github.com/KhronosGroup/OpenGL-Registry` を clone、
  `extensions/ARB/*.txt`, `extensions/AMD/*.txt` を参照）
- **AMDVLK / PAL / LLPC**（`GPUOpen-Drivers/{pal,xgl,llpc}` を clone 済み — 90MB/12MB/27MB。
  今回は構造把握まで。**深掘りは未実施**）

---

## 1. Vulkan Guide — queues.adoc

- 「**VkQueue に投入された command buffer は開始順は保たれるが、その後は独立に進み
  完了順は入れ替わりうる**」。異なる queue 同士は `VkSemaphore` で明示同期しない限り無順序。
- **1 つの VkQueue への submit は 1 スレッドからしかできない**（異なる Queue なら並行可）。
- **VkQueue とハードウェアの対応は実装定義**。Vulkan は対応を露出しない。
- 原文の注記（重要）:
  > **Not all applications will require or benefit from multiple queues.**
  > It is reasonable for an application to have a single "universal" graphics supported queue.
- `VK_QUEUE_TRANSFER_BIT` のみを持つ family は **DMA で host↔device を非同期転送するため**のもの。
- **`GRAPHICS` と `COMPUTE` は常に `TRANSFER` コマンドを暗黙に受け付ける。**
- 各 API の「Supported Queue Types」は `xml/vk.xml` から生成される。
- 参照リンクとして **AMD の "Leveraging asynchronous queues for concurrent execution"** と
  NVIDIA の "Asynchronous Compute" を明示的に挙げている。

**AMD-Faster への含意**: 「async compute が常に得」とは限らない。
**queue family を列挙して、専用 compute/transfer family が実際に別 family として
存在するときだけ非同期パスを有効化する**こと。AMD dGPU は通常
`graphics+compute+transfer` の統合 family と **`compute` 専用**、**`transfer` 専用** family を持つ。

## 2. Vulkan Guide — memory_allocation.adoc（★APU の扱いの根拠）

- **Sub-allocation は first-class**。**`maxMemoryAllocationCount` という上限がある**ため、
  大きな確保を自分で管理すべき。OS/ドライバ層の確保・解放は非常に遅い。
- **discrete と UMA（integrated）の違い**:
  > UMA systems share the memory between the device and host which is advertised with a
  > **`VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT`** combination.
  > The disadvantage is that system memory has to be shared with the GPU which requires
  > being cautious of **memory pressure**. The main advantage is that **there is no need to
  > create a staging buffer and the transfer overhead is greatly reduced.**
- **`LAZILY_ALLOCATED_BIT`** は tile-based（ほぼ全モバイルGPU）で実メモリを持たない。
  G-buffer / depth / MSAA をタイルメモリに置ける。
  （→ **ハンドヘルド AMD APU で MSAA を使う場合の選択肢**）

**AMD-Faster への含意（確定方針）**:
```
if (memoryTypes に DEVICE_LOCAL|HOST_VISIBLE の大きなヒープがある)  // APU or ReBAR
    → アリーナを直接 persistent-map して書く（staging copy 無し）
else
    → staging ring + copy（Sodium 現行方式）
```
これは 01章（RDNA Perf Guide §5）と 03章（RADV の `sam`/`nosam`, `dmashaders`）とも一致する。
**APU（Vega 8 / 680M / 780M / 890M）を忘れずに、というのがこの分岐そのもの。**

## 3. Vulkan Guide — subgroups.adoc（★wave32/64 対策）

- subgroup は「効率的に同期・データ共有できるシェーダ呼び出しの集合」。compute では
  **local workgroup が subgroup のスーパーセット**。
- **subgroup size は実装によって動的になりうる**。大きな subgroup を分割したり、
  小さいものを inactive 付きの大きい subgroup として表現する実装もある。
- **`VK_EXT_subgroup_size_control` は Vulkan 1.3 でコア化**。
  複数 subgroup size を露出できる。未対応なら「1種類の subgroup size しか無い」ことを意味する。
- 保証されているもの:
  - `supportedStages` に `COMPUTE_BIT`（compute queue がある限り）
  - `supportedOperations` に `BASIC_BIT`（graphics or compute queue がある限り）
- `VK_KHR_shader_subgroup_extended_types`（1.2 コア）: 8/16/64bit int, 16bit float の subgroup op。
- `VK_EXT_shader_subgroup_ballot` / `_vote` は **Vulkan 1.1 以降では使う必要なし**。

**AMD-Faster への含意**: **`gl_SubgroupSize` を定数としてハードコードしない**。
カル/ソート compute は `gl_SubgroupSize` を実行時に読むか、`subgroupSizeControl` で
明示指定する（AMD では wave32/wave64 の両方が露出されることがある）。

## 4. Vulkan Guide — common_pitfalls.adoc（★設計方針の裏付け）

- **command buffer の再利用は非推奨**:
  > Many early Vulkan tutorials recommended writing a command buffer once and re-using it.
  > **In practice re-use rarely has the advertised performance benefit** while incurring a
  > non-trivial development burden … **prefer to re-record fresh command buffers every frame.**
  > If performance is a problem, recording can be multithreaded as well as using secondary
  > command buffers for non-variable draw calls, like post processing.
- **パイプライン削減の凝った工夫は逆効果になりうる**（大規模エンジンでなければ
  ボトルネックにならない）。**pipeline cache を使え**。
- **swapchain image ごとにリソースを複製する必要はない**。uniform buffer 等は2つで足りる。
- **queue family あたりの複数 Queue は必ずしも有利でない**（ベンダのベストプラクティスを参照）。
- **Descriptor Sets: spec は最低4、多くのHWは最低8を保証** → 複数 set を使う理由は十分にある。

**AMD-Faster への含意**: 「command buffer をキャッシュして再利用する」最適化は
**やらない**（Minecraft は毎フレーム可視集合が変わる＝まさに文書が言うケース）。
**毎フレーム再記録 + マルチスレッド記録**を基本設計にする。

## 5. VK_KHR_draw_indirect_count（Vulkan 1.2 コア）

> `vkCmdDrawIndirect` allows you to invoke a `drawCount` number of draws, but the `drawCount`
> is needed at record time. **The new `vkCmdDrawIndirectCount` call allows the `drawCount` to
> also be in a `VkBuffer`** … decided when the draw call is executed.

- `VkPhysicalDeviceVulkan12Features::drawIndirectCount` が true なら使える。
- → **GPU 側カル → count buffer → 1 draw** という AMD-Faster の中核が、
  **Vulkan 1.2 コア機能だけで**実現できる（拡張不要）。
- OpenGL 側の対応物は **`GL_ARB_indirect_parameters`**（下記 §8）。

## 6. VK_EXT_descriptor_indexing（Vulkan 1.2 コア）

機能は分割されている（部分対応がありうる）:
- **Update after bind**: `descriptorBinding*UpdateAfterBind` を照会。
  使うには `VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT` +
  `VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT` +
  binding ごとの `VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT` の3点が必要。
- **Partially bound**: `descriptorBindingPartiallyBound` +
  `VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT`。配列を全部埋めなくてよい
  （**未バインドの index を参照しない責任はアプリ側**）。
- **Dynamic Indexing**: `shader*ArrayDynamicIndexing`。「dynamically uniform」な index
  （1 draw 内 / 1 workgroup 内で同一）。
- **Dynamic Non-Uniform Indexing**: `shader*ArrayNonUniformIndexing` +
  SPIR-V の `NonUniform` decoration / GLSL の **`nonuniformEXT()`**。

**AMD-Faster への含意（★Nvidium の代替）**:
> Nvidium は `GL_NV_shader_buffer_load`（GPU アドレス）+ `GL_ARB_sparse_buffer` で
> 全チャンクを 1 draw に畳んでいる（04章 §3.2）。**AMD にはこれが無い。**
> Vulkan なら **`descriptor_indexing`（partially bound + nonuniformEXT）** で
> 「テクスチャ/バッファ配列を index で引く」bindless 相当ができる。
> これが **AMD 版 Nvidium の正しいジオメトリアドレッシング**。

## 7. VMA（AMD 純正）— 実測した重要値

`include/vk_mem_alloc.h` から:
```c
#define VMA_DEFAULT_LARGE_HEAP_BLOCK_SIZE (256ULL * 1024 * 1024)   // ← 256 MiB
m_PreferredLargeHeapBlockSize = pCreateInfo->preferredLargeHeapBlockSize
                              ? ... : VMA_DEFAULT_LARGE_HEAP_BLOCK_SIZE;
```
→ **AMD の Performance Guide「ヒープは 256MB 推奨」と VMA の既定値が一致**。
  何も設定しなければ AMD 推奨どおりになる。

`VmaAllocatorCreateFlagBits`（抜粋）:
- `VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT`（**VK_EXT_memory_budget** 連携）
- **`VMA_ALLOCATOR_CREATE_AMD_DEVICE_COHERENT_MEMORY_BIT`**（AMD 固有拡張 `VK_AMD_device_coherent_memory`）
- `VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT`
- `VMA_ALLOCATOR_CREATE_EXT_MEMORY_PRIORITY_BIT`
- `VMA_ALLOCATOR_CREATE_KHR_DEDICATED_ALLOCATION_BIT` / `_KHR_BIND_MEMORY2_BIT`

`VmaMemoryUsage`:
- 旧: `GPU_ONLY`, `CPU_ONLY`, `CPU_TO_GPU`, `GPU_TO_CPU`, `CPU_COPY`, `GPU_LAZILY_ALLOCATED`
- **新: `AUTO`, `AUTO_PREFER_DEVICE`, `AUTO_PREFER_HOST`**（推奨。ヘッダ内に
  「map する場合は `VMA_ALLOCATION_CREATE_MAPPED_BIT` か `vmaMapMemory` が必要」の注記あり）
- `VMA_ALLOCATION_CREATE_MAPPED_BIT`（常時マップ）は
  **`VMA_POOL_CREATE_LINEAR_ALGORITHM_BIT` の custom pool でのみ許可**される箇所がある（linear allocator 用）。

**AMD-Faster への含意**:
- **VMA を LWJGL 経由で使う**（VulkanMod が実証済み。`org.lwjgl.util.vma.Vma`）。
- `VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT` を有効化し、
  **`vmaGetHeapBudgets` で「80%ルール」を自前で強制**（01章 §5）。
- アップロード用は `AUTO_PREFER_HOST` + `MAPPED_BIT` + **linear pool（ring buffer）**。
  幾何データ用は `AUTO_PREFER_DEVICE`。
- **`VK_AMD_device_coherent_memory`** は APU/ReBAR で coherent host メモリを扱う拡張。
  対応していれば `AMD_DEVICE_COHERENT_MEMORY_BIT` を立てる（＝APU 最適化の一手）。

## 8. OpenGL 拡張レジストリ（KhronosGroup/OpenGL-Registry を clone して確認）

存在を確認したファイル:
```
extensions/ARB/ARB_buffer_storage.txt
extensions/ARB/ARB_multi_draw_indirect.txt
extensions/ARB/ARB_indirect_parameters.txt
extensions/ARB/ARB_shader_draw_parameters.txt
extensions/ARB/ARB_bindless_texture.txt
extensions/ARB/ARB_shader_image_load_store.txt
extensions/AMD/AMD_pinned_memory.txt
extensions/AMD/AMD_multi_draw_indirect.txt
extensions/AMD/AMD_vertex_shader_tessellator.txt
extensions/AMD/AMD_texture_texture4.txt
extensions/AMD/AMD_transform_feedback3_lines_triangles.txt / 4.txt
```

### ARB_buffer_storage（★★AMD 由来）
- **Contact: Graham Sellers (graham.sellers 'at' amd.com)** ← **AMD の人が書いた拡張**。
  つまり Sodium が採用している persistent-mapped 方式は **AMD 発祥の設計**。
- Issue 1 の解決（原文）:
  > **The application must both create the data store with MAP_PERSISTENT_BIT set _and_
  > map it with MAP_PERSISTENT_BIT set in `<access>`.** Did the same for coherency too.
- Issue 2（原文）:
  > Most applications get `<usage>` wrong and they're only hints anyway.
  > **The flags are hard and fast rules that must be followed.** … to allow the
  > implementation to **not have to second guess the application and to perform less tracking**.

**AMD-Faster への含意**: `glBufferData` の usage ヒントではなく
**`glBufferStorage` のフラグで意図を正確に伝える**方が AMD ドライバは速い。
Sodium の `MappedStagingBuffer` は既にこれを使っている（04章 §1.3）。

### ARB_indirect_parameters（＝ `vkCmdDrawIndirectCount` の OpenGL 版）
- 原文: GL 4.3 の `ARB_multi_draw_indirect` は「indirect draw のパラメータ群をバッファに
  置いて 1 API call で発行」できるが、**draw 数は記録時に必要**。
- この拡張は **`GL_PARAMETER_BUFFER` というターゲット**を導入し、
  **MultiDrawArraysIndirect / MultiDrawElementsIndirect の「一部パラメータ」を
  バッファから読ませる**。
- → **OpenGL バックエンドのままでも「GPU 側カル → count buffer → multi draw indirect」が可能。**
  **Vulkan 化しなくても AMD-Faster の中核機能は OpenGL で実装できる**（＝段階的移行が可能）。

### AMD_pinned_memory
- 原文:
  > This extension defines an interface that allows **improved control of the physical memory
  > used by the graphics device**. It allows an **existing page of system memory allocated by
  > the application** to be used as memory directly accessible to the graphics processor.
  > One example … **avoid an explicit synchronous copy** … it is possible to directly draw
  > from a system memory copy of a video image.
- `New Procedures and Functions: None`（＝**`GL_EXTERNAL_VIRTUAL_MEMORY_BUFFER_AMD` という
  バッファターゲットを追加するだけ**）。

**AMD-Faster への含意**: **JVM のヒープ外メモリ（`MemoryUtil.nmemAlloc` / FFM Arena）を
そのまま GL バッファとして pin できる**。
→ **メッシュビルドの出力を「JVM 側 off-heap → コピー無しで GPU 直読み」**にできる可能性がある。
Windows の AMD OpenGL ドライバ限定なので **機能検出必須**（`GL_AMD_pinned_memory`）。
**LWJGL の `MemoryUtil` 領域を pin する際は、確保を LWJGL のアロケータに固定する必要がある**
（GC が移動しない＝ direct buffer なので移動しないが、**解放タイミングの管理は自前**）。

### AMD_multi_draw_indirect
- 原文: 「**1回の関数呼び出しで複数の draw を発行**できる。大量の draw コマンドを
  **サーバメモリ（buffer object）に組み立てて、単一の関数呼び出しでディスパッチ**できる」
  → `MultiDrawArraysIndirectAMD` / `MultiDrawElementsIndirectAMD`。
-ARB 版（`ARB_multi_draw_indirect`）の前身。**ARB 版を使えばよい**（AMD 固有は不要）。

## 9. vk.xml から確認した AMD 固有 Vulkan 拡張（実在するもの）

```
VK_AMD_anti_lag                       VK_AMD_buffer_marker
VK_AMD_device_coherent_memory         VK_AMD_display_native_hdr
VK_AMD_draw_indirect_count            VK_AMD_gcn_shader
VK_AMD_gpa_interface                  VK_AMD_gpu_shader_half_float
VK_AMD_gpu_shader_int16               VK_AMD_memory_overallocation_behavior
VK_AMD_mixed_attachment_samples       VK_AMD_negative_viewport_height
VK_AMD_pipeline_compiler_control      VK_AMD_rasterization_order
VK_AMD_shader_ballot                  VK_AMD_shader_core_properties
VK_AMD_shader_core_properties2        VK_AMD_shader_early_and_late_fragment_tests
VK_AMD_shader_explicit_vertex_parameter  VK_AMD_shader_fragment_mask
VK_AMD_shader_image_load_store_lod    VK_AMD_shader_info
VK_AMD_shader_trinary_minmax          VK_AMD_texture_gather_bias_lod
```

**AMD-Faster で使いどころがあるもの**:
| 拡張 | 用途 |
|---|---|
| **`VK_AMD_shader_core_properties` / `2`** | **shader engine 数・CU 数・wave/wgp 情報を取得 → 世代別チューニングの判定に使う**（ベンダ文字列より正確） |
| **`VK_AMD_shader_early_and_late_fragment_tests`** | **early+late の深度テストを両方行う実行モード**。RDNA4 の HiZ 問題（03章 §5）への対抗策として有効 |
| `VK_AMD_shader_info` | **シェーダの ISA / resource usage を実行時に取得**（RGA が無い環境での VGPR 調査） |
| `VK_AMD_device_coherent_memory` | APU/ReBAR での coherent host メモリ |
| `VK_AMD_pipeline_compiler_control` | パイプラインコンパイルの制御（スタッタ低減） |
| `VK_AMD_anti_lag` | 入力遅延低減（Minecraft では低優先度） |
| `VK_AMD_memory_overallocation_behavior` | VRAM 超過時の挙動制御（80%ルールと併用） |
| `VK_AMD_buffer_marker` | **GPU 側にマーカーを書いて TDR/ハング位置を特定**（01章 §14 の buffer marker そのもの） |

### VK_AMD_shader_early_and_late_fragment_tests（`proposals/` を読んだ）
- 問題（原文要約）: 多くのデバイスは「FS が深度/ステンシルやストレージに書かない」場合に
  early fragment test を使える。`EarlyFragmentTests` 実行モードは**FS からの深度書き込みを禁止**してしまう。
  一部実装は **early と late の両方でテストでき、early で大半を discard し late で精密に discard** できる。
  `GL_ARB_conservative_depth` はこれを深度書き込み下でも可能にしたが、
  **ストレージ書き込みがある場合は仕様上の予測可能性要件のため最適化できない**。
- 提案: **SPIR-V の新しい実行モード**（`EarlyAndLateFragmentTestsAMD` 相当）＋
  `VkPhysicalDeviceShaderEarlyAndLateFragmentTestsFeaturesAMD::shaderEarlyAndLateFragmentTests`。
  ステンシル参照値の書き込みにも同様の最適化を提供。

**AMD-Faster への含意**: Minecraft の地形は `discard`（カットアウト）や
Fog/Overlay の書き込みがあるので **early-Z が効きにくい**。
**この拡張が使えるなら地形 FS に適用する**、使えないなら **Z pre-pass を入れる**
（RDNA4 では特に有効。03章 §5 の `radv_gfx12_hiz_wa=full` が既定で early-Z を潰しているため）。

## 10. その他の Vulkan-Docs proposals（存在確認済み・AMD-Faster 関連）

```
VK_EXT_mesh_shader.adoc            VK_EXT_descriptor_heap.adoc
VK_EXT_descriptor_buffer.adoc      VK_EXT_device_generated_commands.adoc
VK_EXT_graphics_pipeline_library.adoc  VK_EXT_shader_object.adoc
VK_EXT_dynamic_rendering_unused_attachments.adoc  VK_EXT_host_image_copy.adoc
VK_EXT_present_timing.adoc         VK_EXT_calibrated_timestamps.adoc
VK_EXT_frame_boundary.adoc         VK_EXT_primitive_restart_index.adoc
VK_EXT_depth_clamp_control.adoc    VK_EXT_depth_bias_control.adoc
VK_AMDX_shader_enqueue.adoc        VK_AMDX_dense_geometry_format.adoc
VK_EXT_ray_tracing_invocation_reorder.adoc  VK_EXT_shader_module_identifier.adoc
```
- **`VK_EXT_descriptor_heap`**: bindless の本命（RADV も実装中。03章の `RADV_DEBUG=noheap`）。
- **`VK_EXT_device_generated_commands`**: GPU 側でコマンドを生成（＝Nvidium の
  bindless multi-draw-indirect の Vulkan 版に相当しうる）。
- **`VK_AMDX_shader_enqueue`**: **AMD 発の task/mesh enqueue（work graph 的なもの）**。
  mesh shader パイプラインを GPU 側から起動できる → **AMD 版 Nvidium の候補**。
- **`VK_EXT_mesh_shader`**: `GL_NV_mesh_shader`（Nvidium が要求）の**クロスベンダ版**。
  **AMD-Faster は `VK_EXT_mesh_shader` の有無で mesh shader パスを分岐**すべき。
- **`VK_EXT_frame_boundary` / `VK_EXT_present_timing` / `VK_EXT_calibrated_timestamps`**:
  **Mod 内蔵ベンチ/フレームタイム計測**に使える。

## 11. AMDVLK / PAL / LLPC（構造把握のみ）

- `GPUOpen-Drivers/pal`（90MB, blobless）— **Platform Abstraction Layer**。
  「どの API 呼び出しがどの HW レジスタ/コマンドに落ちるか」の一次情報。
- `GPUOpen-Drivers/xgl`（12MB）— **Vulkan ICD（API 層）**。
- `GPUOpen-Drivers/llpc`（27MB）— **LLPC シェーダコンパイラ**。
- **今回のスコープでは精読していない**（次タスク）。
  読むなら `pal/inc/core/`（HW 世代別 caps）と `llpc/lib/`（パス）が入口。
