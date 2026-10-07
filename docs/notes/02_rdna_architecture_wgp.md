# 02 — RDNA アーキテクチャ（WGP/SIMD32/キャッシュ/DCC）精読メモ

出典（実際に取得・通読したもの）:
- **AMD RDNA Architecture（開発者向けプレゼン, PDF 4チャンク全読）**
  `https://gpuopen.com/download/RDMA_Architecture_public.pdf`
- 補助: `https://gpuopen.com/rdna/`（RDNA ISA Guide / Whitepaper の入手先リンク）
  - RDNA ISA Guide: `https://developer.amd.com/wp-content/resources/RDNA_Shader_ISA.pdf`
  - RDNA Whitepaper: `https://www.amd.com/system/files/documents/rdna-whitepaper.pdf`
  - RDNA 3.5 ISA: `https://www.amd.com/content/dam/amd/en/documents/radeon-tech-docs/instruction-set-architectures/rdna35_instruction_set_architecture.pdf`
- **アクセス状況の記録**: `www.amd.com/content/dam/...`（RDNA3.5 ISA 等）は現在 `docs.amd.com` の
  **サインイン要求**にリダイレクトされ、本文を取得できなかった（2026-10-07 確認）。
  gpuopen.com 配下（`/download/*.pdf`, `/learn/*`）は取得可能。

---

## 1. WGP（Workgroup Processor）の構成

- **RDNA = 2 Compute Unit を 1 つの Workgroup Processor に束ねる。**
- WGP の特徴（原文）:
  - lower latency / higher effective IPC を狙った設計
  - **ネイティブ Wave32**、Wave64 は **dual-issue でサポート**
  - **Single-cycle instruction issue**
  - **transcendental の共実行（co-execution）**
  - **2 CU 分のリソースを単一 workgroup が使える**
  - scalar 実行リソース 2倍
  - vector memory の改善
- Navi10 は **20 WGP = 40 CU**。
- GCN 側: RX590 は 36 CU、RX Vega64 は 64 CU。

## 2. 命令発行（★★最重要の性能モデル）

### GCN（4サイクル発行）
- 各 wave は **1 つの SIMD16 に割り当てられ、SIMD16 あたり最大 10 wave**。
- **各 SIMD16 は 4 サイクルに 1 命令**しか発行しない。ベクトル命令スループットは 1/4 サイクル。
- 4つの SIMD16 に wave が分散しないと発行レートが埋まらない。

### RDNA（1サイクル発行）
- 各 wave は **1 つの SIMD32 に割り当てられ、SIMD32 あたり最大 20 wave**。
- **各 SIMD32 は毎サイクル 1 命令を発行**。Wave32 ならベクトルスループット 1/cycle。
- **5 サイクルのレイテンシが露出**する（ハードウェアが自動で依存チェック）。
  **依存ストールは他 wave で埋められる。**

### 実測レイテンシ（プレゼン内テーブル、GCN vs RDNA）

| 命令 | GCN (Wave64) | RDNA (Wave32) |
|---|---|---|
| `v_rcp_f32` | 18 cycles | 12〜16 cycles |
| `v_fma_f32` | 3〜4 cycles | 6 cycles |
| `v_mul_f32` | 3 cycles | 5〜6 cycles |

> "With small dispatches, RDNA behaves significantly better than GCN"

**含意**: RDNA は **レイテンシは伸びたがスループット/発行が速い**。→ Minecraft のように
「小さい draw を大量に出す」ワークロードは RDNA で有利に働くはず（＝AMD-Faster の前提が立つ）。

## 3. ILP とスケジューリング（命令順で 2倍変わる）

プレゼンが同一の 6 命令を 2 通りの順序に並べたタイムラインを示している:

- **NG**: `v_fma v4` → `v_fmac v4` → `v_fma v5` → `v_fmac v5` → `v_fma v6` → `v_fmac v6`
  （同一 vreg への連続依存）→ **18 サイクル**
- **OK**: `v_fma v4` → `v_fma v5` → `v_fma v6` → `v_fmac v4` → `v_fmac v5` → `v_fmac v6`
  （依存をずらす）→ **11 サイクル**

**含意**: GLSL 側で「同じ出力レジスタへの逐次累積」を避け、**独立な出力を先に並べる**だけで
シェーダが速くなる。コンパイラが並べ替えるとは限らない（特に ACO のスケジューリングは
リソース圧を優先する）。→ AMD-Faster の地形シェーダは「RGB を独立ストリームで計算」する形で書く。

## 4. Transcendental 共実行

- 対象: `rcp / rsq / sqrt / log / exp / sin / cos`
- **transcendental は 1/4 レート**（GCN と同じ）。
- **ただし非 transcendental 命令は transcendental と並列実行できる**（RDNA の新機能）。
  → 「重い `rcp` を先に出して、その影で FMA を回す」構成が有効。

## 5. VGPR と occupancy（★数値は暗記必須）

- **SIMD32 あたり 1024 物理レジスタ**。wave 間で分割、**1 wave あたり最大 256 VGPR**。
- **Wave64 は倍カウント**。
- 例: 4× Wave32 @256VGPR / 2× Wave64 @256VGPR / **16× Wave32 @64VGPR** / 8× Wave64 @64VGPR
- **occupancy は GCN から不変**、「**SIMD レーンあたりのスレッド数**」で考えること。
  - GCN occupancy 4 = 16 threads/lane → RDNA では 16×Wave32 or 8×Wave64

**AMD-Faster への含意**: compute シェーダ（カル/ソート/ビルド）を **VGPR ≤ 64 に収めると
occupancy 16 wave** が出る。**VGPR 128 で 8 wave、256 で 4 wave**。
RGA で VGPR 数を静的に確認する運用が必要（RGA は GLSL/SPIR-V から ISA・VGPR 数・occupancy を出す）。

## 6. ALU 飽和に必要なスレッド数（★設計に直結）

- **GCN: 2 CU で 2×4×64 = 512 スレッド**ないと ALU 100% にならない。
- **RDNA: WGP で 4×32 = 128 スレッド**で ALU 100% になりうる（高 ILP が前提）。
  - グラフィックス負荷は RGB/XYZ の 3 独立ストリームを持ちやすい。
  - **実用上は 256 threads/WGP で >90% ALU 使用率**に達する。
- メモリレイテンシ隠蔽には追加スレッドが要る（ALU で待てれば別だが稀）。
- RDNA は「バリア後の立ち上がりが速い」「高 VGPR のダメージがやや小さい」。

**AMD-Faster への含意（決定的）**:
- **workgroup を 64 に固定すると RDNA では WGP の半分しか埋まらない**。
  ただし後述の「workgroup size は 64 の倍数」ルール（GCN 互換）もある。
  → **RDNA 専用パスでは 128 スレッド（= WGP 1個ぶん）を 1 workgroup** にすると理屈上最適。
    GCN/Vega 世代は 256 の倍数が安全。**→ 世代別に local size を切り替える設計にする。**
- Nvidium のオクルージョン/ソート compute が 64 や 32 区切りで書かれている場合、
  AMD では **128 の倍数に上げると occupancy が跳ねる**可能性がある（要実測）。

## 7. ILP 目的の「1スレッド複数ワークアイテム」は逆効果

プレゼンが明示的に否定している:
- コード肥大（**I$ サイズに注意**）
- VGPR 数増加
- **実効ディスパッチ粒度が大きくなり、エッジの無駄が増える**
> "Don't panic: extra waves are a really good source of parallelism"

**含意**: メッシュビルド系 compute を「1スレッドが8頂点処理」等にしない。**wave 数を増やす。**

## 8. Wave64 / Wave32 の使い分け

- Wave64 は **2×Wave32 として実行される（dual-issue、コード肥大なし）**。
  EXEC の low/high half が 0 の側は実行をスキップ。
- **Wave32 の利点**: wave lifetime 短縮、**バリア後の WGP 立ち上がりが速い**、
  部分充填 wave で効率が良い、メモリアクセスパターンがタイト。
- **Wave64 の利点**: occupancy（threads/lane）が高い、**属性補間（attribute interpolation）で効率的**。
- **決定はコンパイラ**: compute/vertex は通常 Wave32、**pixel shader は通常 Wave64**。
- Call to action（原文）:
  - **シェーダ内バリアを意味的に正しく書け**（コンパイラが不要なバリアを消す）
  - **variable subgroup size を有効化せよ**（特に wave/subgroup intrinsic 使用時）
  - **workgroup size は 64 の倍数**

**AMD-Faster への含意**: `VK_KHR_shader_subgroup_uniform_control_flow` /
subgroup size 系の拡張を使い、**subgroup 依存コードを wave32/64 両対応**にする
（`gl_SubgroupSize` を仮定しない）。Sodium/Iris 系シェーダは subgroup をあまり使わないが、
**カル/ソート compute では必須の注意**。

## 9. LDS

- **WGP あたり 128 kB**。compute の共有メモリ兼、pixel shader の属性用。
- **workgroup あたり最大 64 kB**。
- **read/write/atomic スループットは 32 dword/cycle（GCN の2倍）**。
- **32 banks**（Vega と同じ）→ **bank conflict に注意**。
- （01章の Performance Guide と合わせると）`float4[]` の x 読み = 8 conflicts、SoA なら 2 conflicts。

**AMD-Faster への含意**: 128 kB/WGP なので **2 workgroup × 64 kB** まで並走可能。
64 kB を超える LDS 使用は occupancy を 1 workgroup/WGP に落とす。

## 10. IPC 改善（命令数が減る仕組み）

- 目的は **move 命令の削減**。
- **Dual scalar source**: `v_fma_f32 v0, v0, s0, s1` のように SGPR を2つ直接使える
  （`v_mov_b32 v1, s0` が消える）。
- **全 VALU 命令が immediate 対応（96bit 命令）**:
  `v_mul_f32 v0, |v0|, 0x3f490fdb`（pi/4 を直接埋め込める）。
- **image 命令に NSA（non-sequential address）エンコーディング**（オプション）:
  `image_sample v[0:1], [v14, v8], ...` → move 削減、レジスタアロケーション簡素化で **VGPR 圧低下**。

**含意**: GLSL で「定数を const にする」「swizzle を工夫する」だけで ISA が短くなる。
**RGA で ISA を見て `v_mov_b32` の数を数える**のが AMD 特有の最適化指標になる。

## 11. WGP まとめ（原文の call to action 一覧）

- バリアを確認せよ
- variable subgroup size を有効化
- workgroup size は 64 の倍数
- ILP に気を配る（が、panic しない）
- **occupancy は「SIMD レーンあたりスレッド数」で計算**
- **VGPR 圧は少し気にしなくてよい**（GCN より）
- **LDS bank conflict は気にせよ**
- **Shuffle より ShuffleXor を使え**

**AMD-Faster への含意**: subgroup shuffle を使うカル/サフィックスサムは
**`subgroupShuffleXor` を第一候補**にする。

## 12. メモリ階層（★GCN/Vega/RDNA の差＝APU 対応の要点）

### トポロジ
- **GCN (Vega 64)**: Shader Engine → Shader Array → **CU ごとに L1$**、I$/K$ は 4CU 共有、
  下に L2$、Scalable Data Fabric、HBM2。
- **RDNA (5700 XT)**: Shader Array → **WGP に L0$（CUごと）+ WGP 共有 I$/K$**、
  Shader Array に **L1$**、L2$、Scalable Data Fabric、GDDR6。

### L2 クライアント（★copy queue を使う理由）
- **Polaris**: L2 のクライアントは **CU のみ**。Copy Engine / CP / Render Backend はメモリ直書き
  → **L2 フラッシュが多発**。
- **Vega**: CP と Render Backend も L2 クライアント化 → フラッシュ減。
  ただし **copy queue 経由のアップロードは依然 L2 フラッシュが必要**。
- **RDNA**: **Copy Engine も L2 クライアント** → **"Navi" では L2 フラッシュはほぼ観測されない**。

**AMD-Faster への含意（★大きい）**:
- **Polaris/Vega（＝古い APU: Vega 8 等）では、copy queue へのオフロードが L2 フラッシュを誘発する**。
  → 世代で挙動が違うので、**「copy queue に投げれば速い」は RDNA 以降限定**。
  Vega 世代 APU では **graphics/compute queue での直接書き込み or persistent-mapped 直書き**の方が速い可能性。
- 実装: 起動時に GPU 世代（PCI ID / `GL_RENDERER` / `VkPhysicalDeviceProperties.deviceName`）を判定し、
  **アップロード経路を切り替える**。APU（Vega）向けフォールバックを必ず用意する。

### キャッシュサイズ（実測表）

| | RX Vega 64 | RX 5700 XT |
|---|---|---|
| I$ | 32KB / 4 CU、line 32B | 32KB / WGP(~2CU)、line **64B** |
| K$ | 16KB / 4 CU、line 32B | 16KB / WGP(~2CU)、line **64B** |
| L0/L1 | L1 16KB / CU、line 64B | **L0 2×16KB / WGP、line 128B** |
| L1(RDNA) | — | **128KB / shader array、line 128B** |
| L2 | 4MB、line 64B | 4MB、line **128B** |

→ I$/K$ が実質2倍、128B ラインで帯域向上、L1(+512KB) で Vega より低レイテンシ。

### DCC（Delta Color Compression）＝★AMD 特有の圧縮

| クライアント | Vega: 圧縮読 / 圧縮書 | Navi: 圧縮読 / 圧縮書 |
|---|---|---|
| CU / WGP | ✓ / **✗** | **✓ / ✓** |
| Render Backend | ✓ / ✓ | ✓ / ✓ |
| Present Queue | **✗** / N/A | **✓** / N/A |

- Vega: **UAV に書く前に decompression barrier が必要**。
- Navi: **Present 前に decompression barrier**。
- → **"Expect more textures to stay compressed" / "Compute all the things!"**

**AMD-Faster への含意**:
- **Vega 世代 APU では UAV 書き込みが圧縮解除を引き起こす**。カル結果を UAV に書く設計は
  Vega でペナルティ。→ Vega ではバッファ（非圧縮）に書く。
- Navi 以降は compute で読み書きしても圧縮が保たれる → **compute ベースのカリング/描画**が現実的。

## 13. この文書から得た AMD-Faster の設計ルール（追加分）

1. **workgroup size**: RDNA=128 の倍数（理想 128）、GCN/Vega=256 の倍数。世代分岐。
2. **VGPR ≤ 64** を目標に compute を書く（occupancy 16 wave）。RGA で検証。
3. **LDS ≤ 64 KB**（できれば ≤32 KB）。SoA 配置で bank conflict 回避。ShuffleXor 優先。
4. **命令順で ILP を作る**（同一出力への逐次依存を避ける）。
5. **transcendental を先に出して FMA を影で回す**。
6. **subgroup size を仮定しない**（wave32/64 両対応）。
7. **Vega/Polaris では copy queue・UAV 圧縮の扱いを変える**（APU 対応の要）。
8. **occupancy は「threads/SIMD lane」で管理**し、HUD/デバッグ画面に表示する。
