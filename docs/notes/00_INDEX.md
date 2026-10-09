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
| 12 | [`12_stage4_5_vk_and_culling.md`](12_stage4_5_vk_and_culling.md) | **Stage 4–5 実装記録**。Vulkan の「判断」を LWJGL 型なしに分離した理由、テストが直した実装バグ 2 件（large BAR カードでメッシュが 256 MiB の不可視ヒープに行く、ReBAR 判定がシステム RAM を数えて false になる）、LWJGL の呼び出し形で CI に落ちた 3 件、**root signature 13 DWORD 予算がシェーダ設計を変えた話**、2 パスカリング、そしてシェーダと Java のバインディング整合テスト | 実装＋ CI |
| 13 | [`13_light_and_entity_batching.md`](13_light_and_entity_batching.md) | **ライティングとエンティティバッチングの設計**。`LightValue.pack` を vanilla の `LightTexture.pack` と同じ配置にして平均を整数加算 1 回にした理由、18³ ライトキャッシュ、遮蔽サンプルを平均に入れない判断、同一モデルのエンティティを 1 個のインスタンスドローにまとめる条件 | 設計 |
| 14 | [`14_stage8_11_mc_light_shaders.md`](14_stage8_11_mc_light_shaders.md) | **Stage 8〜11 実装記録**。`net.amdfaster.mc` を唯一 Minecraft 型に触るパッケージにした理由、`BlockKeys` のビット配置と範囲外を例外にする判断、`SectionCoords` をシフト／マスクで書く理由、**ライトを頂点単位にしたときに頂点 dedup キーを広げないと継ぎ目が走査順に依存する話**、角の対応表を「中身ではなく不変条件で」テストする理由、`discard` を使わない判断、**VK_FORMAT をリテラルで書いて 3 つとも外して CI に捕まった話** | 実装＋ CI |
| 15 | [`15_entity_scaling.md`](15_entity_scaling.md) | **エンティティ数を無関係にする実装記録**。確保ゼロの `EntityBuffer` とオープンアドレス法の `EntityGrid`、**greedy バッチャが到着順で 20 → 1000 と 50 倍ぶれる実測表**とそれを固定するテスト、ソートせず O(n) で効かせるバジェット（距離 2 乗の 64 バンド）、write-combined メモリへの昇順書き込みとパディングを毎回ゼロで書く理由 | 実装＋ CI |
| 16 | [`16_bottleneck_survey.md`](16_bottleneck_survey.md) | **ボトルネック調査と最も重い 2 箇所の実装**。第三者実測による重さ順位（**ブロック 1 個でリビルド 50 回**、牛 1000 頭 24→140 FPS）、**65.1% のブロックは 1 セクションしか無効化しない**という実測分布と dirty セットの統合、ライトだけを別ストリームに書いて meshing と AO を丸ごと飛ばす話、そして既存オクルージョンシェーダで見つかった **可視の幾何を消す 3 バグ**（max reduction／中心 1 テクセル／floor ピラミッド、網羅検証で取りこぼし 0 vs 2061） | arXiv / GPU Zen / Granite / 第三者実測 ＋ 実装＋ CI |
| 17 | [`17_hot_paths_and_backface.md`](17_hot_paths_and_backface.md) | **ホットパス高速化とフレーム全体のスキップ**。`selectLevel` の `log` 2 回 → ビットカウント 1 回（密な掃引で不一致 0、かつ `log` 版は「たまたま正しい」だけだった話）、frustum 判定の **18 の予測不能分岐 → 0**（40 万組でビット一致）、**カメラが生ビット同一ならカリング段階を丸ごと飛ばす** `CullReuse`（`0.0f == -0.0f` の罠、ライトのみリビルドとモブの移動を意図的に無効化しない理由）、そして**メッシュレット単位のバックフェースカリング**（向き 3 bit vs 空き 2 bit、root signature がセット単位課金なのでタダだった話、宣言を sticky にすると順序依存バグになる話） | 実装＋ CI |
| 18 | [`18_wave_atomics_and_gpu_packing.md`](18_wave_atomics_and_gpu_packing.md) | **ウェーブ協調アトミックと、GPU へ積むコードの欠落**。カリングの `atomicAdd` を **レーン毎からウェーブ毎へ**（RE Engine 実測 0.543→0.051 ms、DXC wiki の 2 つ目の利点は競合低減でなく出力の局所性）、ballot より前の `return` が禁止な理由（subgroup 命令はアクティブレーンしか見ない → 末尾の幾何が消える）、`lane 0` ではなく `subgroupElect()`、そして **Aokana §1 の自チャンク問題**が `clip.w <= 0` ガードで閉じていることの検証。さらに監査で残っていた項目が想定より広く、**cull データと頂点ストリームの両方に書き手が無かった**話と、その中で見つけた**セクション原点を uint→float で読んでいた**バグ（原点 −32 が **4.29e9** になり地形が消える、エラーは出ない） | DXC wiki / Drobot SIGGRAPH 2017 / RE Engine / Aokana ＋ 実装＋ CI |
| 19 | [`19_amd_native_plan.md`](19_amd_native_plan.md) | **AMD ネイティブ実装プランと、最優先項目の実装**。8 項目を「AMD 固有か NVIDIA でも可能か」で分類し、非同期コンピュート（AMD の ACE がハードウェアで独立）とスカラーユニット（**NVIDIA にスカラーパスは無い**）、そして **RDNA3 で LDS レイテンシが NVIDIA を逆転した**ことを根拠付きで整理。**メッシュシェーダの task shader は AMD に無くこの一点では NVIDIA が上**という不利も明記。実装した項目 1 では `LevelBlockSampler` が**ボクセル毎に `new BlockPos` を確保し `getBlockState` を呼んでいた**（1 セクション 2.5 万回）のを修正し、その過程で**原点が sentinel と衝突する**バグ、**direct-mapped テーブルが 4096 ではヒット率 23%** になる問題、**CI が失敗テスト名を一切出さない**問題（`reportTestFailures` を追加して解決）を発見 | RDNA 資料 / NVIDIA 公式 / 実装＋ CI |
| 20 | [`20_backlog_100.md`](20_backlog_100.md) | **実装バックログ 100 項目の状態台帳**。ユーザー提供の候補 100 件を grep で実際の状態と突き合わせ、**完了 21／部分 25／未 33／対象外 21** に分類。対象外のうち 20 件はサーバー側で、クライアント側 Mod という制約から来る（勝手にやらずユーザーの判断を仰ぐ項目として明記）。併せて**項目 100（自動ベンチマーク）を最優先で実装** — 60fps 平均に 200ms のカクつき 10 回を混ぜると **平均 56fps・p95 も p99 も 16.7ms で何も見えず、1% Low だけが 5fps と暴く**ことをモデルで確認し、その定義をテストで固定した | 実装＋ CI |

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
- **Stage 4–5**: `net.amdfaster.vk` が Vulkan の**判断**を LWJGL 型なしで持つ（メモリタイプ、
  キューファミリ、アダプタ選択、root signature 予算、per-frame リング）ので、GPU なしで
  4 種の実在トポロジをテストできる。`VkContext` は呼ぶだけ。カリングは 2 パス
  （frustum で詰める → 前フレーム Hi-Z で落とす）。詳細は `12`。
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
