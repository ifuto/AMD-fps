# 01 — GPUOpen「RDNA Performance Guide」精読メモ

- 出典: https://gpuopen.com/learn/rdna-performance-guide/ （Originally posted: March 22, 2023 / RDNA3 追記版）
- 取得手段: 本文を全6チャンク取得して通読した（2026-10-07）。
- **AMD-Faster への直接の含意**を最後にまとめる。

---

## 1. ツール連携（冒頭の "Short on time?"）

> Many of our performance suggestions on this page are available through Microsoft PIX and Vulkan's Best Practice validation layer.

- **Vulkan SDK 1.2.189 以降の Best Practice validation layer に AMD 固有チェックが組み込まれている。**
  → AMD-Faster の開発時に `VK_LAYER_KHRONOS_validation` の best-practices チェックを有効化すれば、
  AMD のパフォーマンスアンチパターンを自動検出できる。DX12 側は PIX。**OpenGL には同等の仕組みが無い**
  （＝OpenGL バックエンドのままだと「AMD 公式の性能診断」が受けられない。Vulkan 化の動機の1つ）。

## 2. Command buffers

| 要点 | 内容 |
|---|---|
| マルチスレッド記録 | 低レベルAPIはアプリ側がスレッド化を担う。**ドライバは追加スレッドを生やさない** |
| 粒度 | 小さい command buffer を量産しない。**目安は 1 buffer あたり 10 draw/dispatch 以上** |
| allocator | thread-safe ではない。`スレッド数 × frames in flight` 以上用意。allocator は「最大サイズ」に成長するので用途ごとに使い回す |
| submit | submit ごとに CPU+GPU コスト。**sync 時とフレーム末尾だけ**にする。バッチ化推奨 |
| secondary / bundle | GPU 性能をむしろ落とす（CPU しか得しない）。使うなら 10 draw 以上 |
| Vulkan 固有 | `USAGE_ONE_TIME_SUBMIT` を使うと最適化が効く / `USAGE_SIMULTANEOUS_USE` は避ける / secondary 内での attachment clear は避ける |

**AMD-Faster への含意**: Minecraft は 1 フレームに数千〜数万の draw が出るが、Sodium の設計（後述 12章）は
「地形は 1〜2 本の indirect draw に畳む」方向なので、そもそも command buffer を大量に切らない設計と相性が良い。

## 3. PSO（Pipeline State Object）

- PSO コンパイルは重い。**ドライバはスレッドを生やさないのでアプリが並列化する**。
- JIT コンパイル回避：uber-shader → 後から特殊化。同一シェーダの並列コンパイルはロック競合で直列化しうる。
- **pipeline cache を使い、単一キャッシュを共有**してヒット率を最大化。
- PSO は VRAM を食うので数を絞る。作成後はシェーダ blob を捨ててよい。
- **パイプライン変更は「直前と似ているほど安い」。PSO のバインドはハードウェアコンテキストをロールしうる**
  → draw をパイプライン順にソートする。geometry / tessellation 有無でグループ分けする。
- **ドライバは redundant state tracking をしない**（= アプリが冗長 state 変更を排除する責務）。
- Vulkan: 変化しない dynamic state を有効化しない。

**AMD-Faster への含意**: Minecraft の「地形／雲／エンティティ／GUI」でパイプライン数が爆発しがち。
Sodium は program-per-blocklayer だが、AMD では **パイプライン切替コストが Nvidium 系の設計思想（= 少数パイプラインに畳む）
を正当化する**。パイプライン数 N を監視して HUD に出す価値がある（後述 RGP の counter）。

## 4. Barriers

- 数を減らす。read→read barrier は出さない（最初から正しい state に）。
- **まとめて 1 call で**（ドライバが冗長除去できる）。
- `GENERAL`/`COMMON` レイアウトは必要時のみ。
- state は最小限の組合せに。

## 5. Memory（★AMD 特化の最重要ポイント）

- **ヒープは大きめ（推奨 256MB）を取って sub-allocate**。VRAM 1GB 未満のカードは小さめ。
- 確保/解放は高いので**静的に**。動的リソースは sub-allocation。
- **使用 VRAM は総量の 80% 以下**に抑える（eviction 回避）。
  - 予算は `VK_EXT_memory_budget` で取得。**毎フレーム聞かない**。
- memory type は用途に合わせて選ぶ。システムメモリへの GPU アクセスは遅い。
- **VMA / D3D12MA を使え**（AMD 公式ライブラリ）。
- Tiled/Sparse リソースは**非推奨**（GPU/CPU 両面で性能コスト）。高トラフィックなリソース（RT/depth/UAV/BLAS）には絶対置かない。
- **★ReBAR（Resizable BAR）有効時**:
  > When user's system has ReBAR enabled, entire VRAM is accessible for the CPU (Vulkan: `DEVICE_LOCAL` + `HOST_VISIBLE` memory type, DX12: `GPU_UPLOAD` heap type).
  - CPU が直書き→GPU 直読みのバッファに使い、**2バッファ間コピーを消す**。
  - **`memcpy` か逐次書き込み専用。読み出しは厳禁**（`p[i] += v` のような隠れた読みにも注意）。
- Vulkan 固有: **AMD カードには CPU から見える device-local メモリがある**。constant buffer 更新に最適。
  ただし**使いすぎない（ドライバも使う）**。guaranteed allocation 数は必ず照会する。

**AMD-Faster への含意（大きい）**:
1. **APU（Vega 8 / 680M / 780M / 890M）では UMA なので `DEVICE_LOCAL|HOST_VISIBLE` が常時成立する**。
   → APU 向けには「アップロード専用リング」ではなく **1本の共有バッファへ直接書く**設計が最速になる。
   デスクトップ dGPU では ReBAR 有無で分岐が必要 → **起動時に memory type を列挙して feature flag 化**する。
2. Sodium の `GlBufferSegment` + persistent mapped + `glMapBufferRange` 設計は、
   実質これと同じ発想。**Vulkan バックエンドでは VMA + HOST_VISIBLE|DEVICE_LOCAL のリング + memcpy 一本**に落ちる。

## 6. Resources

- usage flag は必要最小限に（**多いと HW 最適化が無効化される**）。
- render target に `STORAGE`/`UAV` を足すと GCN3 以前の色圧縮が無効化。
- 非線形レイアウトを使う。MSAA は 4x 以下。
- **アップロードは image→image ではなく buffer→image コピー**。
- read only を明示すると圧縮が効く。
- `TYPELESS`/`MUTABLE` フォーマットを RT/depth/UAV に使わない。
- **mipmapped array の render target には圧縮が効かない。**
- **24bit depth は GCN/RDNA では 32bit と同コスト → 32bit を使え**。16bit depth が圧縮で有利とは限らない。
- **reversed-Z（near=1.0）**で深度分布を改善。near Z は可能な限り大きく。手元視点（プレイヤーの手）は depth partitioning。
- **頂点データはストリーム分割**。position 専用ストリームは depth-only pass を速くする。
- draw ごとに vertex stream を設定しない（CPU コスト）。offset で対応。
  あるいは **vertex pull**（バッファから VS 内で load）で stream をやめる。
- primitive restart index は旧世代でプリミティブレートを落とす。
- 疎なリソース読みはキャッシュ効率悪化。
- **RT には全チャンネル書け**（部分書き込みは read-modify-write で圧縮が無効化される）。
- G-Buffer のビットパックは**相関の高いビットを MSB、ノイズを LSB**。

**AMD-Faster への含意**: Minecraft の地形シェーダは RGBA 全書き込みなので圧縮面は問題なし。
ただし **Fog/Overlay のブレンドや、Sodium の translucency sorting pass** で部分書き込みが起きないか要確認。
**reversed-Z は Iris のシェーダパスと衝突しがち**なので、デフォルトは無効＋設定で明示が安全。

## 7. Descriptors（★★ AMD 固有の定量値）

- **root signature / descriptor set layout は 13 DWORD 未満に抑える**（溢れるとユーザーデータがメモリに spill）。
- 頻繁に変わる/低レイテンシが必要なものを**先頭**に。
- root descriptor / dynamic buffer は最小限（**検証が薄い。範囲外アクセスで GPU hang**）。
- stage flag を `ALL` にしない。
- descriptor set の更新回数を減らす。**一緒に使うリソースは同じ set に**。
- **bindless 化で CPU のバインドコストを削減できる**。
- draw は root signature / pipeline layout 順に並べる。
- static / immutable sampler はドライバがシェーダに埋め込む（メモリロード削減）。**同じ値の sampler は使い回す**。
- root signature / push constants には**毎 draw 変わる定数のみ**。
- descriptor のコピーは避ける。`vkUpdateDescriptorSet`（≒`CopyDescriptorsSimple`）は `vkUpdateDescriptorSetWithTemplate` より CPU キャッシュヒット率が良い。

**DWORD コスト内訳（Vulkan / AMD）:**

| 項目 | DWORD |
|---|---|
| Descriptor set | 1 |
| Dynamic buffer (robust buffer access **OFF**) | 2 |
| Dynamic buffer (robust buffer access **ON**) | 4 |
| Push constant | **4バイトごとに 1** |

**AMD-Faster への含意（決定的）**:
- **13 DWORD 予算** → layout を `set0: フレーム定数(1) / set1: チャンクデータ(1 or 2) / set2: テクスチャ(bindless table 1)` に割り当てれば余裕。
- **robust buffer access を OFF にすると dynamic buffer のコストが半減（4→2 DWORD）**。
  Minecraft は index が生成側で保証できるので OFF にできる可能性がある（ただし安全性トレードオフ。要設定化）。
- Nvidium が「bindless texture array + 巨大 uniform」に寄せているのは、まさにこの 13 DWORD 制約への回答。
  **AMD でも同じ設計が有効**という根拠になる。

## 8. Synchronization / Present / Clear

- 同一 queue 内は barrier で足りる（semaphore/fence 不要）。cross-queue のみ fence/semaphore。
- fence は CPU/GPU 両方にコスト。**同期は最小化**。
- submit/present 中に CPU をアイドルさせない。
- **present**: async compute と重ねるなら別 queue から present。
  - **Vulkan: AMD では compute queue が present できる。**
  - 対応確認必須。V-Sync On なら `FIFO_RELAXED`（無ければ `FIFO`）、Off なら `IMMEDIATE`。
- **Fast clear（★AMD 固有）**:
  - **通常のフルフィルより約 100倍速い**。
  - 条件: **画像全体**のクリアであること。
  - **RT の fast clear 色は RGBA(0,0,0,0) / (0,0,0,1) / (1,1,1,0) / (1,1,1,1) のいずれか**。
  - depth は `1.0f` か `0.0f`（stencil=0）。depth array は全スライスを一度に。
  - クリアを省くなら `LOAD_OP_DONT_CARE`/`Discard` で依存を切る。
  - Vulkan: `vkCmdClearColorImage`/`ClearDepthStencilImage` より **`LOAD_OP_CLEAR` / `vkCmdClearAttachments` を優先**。

**AMD-Faster への含意**: Minecraft の `glClear` 相当は (0,0,0,1) 系の空色ではなくフォグ色になるので
**fast clear 対象にならない**ことがある。→ **背景クリアを (0,0,0,1) で行い、フォグ色はシェーダ側で乗せる**
設計にすると fast clear が効く。これは OpenGL バックエンドでも効く（`glClear` の色を白/黒/透明系に寄せる）。
**測定可能で、効果が大きい、低リスクの最適化**。要実装リスト行き。

## 9. Async compute

- GCN/RDNA は**固定機能にブロックされない compute queue** を持つ。graphics のフロントエンドが詰まっている間を埋める。
- 重ねる典型: **Z pre-pass、シャドウ、ポストプロセス**。次フレーム冒頭と前フレームのポストを重複させる。
- **export bound なシェーダと並列にすると async compute の効果は落ちる**。
- **async では小さい workgroup（64スレッド）の方が良い**（リソース要求が下がり発射機会が増える）。
- barrier 無しなら graphics↔compute の前後は重なる。小さい dispatch は async より **pipelined compute** が良い。

## 10. Copy queue

- copy queue は PCIe 越し転送用に設計された **DMA エンジン**にマップされる。PCIe 転送に使うと僅かに速い＋他 queue を塞がない。
- 両端が GPU 上で、かつ即座に結果が要る/queue が暇なら graphics/compute queue でコピー。
- **UPLOAD（DX12）/ HOST_VISIBLE 非 HOST_CACHED（Vulkan）は uncached + write-combined**。
  - `memcpy` か逐次書き込みのみ。ランダムアクセス禁止。**読み出し厳禁**。

## 11. ExecuteIndirect（DX12）→ Vulkan の multi-draw-indirect への読み替え

- 最速パスは root signature を一切変えないこと。
- pre-RDNA は root signature 変更でスローパス。**RDNA で最適化パスが追加された**（それでも変更しない方が速い）。
- 変更する場合の作法:
  - **vertex buffer binding を ExecuteIndirect で更新しない → vertex pull を使う**
  - spill した値を更新しない（毎 draw 変化する値は先頭へ）
  - **更新は連続エントリにする**（[0,2,4] より [0,1,2]）
  - カル結果は argument buffer を**コンパクト化**し、**count buffer を渡す**

**AMD-Faster への含意（★中核設計方針）**:
- 「**vertex pull + multi draw indirect + count buffer + GPU 側カル + argument コンパクション**」が
  AMD で最も速いパス。これは **Sodium の `ChunkBuilderMeshing`→indirect draw** や **Nvidium の occlusion culling** の延長線上にある。
- OpenGL 4.3+ にも `ARB_multi_draw_indirect` / `ARB_indirect_parameters` があるので、
  **OpenGL バックエンドでも同じ構造を再現できる**（＝段階的に Vulkan へ移行できる）。

## 12. Shaders

### 一般
- **arc 系（`atan`,`acos`…）は 100+ サイクルのコードになりうる。避ける。**
- transcendental（`sin`,`cos`,`sqrt`,`log`,`rcp`）は **quarter rate**。RDNA は quarter rate で共実行可能。
- **`tan` は sin/cos に展開されて transcendental 3発**になる。
- 近似関数を使え（FidelityFX に高速近似ライブラリがある）。
- **cross-wave op（subgroup op）**が GCN/RDNA で使える（AGS / SM6 / SPIR-V subgroup）。prefix sum・ダウンサンプル・フィルタに有効。
- **VGPR 圧を減らす intrinsic**: FP16 で VGPR 数を削減、`readfirstlane` で VGPR→SGPR にスカラ化。
- **単一チャンネルのサンプルは `Gather4`**: テクスチャユニットへのトラフィック減＋キャッシュヒット率向上。
  **アドレッシングの VGPR が 8→2 に減る。**

### Compute
- **GCN = wave64 / RDNA = wave32。** 未使用レーンはマスクされる。
- **workgroup size は 64 の倍数にすると全世代で最良**（＝ wave32×2、または wave64×1）。
- **バンド幅最大化: wave あたり 256バイトの coalesced ブロックで image 書き込み**。
  目安は **8×8 スレッドグループが 8×8 ピクセルのブロックを書く**。swizzled レイアウトでさらに改善
  （FidelityFX の `ARmpRed8x8()`）。
- **LDS は 32 banks × 32bit(1 DWORD)**。bank conflict がレイテンシを上げる。
  - `float4[]` から x を読むと **8 bank conflicts**。`float x[]`（SoA）なら **2 bank conflicts**。
  - → **SoA 化 or パディングで stride を崩す**。
- **フルスクリーンパスは compute shader で書くと最大 2% 速い**（8×8 タイルが三角形で埋まらない問題が消え、
  エッジの helper lane が不要になり、キャッシュヒット率も上がる）。
- **RDNA3 以前**では、分岐の多いワークロードは pixel shader より compute の方が速いことがある
  （pixel shader は他 wave の export にブロックされるため）。

### Vertex
- geometry shader や cull distance でのカルは高い。
- **VS で position を NaN にしてカルする**（primitive を殺す）。

### Pixel
- 長いシェーダで `discard` を避ける。**Z pre-pass 済みのピクセルは depth test equal で捨てる**。
- 書き込みデータ量を減らす。**`RGB32` フォーマットは避ける**。

### Sampler feedback
- 全ピクセルに feedback を書かない。stochastic discard を使う。

### 16-bit math
- **Vega で Rapid Packed Math（RPM）＝ 16bit float 2倍レート**。
- DXC は `-enable-16bit-types`。`Texture2D<float16_t4>` で 16bit sample/load。

**AMD-Faster への含意（シェーダ設計ルール化）**:
- 地形 VS は **vertex pull**（`vec3 pos = loadPos(idx)`）にする。→ ExecuteIndirect 節の推奨そのもの。
- 空/雲/フォグ計算で `atan`/`sin` を多用しない（Minecraft の vanilla 太陽位置計算は `atan2` を使う箇所がある → 要置換）。
- **チャンクメッシュのビルド（CPU）は別問題だが、GPU 側のライト計算を compute に寄せる**なら
  workgroup=64 倍数、LDS は SoA、8×8 ブロック書き込みを守ること。

## 13. VRS / Ray tracing（Minecraft では低優先度）

- VRS: pixel shading bound 時に有効。**depth export / post-depth coverage / Raster Ordered Access Views は VRS fill rate を 1×1 に落とす**。
- RDNA2 では、同じ depth buffer 中に alpha-test ジオメトリ等で VRS を一時無効化する必要がある場合、
  **VRS image はバインドしたまま combiner で無効化する**のがベストプラクティス。
- RT: レイは最小に。**AMD のトラバーサルは RDNA WGP 上で動き LDS を多用する** → **RT シェーダで groupshared を食いすぎるな**。
  **8×4 threadgroup が RDNA WGP に最適**。
- BVH: 小さいほど良い。高品質ビルドフラグ。動的ジオメトリを毎フレーム再ビルドしない。**TLAS は毎フレーム compute queue で再構築**。
- ツール: **RGP**（shader table / DXR プロファイル / ISA 逆アセンブル）、**RRA**（BVH 可視化）。

## 14. Debugging

- debug runtime / validation layer は必須。**warning ゼロ**を目指さないと実装違い/将来のハードで壊れる。
- **buffer marker で TDR を追う**：external memory のバッファに書く（さもないと device ごと失う）。
  ただし **draw ごとの marker は出荷ビルドで使わない**（in-flight の仕事量を制限してしまう）。**pass 単位**にすること。
- debug marker は RenderDoc / PIX / RGP に出る。
- **GPU 完了を待つ簡単な手段を実装しておく**（同期バグ追跡用）。
- RGP は PIX3 marker を PIX3.h 経由で扱える。

## 15. この文書から得た「AMD-Faster 実装チェックリスト（案）」

優先度順。★=低リスク/高効果。

1. ★ **fast clear 条件を満たすクリア色に寄せる**（(0,0,0,1) 等）＋`LOAD_OP_CLEAR`/`vkCmdClearAttachments` 使用。
2. ★ **persistent-mapped + HOST_VISIBLE|DEVICE_LOCAL（APU/ReBAR）のアップロード経路**、`memcpy`/逐次書き込み限定、読み出し禁止。
3. ★ **descriptor 予算 ≤13 DWORD** の layout 設計、bindless テクスチャ、static sampler 埋め込み。
4. ★ **multi draw indirect + count buffer + GPU カル + argument コンパクション + vertex pull**。
5. ★ **draw をパイプライン順にソート**、パイプライン数を HUD 化、単一 pipeline cache 共有。
6. **barrier の最小化とバッチ化**、`GENERAL` 回避。
7. **VRAM 予算の 80% ルール**、`VK_EXT_memory_budget` を毎フレーム聞かない、256MB ヒープ + sub-allocation。
8. **compute パスの作法**: workgroup 64倍数 / 8×8 ブロック / LDS 32bank SoA / async では 64スレッド workgroup。
9. **シェーダ**: arc 関数排除、FP16、`readfirstlane`、`Gather4`、cross-wave op。
10. **async compute queue** を Z-prepass / 影 / ポストに、**copy queue** を PCIe アップロードに、**compute queue から present**（AMD 固有）。
11. **検証**: validation + best-practices レイヤーを dev ビルドで常時 ON、warning ゼロ運用。
