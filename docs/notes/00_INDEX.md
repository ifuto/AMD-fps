# AMD-Faster / 調査メモ INDEX

**目的**: Nvidium の AMD 版（＝AMD GPU で最も速い Minecraft レンダリング）を作るための事前調査。
**状態**: **ドキュメント精読フェーズ完了。Mod のコードはまだ書いていない。**
**調査日**: 2026-10-07

---

## 1. メモ一覧

| # | ファイル | 内容 | 一次情報 |
|---|---|---|---|
| 01 | [`01_gpuopen_rdna_performance_guide.md`](01_gpuopen_rdna_performance_guide.md) | **GPUOpen RDNA Performance Guide 全読**。descriptor 13 DWORD 予算、fast clear、ReBAR、async/copy queue、LDS 32bank、compute 作法 | gpuopen.com（全6チャンク取得） |
| 02 | [`02_rdna_architecture_wgp.md`](02_rdna_architecture_wgp.md) | **RDNA アーキテクチャ資料（PDF 全4チャンク読了）**。WGP/SIMD32、1024 VGPR、occupancy、命令レイテンシ、L2 クライアント、DCC 圧縮表 | `gpuopen.com/download/RDNA_Architecture_public.pdf` |
| 03 | [`03_mesa_radv_aco_radeonsi.md`](03_mesa_radv_aco_radeonsi.md) | **RADV / ACO / RadeonSI の全チューニング knob**。`RADV_PERFTEST=sam/nosam`, `RADV_DEBUG=psocachestats/nofastclears`, **RDNA4 の HiZ ワークアラウンド**, `AMD_DEBUG=nowc/w32ps` | docs.mesa3d.org/envvars.html（chunk 0,7,8,9,10） |
| 04 | [`04_mods_sodium_vulkanmod_nvidium.md`](04_mods_sodium_vulkanmod_nvidium.md) | **Sodium 0.8.12(1.21.11) / VulkanMod 0.6.8 / Nvidium のソース精読**。persistent-mapped アップロード、region 単位 multi-draw、LWJGL natives の JiJ 配布、Nvidium の sparse バッファが AMD で使えない根拠 | GitHub clone（実ソース） |
| 05 | [`05_vulkan_vma_opengl_api.md`](05_vulkan_vma_opengl_api.md) | **Vulkan Guide / vk.xml / VMA / OpenGL 拡張レジストリ**。UMA(APU) 判定、subgroup size 可変、`drawIndirectCount`、`descriptor_indexing`、VMA 256MiB 既定、`AMD_pinned_memory`、`VK_AMD_*` 一覧 | GitHub clone（KhronosGroup, GPUOpen） |
| 06 | [`06_gpu_queues_preemption_barriers.md`](06_gpu_queues_preemption_barriers.md) | **Breaking Down Barriers / GPUOpen async queues / MES スケジューラ仕様**。split barrier、プリエンプション粒度、**AMD は複数 graphics queue が無意味**、**fence ≤9/frame**、**command buffer は 1〜2ms** | therealmjp.github.io（直接取得）＋ GPUOpen（抜粋） |
| 07 | [`07_amdfaster_design.md`](07_amdfaster_design.md) | **設計メモ**: アーキテクチャ、世代別マトリクス、機能優先順位、Modrinth 配布、計測計画、リスク、マイルストーン | 01〜06 の統合 |
| 08 | [`08_jvm_lwjgl_fabric.md`](08_jvm_lwjgl_fabric.md) | **JVM/LWJGL/Fabric**。Java 21 では FFM が使えない、Sodium の `Unsafe.copyMemory` と ASM 実行時生成、LWJGL 版本、GC ルール | GitHub clone（実ソース）＋ JEP 確認 |
| 09 | [`09_research_papers_forums.md`](09_research_papers_forums.md) | **海外論文・フォーラム調査**。Aokana（GPU 駆動ボクセル）、Ubisoft GPU-driven pipeline、two-pass occlusion culling、meshlet 研究3本、RE Engine 実測、voxel エンジン実務、GPU ソート、JVM/GC。各項に AMD-Faster への適用つき | arXiv / ACM / JCGT / CGF / REAC / 個人技術記事 |
| 10 | [`10_primary_source_verifications.md`](10_primary_source_verifications.md) | **一次ソース再検証**。`fetched_content/` に落ちた RDNA Performance Guide 全文と突き合わせ、**自分のメモの誤り1件（workgroup size）を訂正**し、HOST_VISIBLE 書き込み制約など実装直結の事実を追加 | `fetched_content/`（fetch.yml が取得） |
| 11 | [`11_stage2_meshing.md`](11_stage2_meshing.md) | **Stage 2 実装記録**。`net.amdfaster.mesh` の設計と確定数値（meshlet 62 quad / 8bit index / 4 バイト packed AABB / 6 バケット分割）、**CI が捕まえた実バグ 2 件**（axis に ordinal を入れていた、巻き順テーブルの行入替）、そしてテストで判明した「頂点 dedup は効かない」事実 | 実装＋ CI |

## 2. 最重要の発見（3つだけ挙げるなら）

1. **Minecraft の版体系が変わった。1.21.11 が 1.x の最後で、次は 26.1（Java 25 必須）。**
   しかも **MC 26.x には Mojang 公式の Vulkan バックエンド（RenderPearl）が入っており、
   Sodium 0.9.x は既にそれを使っている**（`04` §0、実ソースの import で確認）。
   → **対象バージョンの決定が設計を根本から変える。**
2. **Nvidium の中核（`GL_ARB_sparse_buffer` + `GL_NV_shader_buffer_load`）は AMD では使えない。**
   GPUOpen が sparse/tiled resource を明確に非推奨としている（`01` §5, `04` §3.2）。
   → AMD 版は **巨大アリーナ + `descriptor_indexing`/bindless** で作る。
3. **AMD の性能は「世代」で分岐する。**
   APU(Vega) は copy queue で L2 フラッシュ、RDNA1 以降は無い。
   GFX11+ は NGG culling 既定 OFF。RDNA4 は HiZ 不具合で early-Z が既定で潰れている。
   → **世代別プロファイルが必須**（`02` §12, `03` §2, §5）。
4. **★Sodium のライセンスは LGPL ではなく `PolyForm Shield License 1.0.0` だった**
   （0.8.12 / dev とも `LICENSE.md` を実確認。VulkanMod / Nvidium / Iris は LGPL-3.0）。
   PolyForm Shield は「**Sodium の実質的な代替品（practical substitute）を作ることを禁止**」する
   **Noncompete 条項**を持つ。
   → **AMD-Faster は「Sodium の置き換え」ではなく「Sodium の上で動く add-on」
   （＝Nvidium と同じ立ち位置）として設計しなければならない。** 詳細は `07` §7.1。

## 3. 実際に取得できた／できなかった資料（正直な記録）

### 取得して読めたもの
- `gpuopen.com/learn/rdna-performance-guide/`（全6チャンク）
- `gpuopen.com/download/RDMA_Architecture_public.pdf`（全4チャンク）
- `gpuopen.com/rdna/`, `/rdna2/`, `/news/amd-rdna-3-5-isa/`
- `docs.mesa3d.org/envvars.html`（RADV / RadeonSI / ACO / r600 / r300 の全セクション）
- GitHub clone: **Sodium**（dev + tag `mc1.21.11-0.8.12`）、**VulkanMod**、**Nvidium**、**Iris**、
  **LWJGL3**、**Fabric API**、**Vulkan-Guide**、**Vulkan-Docs**、**OpenGL-Registry**、
  **VulkanMemoryAllocator**、**GPUOpen-Drivers/{pal,xgl,llpc}**
- `meta.fabricmc.net/v2/versions/game`（版一覧）

### 取得できなかったもの（理由つき）
- **AMD 公式 ISA 参照ガイド本体**（RDNA1/2/3/3.5/4）:
  `www.amd.com/content/dam/.../rdna35_instruction_set_architecture.pdf` は
  **`docs.amd.com` のサインイン要求にリダイレクト**され本文を取得できなかった。
  → 代替として **RDNA アーキテクチャ資料（PDF）** と **Mesa/RADV の knob** から
  ハードウェア事実を収集した（02, 03 章）。**ISA 命令表・エンコーディングは未取得**。
- **`gpuopen.com/learn/breaking-down-barriers-*`**: **404（存在しない）**。
  "Breaking Down Barriers" は **Matt Pettineo 氏の連載**（Khronos Vulkan Guide が
  公式に参照リンクとして言及）。Part 3/4 は直接取得、Part 1/2/5/6 は抜粋のみ（`06` 冒頭に明記）。
- **Mesa のソースコード**: **GitHub にミラーが存在しない**
  （`api.github.com/repos/mesa3d/mesa` → `NotFound`、`orgs/mesa3d/repos` は `.github` のみ）。
  GitLab はこの環境から到達不可。**ドキュメントサイト経由に限定**。
- **`CaffeineMC/Nvidium` / `FabricMC/Nvidium`**: **404**。現行は `MCRcortex/nvidium`（tag 無し、対象 MC 1.21）。
- **GPUOpen Performance Guides の他ページ**（GCN3 guide, Vulkan barriers explained 等）: 未取得。

## 4. 現在の状況（2026-10-07 更新）

確定した前提:
- **対象 MC = 1.21.11**（Java 21、Sodium 0.8.12 の系列）。
- **バックエンド = Vulkan 優先**。
- **独立レンダラ方式**（VulkanMod 方式。Sodium に依存しない。Sodium は PolyForm Shield なので
  ソースの派生は不可 — `07` §7.1）。

実装は **Stage 1（計測基盤）と Stage 2（セクションメッシング）が完了**。

- **Stage 1**: `net.amdfaster.platform.GpuReport` が起動時に `VkInstance` を作ってアダプタを列挙し、
  `AmdArchitecture`/`GpuIdentity` が世代を判定、`/amdfaster` と
  `config/amdfaster/gpu-report.json` で報告する。
- **Stage 2**: `net.amdfaster.mesh` がセクションを **6 朝向バケット × meshlet（62 quad / 124 三角形 /
  8bit ローカルインデックス / 4 バイト packed AABB）** に分割する。詳細は `11`。
- **Stage 3**: `net.amdfaster.mesh.voxel` の greedy mesher。`VoxelView` 抽象（Minecraft 型を含まない
  ので単体テスト可能）→ 矩形結合 → `Quad`。**16³ の中身が詰まったセクションが 1536 面 → 6 quad になる**
  ことをテストで固定。詳細は `11` §7。

CI（`./gradlew build`）が `compileJava` → `test` → `verifyJar` を回す。
`verifyJar` は「テストが 10 件以上走って全通過」「jar にエントリポイント・probe クラス・アイコンが
入っている」「`fabric.mod.json` の version が project version と一致」「`lwjgl-vulkan` が
jar-in-jar されている」を **assert** し、1 つでも外せばビルドを落とす
（レポートを print しても CI ワークフローが stdout をファイルにリダイレクトしていて見えないため）。
**意図的に壊して失敗が annotation として届くことも確認済み**。

残りの未取得資料: **RDNA3/4 ISA 参照ガイド**（`docs.amd.com` のサインイン要求）、
**GCN Performance Guide**。ただし **RDNA Performance Guide 全文は
`fetched_content/` に取得済み**で、`10` で照合済み。
