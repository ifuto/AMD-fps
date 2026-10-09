# 19. AMD ネイティブ実装プラン — 次に何を作るか

目的は「全軽量化 Mod 全入れの Fabric に、この Mod 単体で勝つ」。
本ノートはそのための**優先順位つきの作業計画**であり、各項目に
(a) AMD 固有か NVIDIA でも可能か、(b) 期待できる効果と**その根拠**、
(c) このリポジトリでの実装形、(d) CI だけで検証できるか、を書く。

ローカルに JDK が無い（`java`/`javac`/`gradle` 全て absent）ので、
**測定はできない**。したがって数値は全て出典付きの第三者実測か、
この環境で実行できる等価性検証に限定する。測れないことを測れたと書かない。

---

## 0. 前提：どこが重いか

Minecraft Java は**CPU バウンド**。0fps（mikola）の結論:
> the bottleneck is not the amount of triangles rendered by the GPU,
> but the amount of calculations made by the CPU

したがって GPU 側の最適化だけ積み増しても、CPU が律速の間は FPS は動かない。
**順位の 1 位は CPU 側**であり、これは AMD 固有ではなく「勝つために必須」の項目。

そして本リポジトリを調べた結果、その CPU コストが**実際に存在する場所を特定した**。

---

## 1. 【最優先・CPU】ボクセル毎の `BlockPos` 割り当てとチャンク再解決を消す

### 発見した実コード

`mc/LevelBlockSampler.java`:

```java
public int key(int x, int y, int z, Orientation orientation) {
    int sprite = this.faces.spriteId(x, y, z, orientation);
    if (sprite < 0) return VoxelView.NO_GEOMETRY;
    BlockState state = this.level.getBlockState(new BlockPos(x, y, z));  // ← 毎回確保
    ...
}
public boolean isOpaque(int x, int y, int z) {
    BlockState state = this.level.getBlockState(new BlockPos(x, y, z));  // ← また確保
    ...
}
```

1 セクションの meshing で呼ばれる回数:

| 呼び出し | 回数 |
|---|---|
| `key()`（6 向き × 4096） | 24 576 |
| その内の `new BlockPos` | 最大 24 576 |
| その内の `getBlockState`（チャンク・セクション再解決） | 最大 24 576 |
| `isOpaque()` の `new BlockPos` + `getBlockState` | 数千〜数万 |

**1 セクションのリビルドで 2.5 万個以上の短命オブジェクトと、
同数のチャンク／セクション再解決**が発生する。
`getBlockState(BlockPos)` は座標からチャンクを引き直し、セクションを引き直し、
それから `PalettedContainer` を引く — 同じセクションを 4096 回引き直す。

### 根拠（第三者実測）

Minecraft Forum の長期スレッド:
> getBlockState (in World, Chunk, and ChunkCache) accounted for substantial amount of CPU
> overhead. I developed a block state cache (write-through direct-mapped cache...) which made a
> HUGE difference. That plus a BlockPos neighbor cache **literally doubled Minecraft performance**
> for the test cases we tried.

CurseForge の最適化 Mod 集も同じ形の問題を繰り返し挙げている:
> ...each one allocating a BlockPos and calling Level.getBlockState, which **re-resolves the chunk
> and section for a block whose section is already sitting right there in a local variable**.

### 実装

3 段で、下の段ほど効く。

1. **`BlockPos.MutableBlockPos` の再利用**（最小）。`new BlockPos` を消す。
   Minecraft がこの目的のために用意している型。GC 圧が 2.5 万個/セクション減る。
2. **セクション単位の `BlockState` スナップショット**。セクションを 1 回だけ解決し、
   `LevelChunkSection.acquire()` → `getStates()`（`PalettedContainer<BlockState>`）→
   `release()` で 4096 個を平坦配列に一括展開。以降の 24 576 回の `key()` は
   **配列読みと仮想呼び出しなしの比較**になる。
   `acquire`/`release` と `getStates()` の 1.21.11 における存在は
   `docs/notes/10` で mappings.dev により**確認済み**。
3. **キーの向き非依存化**。フル不透明立方体は 6 面が同一スプライトなので、
   向き毎の `spriteId` を 6 回ではなく 1 回で済ませる。
   上面だけ違う草などの例外は `BlockFaceSource` が向き依存を宣言する形にする。

### 検証

CI でコンパイルが通ること（MC 型を参照するので CI が唯一のコンパイラ）＋
**Java 側の等価性テスト**。スナップショット経由の `key()` が
逐次 `getBlockState` 経由の `key()` と**全ボクセル・全向きで一致**することを、
合成レベルに対して網羅検証する。これはローカルでは測れないが CI で走る。

### 優先度

**1 位。** 効果が最も大きく、根拠が最も強く、CI で検証できる。

---

## 2. 【CPU・SIMD】Vector API、ただし静的参照しない

### 根拠

- Java Vector API は JDK 16 以降 incubator。JDK 21 は JEP 448（5 回目の incubation）。
  `--add-modules=jdk.incubator.vector` が**コンパイル時と実行時の両方に必要**。
- 実測されている効果は 4〜16×（Java Code Geeks の整理: ML 推論・暗号・信号処理で実測）。
  SIMD ソートの実装例では **3×**。
- **しかし Mod は JVM 引数を追加できない。** ユーザーがランチャーに足す必要がある。

### 決定的な設計制約（既存 Mod から）

`etil2jz/VectorX`（Minecraft 向けに Vector API を使う実在の Mod）の安全モデル:

> **No static reference to the incubator module.** Module detection goes through
> VectorModuleProbe, which never names a `jdk.incubator.vector` class statically.
> The mod loads and runs correctly on a JVM where the module is entirely absent.
>
> Resolution order per kernel, evaluated once at startup (first match wins):
> 1. `vectorized.forceScalar=true` → scalar
> 2. config `backendForcedScalar` → scalar
> 3. **`jdk.incubator.vector` absent → scalar**
> 4. SIMD class fails to link → scalar
> 5. config mode = scalar / off → scalar
> 6. **self-test fails → scalar**
> 7. otherwise → vector

`vectorx.mixins.json` は `"required": false` / `defaultRequire: 0` で、
対象コードが動いてもクラッシュせず**不活性化する**。

**これを採用する。** `jdk.incubator.vector` を静的参照した瞬間、
フラグの無い JVM では `NoClassDefFoundError` で Mod ごと落ちる。
したがって:

- SIMD カーネルは**独立クラスに隔離**し、リフレクションでロードする
- 起動時に**セルフテスト**（既知入力でスカラー実装と一致するか）
- 不一致なら永久にスカラーへ
- `GPUBooster`（別の実在 Mod）が「JVM 引数が必要」と明記しているのは
  この制約を回避していないから。**回避できる**

### 対象（純粋算術で、SIMD が実際に効くもの）

1. **greedy meshing のラン延長判定**。`mask[]` の行比較は SIMD compare そのもの。
2. **AO／スムーズライティングの 4 サンプル補間**。`SmoothLight` は頂点毎 4 サンプル。
3. **フラスタム判定**（CPU 側のエンティティカリング）。`GPUBooster` は
   1.21.1 でフラスタム、1.21.11 で天气レンダリングを SIMD 化していると明記。
4. **ソート**（`SortKey` の基数ソート）。実測 3× の前例。

**対象外**: 仮想ディスパッチが律速の場所（§1）。SIMD は间接呼び出しを速くしない。

### 検証

CI に SIMD パスは走らない（フラグが無い）。したがって
**スカラー実装との等価性**を CI で網羅検証し、SIMD 側は同一のテストを
「フラグがあれば」通す形にする。等価性テストが本体。

### 優先度

**3 位。** §1 の後に。理由は、§1 が終わらないと律速が仮想ディスパッチのままで
SIMD の効果が頭打ちになるから。

---

## 3. 【GPU・AMD 固有】非同期コンピュート — AMD の最大の構造的優位

### AMD 固有である理由

- AMD は **ACE（Asynchronous Compute Engine）をハードウェアで持つ**。
  RDNA ISA は `S_WAITCNT` / `S_BARRIER` / `DS_GWS_SEMA_P` による
  graphics と compute の並行実行を規定し、RDNA1 は **Asynchronous Compute Tunneling** で
  パイプラインを止めずにインターリーブする。
- NVIDIA 側の実情（NVIDIA 自身の Vulkan Dos and Don'ts）:
  > Don't overlap compute work on the graphics queue with compute work on a dedicated
  > asynchronous compute queue on **pre-Ampere** GPUs.
- r/vulkan の整理:
  > Using multiple queues of the graphics queue family will just cause submits to be
  > interleaved. **That's an NVIDIA-ism anyway**, no other vendor exposes more than one
  > graphics queue.
- AMD での実測報告（GameDev.net）: 「execution start and end timestamps **overlap**, so at
  least it works」。
- 反証も記録する: 「every sync point is a CPU round trip because of **WDDM scheduling**」。
  Windows では同期待ちが CPU 往復になる。**Linux/RADV では軽い**。
  したがって効果は OS とドライバに依存し、無条件ではない。

### 何を重ねるか

Minecraft のフレームは**リビルドスパイクが支配的**なので、
「平均スループット」より「スパイクを隠す」方が FPS に効く。

1. **チャンクアップロード**（`SectionUpload` の出力転送）を graphics の裏で
2. **カリング + Hi-Z 生成**を前フレームのラスタライズと並列に
3. Doom 方式（dark_sylinc の記述）:
   graphics → compute でポスト → **compute queue から present**、
   その間に graphics が次フレームをラスタライズ

### 既存ルールとの整合

`docs/notes/07` の「single graphics + single compute queue」は
**transfer queue を on-GPU コピーに使うな**という趣旨であり、
compute queue を実際に並列運用することを禁じてはいない。
ただし `QueueSelector` が compute-only queue family を拾う必要があり、
**`VK_KHR_timeline_semaphore` が前提**（CPU 待ちを消すため）。

### 検証

**CI では検証できない**（実機が必要）。したがって:
同期の**構造**（どの submit が何を signal/wait するか）を
Java 側のグラフとして表現し、**デッドロックと WAR ハザードが無いこと**を
ユニットテストで検証する。GPU 上の効果は測定不能と明記する。

### 優先度

**4 位。** 効果は大きいが検証不能。§1・§2 の検証可能な項目を先に片付ける。

---

## 4. 【GPU】VRS（可変レートシェーディング）

### NVIDIA でも可能。AMD で上回る方法

`VK_KHR_fragment_shading_rate` は 3 方式:

| 方式 | 内容 | 可用性 |
|---|---|---|
| per-draw | パイプライン／コマンドバッファで指定 | **全ティア** |
| per-primitive | シェーダから指定（HLSL 6.4 の `SV_ShadingRate`） | mesh shader 必須 |
| per-image | フレームバッファ領域毎にアタッチメントで指定 | 全ティア |

シェーディングレートの符号化（1 テクセル 8 bit）:
```
horizontal = 2^((texel / 4) & 3)
vertical   = 2^(texel & 3)
```
0 が 1x1（フルレート）、最大 4x4。

**AMD で上回る点**: 本 Mod は既に**メッシュレット単位**でジオメトリを持っている。
per-primitive 方式なら**メッシュレット 1 個 = レート 1 段階**が自然に書け、
これは per-draw より細かく、per-image の compute パスを 1 本丸ごと省ける。
NVIDIA の実装（VRS wrapper）は per-draw／per-image が中心で、
per-primitive は Turing 以降の mesh shader 経由。
RDNA3 以降も mesh shader を持つので**同一条件だが、
メッシュレット化を先に済ませている側が先に使える**。

### 正直な限界

VRS が減らすのは**フラグメントシェーディングのみ**。
ラスタライズもジオメトリも減らない。Minecraft は高レンダー距離では
ドローコール／ジオメトリ律速になりやすく、その場合は VRS の効果は小さい。
効くのは 1440p/4K と、cutout（葉・草）が多いシーン。

### 優先度

**5 位。** per-draw 方式（距離帯で 2x2/4x4）から。アタッチメント方式は後。

---

## 5. 【GPU・AMD が逆転した領域】LDS を使う

### 根拠（実測マイクロベンチ、Chips and Cheese）

> RDNA 3 makes a massive improvement in LDS latency... **Nvidia enjoyed a slight local memory
> latency lead over AMD's architectures, but RDNA 3 changes that.**

> Nvidia has a very large and fast first-level cache, but after that **AMD has an advantage as
> long as it can serve accesses from L1 or L2**.

RDNA3: L1 256 KB（シェーダアレイ共有、16-way）、L2 6 MB。
L0 は WGP 毎に 96 KB（vector 32 KB + scalar 16 KB）。

**つまり LDS を多用するアルゴリズムは、RDNA3 以降では AMD が有利**。
これは NVIDIA でも書けるが、同じコードで AMD の方が速い。

### 何に使うか

1. **フラスタム平面の LDS 化**。今は全レーンが UBO から 6 平面を読む。
   ワークグループで 1 回 LDS に載せれば L0 scalar 経由になる。
2. **GPU 基数ソート**。`docs/notes/09` に「LDS 内 radix sort が bitonic を **2.5×** 上回った」
   とある。`SortKey` のソートに使う。
3. **Hi-Z 生成**。近傍テクセルを LDS に載せてから縮約。

### 制約

LDS は 32 バンク × 32 bit。SoA かパディングで**バンク競合を避ける**（既存ルール）。
`local_size_x = 64` は RDNA で wave32 × 2、GCN で wave64 × 1。

### 優先度

**6 位。** カリングは既に 1 ウェーブ 1 アトミックまで最適化済みで、
残りのボトルネックがメモリなのか ALU なのかを**実機なしに判断できない**。

---

## 6. 【GPU・AMD 固有】スカラーユニット

### AMD 固有である理由

RDNA は**スカラーユニット（SGPR / SMEM）を実際に持つ**。
NVIDIA にスカラーパスは無く、uniform データもベクトルパイプを通る。
RDNA4 ではスカラーユニットが **FP32 に対応**した。

`readfirstlane` で VGPR をスカラー化できる（既存ルールにも記録済み）。

### トレードオフ（正直に）

- **1 メッシュレット = 1 レーン**（現状）: スループット最大。
- **1 メッシュレット = 1 ウェーブ**: メッシュレット内の全計算がスカラーになる。
  RE Engine は **16 clusters/wave** でこの中間を取り、0.543 → 0.051 ms。

現状は既にウェーブ協調アトミックを入れたので、
次の一手は RE Engine 型の「1 レーン複数メッシュレット」だが、
**これは実機測定なしに選べない**。

### 優先度

**7 位。** 測定不能なので、測れるようになってから。

---

## 7. 【GPU・AMD 固有】RDNA4 のアウトオブオーダーメモリと動的レジスタ

### 根拠（HWCooling / Wccftech の RDNA4 解説）

- **アウトオブオーダーメモリ**: CU 内のロード／ストアが並べ替え可能に。
  > In previous RDNA 3 architectures, a cache miss caused execution to stall until the data
  > arrived.
  ボクセルデータは**アクセスが不規則**（パレット参照、隣接チャンク跨ぎ、
  ラン長が可変）なので、これはまさに効く対象。
- **動的レジスタ割り当て**: 固定の最悪ケース割り当てでなく、実行中に要求・返却。
  > With fixed allocation, such shader tasks would block a large number of registers throughout
  > their relatively long-lasting execution time.
- **新しいバリア命令、fill/spill の高速化、プリフェッチ強化**。

### 実装上の帰結

VGPR を節約すれば occupancy が上がり、OoO メモリの恩恵が増える。
既存ルールの **VGPR ≤ 64** は RDNA4 ではより直接的に効く。
**「レジスタを節約する書き方」が RDNA4 では性能になる**という整理。

### 優先度

**8 位。** 既存ルールの再確認で足りる部分が多い。

---

## 8. 【必須・未実装】ディスパッチ層

`gpu/CullingPipeline` は**パイプラインとレイアウトを作るだけ**。
バッファもディスパッチも持たない。
`SectionUpload` は 6 バッファを埋めるが、それを `GpuBuffer.mapForWrite()` へ流す層が無い。

**これが無いと何も動かない。** ただし CI では検証できない。

### 優先度

**2 位**（検証可能性ではなく「動くために必須」で順位を上げている）。

---

## 9. 実行順

| 順 | 項目 | 区分 | CI 検証 | 根拠の強さ |
|---|---|---|---|---|
| 1 | §1 `BlockPos`／`getBlockState` の削減 | CPU | **可** | **実測あり** |
| 2 | §8 ディスパッチ層 | GPU | 不可 | 必須 |
| 3 | §2 Vector API（スカラーフォールバック必須） | CPU | 等価性は可 | 実測あり |
| 4 | §3 非同期コンピュート | GPU・AMD 固有 | 不可 | 実測あり（OS 依存） |
| 5 | §4 VRS | GPU | 不可 | 仕様が明確 |
| 6 | §5 LDS | GPU・AMD 有利 | 不可 | 実測あり |
| 7 | §6 スカラーユニット | GPU・AMD 固有 | 不可 | 測定必須 |
| 8 | §7 RDNA4 OoO／動的レジスタ | GPU・AMD 固有 | 不可 | 仕様が明確 |

### 意図的に順位を下げたもの

- **FSR（アップスケール）**: FPS への効果は最大級だが**見た目が変わる**。
  ユーザーの要件「見た目は変化しない徹底的なカリング」と衝突するため、
  オプション扱い。FSR1（EASU+RCAS）は MIT で compute shader 実装可能。
  FSR4 は ML ベースで **RDNA4 専用**。
- **メッシュシェーダ**: RDNA3 以降が持つが、**NVIDIA は Turing から持つ**。
  AMD の実装は task shader によるアンプリフィケーションを持たず、
  メッシュワークグループを直接ディスパッチする形なので、
  この一点では NVIDIA が上。正直に記録する。

---

## 10. 検証方針（全項目共通）

ローカルに JDK が無い。したがって:

- **GPU 側の変更**は「シェーダが SPIR-V にコンパイルできること」＋
  「Java 側のモデル実装と一致すること」で担保する（`ShaderCompileTest`、`CullShaderTest`）。
- **CPU 側の変更**は網羅的等価性テストで担保する。
- **測れないものは測れないと書く。** 効果を数字で主張する場合は必ず出典を添える。

手計算の期待値は**最大の間違え源**（過去 20 件超）。
Python で先にモデル化して差分を取る方法が有効。


---

## 11. §1 を実装して分かったこと

`mc/BlockStateCache.java` を追加し、`mc/LevelBlockSampler` を接続した（CI green `5e7df3e`）。
実装中に 3 つのバグが出て、いずれも**検証の方法そのもの**に関わるものだった。

### 11.1 原点が sentinel と衝突していた

当初、キーは bit 63 でタグ付けし、空きスロットの sentinel は `Long.MIN_VALUE` だった。
**原点は 3 座標とも 0 なので、タグ付き符号化がちょうど `Long.MIN_VALUE` になる。**
つまりワールド原点のブロックが空きスロットと一致し、未初期化の flags を返す —
デコードすると「空気でも不透明でもなく、発光 0」。
自分のジオメトリは出るのに隣接面の自分に対する面は抑止される、という
**原点に穴が開く**症状になる。新しいワールドでプレイヤーが立つ場所がまさにそこ。

sentinel を **0** に変更。`positionKey` は常に bit 63 を立てるので 0 には到達できず、
副次的に `new long[]` がそのまま空テーブルになり `Arrays.fill` も不要になった。

**検証ループ自身も間違っていた。** 最初の Python 確認は y/z の範囲を
`vals[:40]`（= −64..−25）でスライスしていて、**0 を含まなかった**。
だから 341 万キーを回して「問題 0」と出た。範囲をスライスする検証は、
境界値を意図的に含めないと意味がない。

### 11.2 テーブルサイズを実測で決めた

「1 ボクセル 1 スロット」の 4096 エントリが自然なサイズに見えるが、
**direct-mapped は作業集合と同じサイズだと自分自身で追い出し合う**。
この表と 16×16×16 の掃引をモデル化すると:

| エントリ数 | 1 パス後の生存 | 6 向きパスのヒット率 | テーブル |
|---|---|---|---|
| 4 096 | 23% | **23%** | 48 KB |
| 16 384 | 93% | **93%** | 192 KB |

23% ではキャッシュの意味がほとんど無い。16 384 にした。
テストの閾値はこの**モデルの値に紐付けて**あり、サイズを戻すと落ちる。

構造としての正直な注記も残す: 作業集合は密な直方体なので、
その箱を直接インデックスする配列なら 18³ = 5 832 int（23 KB）で**衝突ゼロ**。
呼び出し側が箱を宣言する必要があるため今回は採用しなかった。

### 11.3 テストが `Map.put` の null を unbox していた

テストヘルパが `int put(...) { return truth.put(key, flags); }` だった。
`Map.put` は**以前の値**を返し、新規キーでは null。宣言が `int` なので unbox で NPE。
**マップを初期化する 4 テスト全部が setup で死んでいた。**

**Python モデルがこれを捕まえられなかった。** Python の dict は `None` を返しても
unbox しないので、アルゴリズムは正しくモデル化できていても**言語の罠は模型化されない**。
等価性検証は計算の正しさを担保するが、ホスト言語の型変換は担保しない。

### 11.4 失敗テスト名が CI に出なかった（これが一番の損失だった）

3 回連続で CI が `There were failing tests` だけを返し、**どのテストか一切分からなかった**。
原因はワークフロー側: ログを固定パターンで grep し、`head -15` で先頭 15 行しか
アノテーション化しない。NPE のスタックトレースはどのパターンにも一致せず、
ログ全体で一致行が 2 行（両方とも汎用メッセージ）しかなかった。

`build.gradle` に **`reportTestFailures`** を追加した。JUnit XML を解析して
`error: test <class>.<method> failed -> <type>: <message>` を出力する
（`error: ` 接頭辞はワークフローが grep するパターンの 1 つ）。
**追加した次の run で即座に 4 テスト全部の名前と NPE のメッセージが出た。**

`test` の `doLast` ではなく **`finalizedBy`** にした理由: `doLast` は自分のタスクが
失敗すると実行されない — まさにこれが必要な状況で動かない。
`verifyJar` は同じ XML を解析しているが `test` に依存しているので、
テストが失敗すると**スキップされて使えない**。

**教訓: 診断できない検証系は、検証系として機能しない。**
ローカルにコンパイラが無い環境では、失敗の名前を出す仕組みを最初に作るべきだった。

### 11.5 CI の一時的な失敗

2 回、`org.ow2.asm:asm:9.9` の POM 解析失敗（`Could not find org.ow2:ow2:1.5.1`）で
ビルドがテストタスクに到達しなかった。Fabric ミラー側の一時的な障害で、
コードとは無関係。空コミットで再実行して切り分けた。
**失敗を見たらまず「前回と同じ失敗か」を確認する**こと。
