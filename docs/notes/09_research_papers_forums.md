# 09 — 海外論文・技術フォーラム調査（効率の上限を狙うための根拠集）

調査日: 2026-10-07。検索と本文取得で確認した一次情報のみを、**AMD-Faster への適用**つきで記録する。

---

## 1. Aokana: A GPU-Driven Voxel Rendering Framework for Open World Games
**arXiv:2505.02017（2025）／ ACM DOI 10.1145/3728299** — 本文（HTML 版）を取得して読了。

**Minecraft を明示的に前提比較对象にしている GPU 駆動ボクセルレンダラの論文。**
> "in the renowned video game Minecraft, the world is divided into chunks measuring 16×16×(256∼384).
> Invisible voxel surfaces are culled, and a series of identical material flat voxel surfaces are
> merged to reduce the number of vertices and triangles within each chunk."

### パイプライン（全て compute shader）
1. **chunk selection pass** — チャンクの AABB 事前計算 → フラスタムカリング
2. **tile selection pass** — **画面を 8×8 px のタイルに分割。4×4 タイルごとに 1 thread group、
   1 タイルに 1 スレッド**。前フレームの Hi-Z でオクルージョンカリング → **Tile-Chunk ペア**を生成
3. **ray marching pass** — indirect dispatch。タイルごとに thread group。
   **64-bit visibility buffer**（Burns & Hunt 2013 由来）に `InterlockedMax()` で書き込み
4. **build Hi-Z pass** — 次フレーム用の Hi-Z を構築
5. **Color Resolve Pass** — 64bit visibility buffer から color/depth を書き出し

### ★保存的カリングの扱い（実装上とても重要）
> "Since the camera is guaranteed to be outside the axis-aligned bounding box of chunks other
> than the self chunk, the depth value of the intersection point obtained with the bounding box
> will always be **less than** the depth value obtained from the SVDAG. … using a depth value
> smaller than the actual depth value results in **more conservative culling**, ensuring that
> no tiles are incorrectly culled.
> However, **for the self chunk** … the depth value … will always be **greater than** the actual
> depth … leading to incorrect culling. Therefore, for the self chunk, we need to perform
> intersections with the SVDAG."

→ **「カメラを含むチャンク」だけは特別扱いが必要**。Minecraft で言えば
**プレイヤーがいる 16×16×16 セクション**は AABB テストが非保存的になる。

### LOD / ストリーミング
- 8個の LOD n-1 チャンクを 1個の LOD n に集約（octree 的）。**非空ボクセル数が
  `density = 2` 以上なら新ボクセルを作り、色は平均**。
- **LOD 誤差の式（原文 Eq.1）**:
  ```
  LOD Error = (ChunkSize × StreamingFactor) − ‖ChunkCenterPos − CameraPos‖
  ```
  `LOD Error > 0` なら分割、`< 0` ならロード対象。**LOD レベルが高い（粗い）チャンクを優先ロード**
  すると子チャンクをまとめて解放できて VRAM を空けられる。

**AMD-Faster への適用**:
- **タイル選択の 8×8 / thread group 4×4 / 1スレッド1タイル**は、
  RDNA の「8×8 スレッドグループが 8×8 ピクセルブロックを書く」（`01` §12）と一致する。
- **LOD Error の式は Minecraft の描画距離選択にそのまま使える**（セクション単位の LOD）。
- **self section の特別扱い**は必須要件として設計に入れる。

## 2. Ubisoft「GPU-Driven Rendering Pipelines」(Haar & Aaltonen, SIGGRAPH 2015)
- Aokana が明示的に参照。**クラスタ分割 + Hi-Z オクルージョンカリング + indirect draw** の原典。
- 派生実装（`miccall/Unity-GPU-Driven-Pipeline`）の特徴リストがそのまま設計チェックリストになる:
  GPU Driven Cluster Rendering / **Hi-Z Occlusion Culling** / Screen Space Cluster based Lighting /
  IBL / PCSS / GTAO / Irradiance Volume GI / Virtual Texture Lightmap / TAA / Forward+ transparent。

## 3. Two-Pass Occlusion Culling（実務パターン）
出典: `medium.com/@mil_kru/two-pass-occlusion-culling`, `krupitskas.com/posts/modern_culling_techniques/`

**Pass 1**（前フレーム可視だったものだけ）:
```glsl
bool visible = visibilityBuffer[drawIndex];          // 前フレームの可視性
if (visible) { visible &&= !isFrustumCulled(...); }  // 任意: 再フラスタムテスト
bool shouldDraw = visible;
if (shouldDraw) { drawArgs[drawArgsIndex++] = MakeDrawArgs(...); }
```
**Pass 2**（全オブジェクト。Pass1 で落ちたものを新 Hi-Z で再テスト）:
```glsl
bool visible = !isFrustumCulled(...);
if (visible) { visible &&= !isOcclusionCulled(...); }
bool shouldDraw = visible && !visibilityBuffer[drawIndex];   // Pass1 で描いていないものだけ
if (shouldDraw) { drawArgs[drawArgsIndex++] = MakeDrawArgs(...); }
visibilityBuffer[drawIndex] = visible;                        // 次フレーム用
```
- コンパクションは **`InterlockedAdd(g_drawCount[0], 1, slot)` で原子的にスロット確保**。
- 1パス方式は「今フレーム可視になったものが1フレーム消える」問題がある。
  **2パス方式でも Pass1 の Hi-Z は1フレーム古いので微小な誤りは残る**（原文が明言）。

**AMD-Faster への適用**: **これが GPU カルの基本形**。
Minecraft では「可視セクション集合」を `visibilityBuffer` として GPU に持ち、
**CPU 側で可視集合を毎フレーム作り直すのをやめる**。

## 4. メッシュレット研究（3本）

### 4.1 JCGT Vol.12 No.2 (2023)「Performance Comparison of Meshlet Generation Strategies」
- **meshlet = 三角形クラスタ**。per-cluster カリングが可能になる。
- **ローカル index buffer は meshlet ごとに区切り、各セクション内で 0 からの
  8-bit インデックス**。
- **index buffer を tipsify（Sander et al. 2007）で最適化してからクラスタリングすると
  meshlet 構成が改善**（cache size のチューニングが必要）。
- レンダリング時間の傾き（三角形数増加に対する悪化の少なさ）は
  **bounding sphere クラスタリングが最小**。
  > "we finish the meshlet instead of going back to the vertex list to look for new candidates.
  > This enforces spatially coherent meshlets … more compact meshlets, making them more likely
  > to be frustum-culled."

### 4.2 Pacific Graphics 2021「Conservative Meshlet Bounds for Robust Culling of Skinned Meshes」
- **meshlet は 64 頂点 / 126 三角形以下が最適**（キーポイントとして明記）。
- **VFC（視錐台）+ BFC（背面）カリングで最大 35.4% のレンダリング時間削減**。
- backface-cullable な meshlet 率を上げると**さらに 11% 超の改善**。
- task shader で「非カル meshlet だけを次段に進める」＝動的な負荷分散。

### 4.3 CGF 2024「End-to-End Compressed Meshlet Rendering」
- **meshlet あたり 3 バイト**の追加データでフラスタム＋背面コーンカリング:
  **bounding sphere 半径スケール（8bit）／コーン角／apex オフセット**。
- amplification(=task) shader でカリング → 生き残った meshlet だけ mesh shader でデコード。
- **圧縮状態のまま GPU メモリに置き、ラスタライズ直前にシェーダで展開** →
  CPU⇔GPU 帯域を削減（out-of-core レンダリングのボトルネック対策）。

**AMD-Faster への適用（★Minecraft への写像）**:
- **16×16×16 セクション = 1 "クラスタ"**。セクション内の quad を
  **64 頂点 / 124 三角形（= 62 quad）単位の meshlet に分割**する。
- **meshlet あたり 3 バイトのカリングデータ**（sphere 半径スケール＋法線コーン）を持たせれば、
  **背面コーンカリングで Minecraft の「見えない面」を GPU で落とせる**。
  Minecraft は軸-aligned な 6 面のみなので**コーンテストはほぼ厳密**（＝非常に効く）。
- **8-bit ローカルインデックス**は Minecraft の quad メッシュにそのまま使える
  （1セクション内の頂点数は 256 未満に収められる）。

## 5. RE Engine Meshlet Rendering Pipeline（Capcom, REAC 2025, PDF 取得）
- **カリング結果は 64-bit の `CulledMeshletInfo`**:
  ```
  Uint 24-bit : Instance Index
  Uint  8-bit : 固定小数点 LOD 遷移値
  Uint 32-bit : Meshlet Cluster Offset      (※計 64bit)
  ```
  → **bindless material 構造体へのアクセスは instance index 経由**。
- **1 wave が処理するクラスタ数を変えた実測（PS5, async compute off）**:

  | Clusters/wave | VisibilityBuffer Pass | Cluster Culling |
  |---|---|---|
  | 1  | 1.578 ms | 0.543 ms |
  | **16** | **1.159 ms** | **0.051 ms** |

  → **1 wave に 16 クラスタを持たせると cluster culling が 10倍以上速い**。
  実装は `if (WaveIsFirstLane()) globalAtomicInc(sharedInstance); sharedInstance = WaveReadLaneFirst(sharedInstance);`
- **meshlet サイズの実測（v=頂点数/t=三角形数）**:

  | | v128/t128 | v64/t126 | v64/t84 | v64/t64 |
  |---|---|---|---|---|
  | Visibility pass | 882 µs | 920 µs | 935 µs | 1002 µs |
  | Cluster Culling | 168 µs | 208 µs | 229 µs | 300 µs |
  | HW Raster | 158 µs | 150 µs | 141 µs | 139 µs |

  → **頂点数を減らすとカリングは遅くなるがラスタは速くなる**。トレードオフ。
- **Hi-Z が visibility buffer の場合、LOD bias を 2×2 / 4×4 / 8×8 に上げる**（テストコストは上がるが
  画面占有率次第で得）。

**AMD-Faster への適用**:
- **「1 wave = 1 セクション」ではなく「1 wave = 16 セクション」**にする。
  RDNA の wave32 なら 32 セクションも可（`02` §6: WGP 飽和に 128 スレッド）。
- カル結果は **64bit パック**（instance index + LOD + offset）にして帯域を減らす。

## 6. Voxel エンジン実務（フォーラム/個人技術記事）

### 6.1 `nickmcd.me/2021/04/04/high-performance-voxel-engine/`「Vertex Pooling」
- **vertex pool + persistently mapped buffers + `glMultiDrawElementsIndirect`** の組み合わせが
  「通常のチャンクメッシングより **GC・GPU へのデータ転送・CPU オクルージョンクエリのすべてで優れる**」。
- **★面方向ごとに 6 バケットへ分割 → 「真の背面カリング」**:
  > "We can achieve higher performance than regular back-face culling by splitting every chunk mesh
  > into **6 vertex pool buckets according to the face orientation** … and **masking according to
  > the camera orientation**."
  > "Note: To apply this technique to regular chunk meshing, a chunk mesh would have to first be
  > split into 6 sub-meshes **increasing driver overhead 6-fold**."
  → **vertex pool 方式だからこそ 6 分割が無料になる**。
- **front-to-back ordering** を併用。
- greedy meshing は面方向ごとに走査するので、**方向ごとに新バケットを要求するだけで自然に 6 分割される**。

### 6.2 `thenumb.at/Voxel-Meshing-in-Exile/`
- **compact quad + instanced vertex shader でアンパック**が最速だった、という実測。
- **頂点座標はチャンク内整数座標なので 12 バイトの vec3 は不要**（ビット幅を詰められる）。
- greedy meshing の quad は最大 31×31 まで広がるので **UV 繰り返し回数を頂点に持たせる**。

**AMD-Faster への適用（★とても具体的）**:
- **Minecraft のセクションメッシュを「面方向 6 バケット」に分割**し、
  **カメラ方向に応じて 3 バケットだけ描く** → **ドローする三角形が理論上ほぼ半分**。
  これは VulkanMod が `QuadFacing` を draw パラメータの次元に持っている理由とも整合する（`04` §2.5）。
- **頂点フォーマットのビットパック**（相対座標＋UV 繰り返し）で帯域を削減。
  RDNA は 128B キャッシュライン（`02` §12）なので**頂点ストライドを 16/32 バイトに揃える**と効く。

## 7. GPU ソート（半透明用）

### 7.1 Satish, Harris, Garland「Designing Efficient Sorting Algorithms for Manycore GPUs」(2008, NVIDIA)
**`mgarland.org/files/papers/nvr-2008-001.pdf`** — GPU radix sort の定番論文。
- **on-chip shared memory（＝AMD の LDS）でブロック内を局所ソート**することで、
  **外部メモリへの散在書き込みを on-chip への散在書き込みに変換**する。
  **on-chip は約 2 桁速い**。
- 局所ソート後にオフセットを計算して scatter するので、**同一キーの要素がブロック内で連続**し、
  **安定ソート**になる。
- **bitonic sort は O(n log²n) で work-inefficient。radix は O(n)**。
- 実測: 当時最速の GPU ソートで、マルチコア CPU 比 2〜2.5倍（最大 3.5倍）。

### 7.2 Sintorn & Assarsson「Fast Parallel GPU-Sorting Using a Hybrid Algorithm」
- **bucket sort 1 パスで分割 → 各部分列を vectorized parallel merge sort**。
- **bitonic より 2.5倍速い**。**任意サイズ配列を扱える**（bitonic は 2 の冪が必要）。
- **応用例として「大きな 3D モデルの頂点距離ソート＝透明描画の正確な順序付け」を明示**。
- ボトルネック: bucket sort では **`atomicInc`**、merge sort では **non-coalesced な global read/write**。

**AMD-Faster への適用（★半透明ソートの方針決定）**:
- **bitonic sort は避ける**（O(n log²n)、2の冪制約、Nvidium の `sorting_network.glsl` は bitonic 系）。
- **Minecraft の半透明は「距離順の緩い順序」で十分**なので、
  **radix sort（キー = 量子化した距離）を LDS 局所ソート付きで実装**する。
  - **LDS は 32 bank**（`02` §9）→ **SoA 配置で bank conflict 回避**。
  - **workgroup は 64 の倍数**（AMD RDNA Performance Guide の原文。64 = GCN で wave64×1、RDNA で wave32×2）。※当初「RDNA=128 / GCN=256」と書いていたが、`02` §6 の occupancy 議論との取り違えであり、`01` §11 の一次ソースに照らして訂正した。
- **安定ソートであること**が重要（同じ距離の quad の順序がフレーム間で暴れない＝チラつかない）。

## 8. JVM / GC（Minecraft クライアント特有の結論）

- **`switchbladegaming.com/minecraft/jvm-arguments/`（2026-09）の実測ベースの表**:

  | 状況 | 推奨 GC | 理由 |
  |---|---|---|
  | **クライアント（シングル/LAN）** | **G1GC（既定）** | **ZGC の並行収集オーバーヘッドが実機で FPS コストとして観測される**。クライアントは低ポーズをそれほど必要としない |
  | サーバ < 12GB | G1GC + Aikar's flags | 実績のある組み合わせ |
  | サーバ ≥ 12GB | ZGC（generational） | サブミリ秒ポーズが効く |

- `mill-build.org/blog/6-garbage-collector-perf.html`（2025-01）の実測:
  - **ヒープが live-set の 2倍程度だと ZGC は G1 よりポーズもスループットも悪い**。
  - **ヒープが live-set の 4倍以上**になると ZGC のポーズは 1〜10ms に落ちる。
  - → **ZGC は「メモリを渡してこそ」**。Minecraft クライアントの既定ヒープ（2GB）では不利。
- `ionutbalosin.com` の JVM GC ベンチ:
  **G1 は write barrier（Remembered Set 管理）が重く、配列ループ系のベンチで他より 10〜20倍遅い**。
  → **G1 を使うなら「オブジェクトの書き換え」自体を減らすのが効く**。

**AMD-Faster への適用（★方針確定）**:
1. **クライアントで ZGC を推奨しない**（README に「G1 のままで良い」と明記する）。
2. 代わりに **フレームあたりアロケーションを 0 にする設計**（`08` §5）。
   - primitive 配列・fastutil・off-heap を使う。
   - **可視集合・カル結果・draw 引数はすべて GPU バッファ側に持たせ、Java 側にコピーしない**
     （＝§3 の visibilityBuffer 方針と一致。**CPU 読み戻しを無くすことは GC 対策でもある**）。
3. **`-Xlog:gc*:logs/gc.log` を README に案内**し、Mod 内蔵の GC 統計と突き合わせる。

## 9. 調査結果から確定した設計判断（追加分）

| 判断 | 根拠 |
|---|---|
| **GPU カルは two-pass（前フレーム Hi-Z）** | §3。1パスは可視化遅延が出る |
| **セクションを meshlet（64頂点/124三角）に分割** | §4.2（64/126 が最適）、§4.1（8bit ローカルインデックス） |
| **meshlet あたり 3 バイトのカリングデータ（sphere + 法線コーン）** | §4.3。Minecraft は軸-aligned なのでコーンテストがほぼ厳密 |
| **カルは 1 wave = 16 セクション** | §5 RE Engine 実測（1→16 で cluster culling 0.543→0.051 ms） |
| **カル結果は 64bit パック** | §5（24bit instance + 8bit LOD + 32bit offset） |
| **面方向 6 バケット＋カメラ方向マスク** | §6.1。三角形が理論上ほぼ半減 |
| **頂点フォーマットのビットパック、ストライド 16/32B 揃え** | §6.2 + `02` §12（128B キャッシュライン） |
| **半透明ソートは radix（LDS 局所ソート）。bitonic 禁止** | §7.1, §7.2 |
| **self section は AABB テストを特別扱い** | §1 Aokana（非保存的になる） |
| **LOD Error = ChunkSize×StreamingFactor − dist** | §1 Aokana Eq.1 |
| **GC は G1 のまま。割り当てを減らす方向で最適化** | §8 |
