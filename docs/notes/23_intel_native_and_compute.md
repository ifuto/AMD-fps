# 23. Intel ネイティブ最適化と、計算系の軽量化

プロジェクトが **Intel も兼ねる**ことになった。このノートは 22 の続きで、
(1) Intel GPU/CPU ネイティブの技術、(2) AMD 側で新たに判明した点、
(3) **描画以外＝計算系**の軽量化、を扱う。各項に出典。数値の無いものは **未測定**。

---

## 1. Intel GPU ネイティブ（Xe-HPG / Xe2）

出典: Intel *Introduction to the Xe-HPG Architecture*、oneAPI GPU Optimization Guide。

### 1-1. 用語の対応（AMD と対応させて覚える）

| Intel | AMD | 備考 |
|---|---|---|
| XVE（Xe Vector Engine、旧 EU） | CU 内の SIMD | **256bit、SIMD8** |
| XMX（Xe Matrix eXtension） | WMMA / tensor 相当 | **1024bit、DPAS 命令** |
| Xe-core | WGP 相当 | 16 XVE ＋ 16 XMX ＋ **192KB L1/SLM** |
| Render Slice | Shader Engine | 4 Xe-core ＋ 4 RTU ＋ 固定機能 |
| SLM（Shared Local Memory） | LDS | **128KB/Xe-core** |
| スレッド/XVE | wave | **8 スレッド**（Xe-LP は 7） |

Arc A770: **8 render slice / 32 Xe-core / 512 XVE / 4096 ハードウェアスレッド / 16MB L2 / 560 GB/s**。

### 1-2. 実装に直結する制約

1. **extended math（EXP, RCP）は XVE あたり 2 ops/clk。FP32 MAD は 16 ops/clk。**
   → **1/8 レート。AMD の quarter rate より悪い。** シェーダの `exp`/`rcp`/`sqrt` は
   AMD よりさらに厳しく避ける。`SQRT` も 2 ops/XVE/clk。
2. **SLM が 128KB と AMD LDS 64KB の倍。** → タイル化を AMD より大きくできる。
   ただし**ベンダーごとに最適タイルサイズが違う**ので、実行時に決める。
3. **DPAS（XMX）は INT8 256 ops/clk/EU、INT4/INT2 で 512。** DP4a は 64。
   → **XMX は AI 専用ではなく汎用の行列演算器**。ただし FP16/BF16/INT のみで FP32 無し。
4. **Xe2（Battlemage）は native SIMD16。** Xe-HPG は SIMD8。
   → **分岐の粒度が世代で変わる。** 8 レーンで揃えたシェーダは Xe2 で半分無駄になる。
   Xe2 は clock-normalized で **draw 12.5×、mesh shader dispatch 4.1×、pixel blend 2.1×**。
5. **Arc は DX12 Ultimate 準拠＝mesh shader 対応。**
   → **Nvidium と同じ手法が Intel でも可能。Nvidium が NVIDIA 専用なのは実装上の選択で、
   技術的には Intel Arc でも成立する。これは AMD-Faster の Intel 経路の最大の目玉。**
6. Hi-Z は render slice 単位（Xe2）。

---

## 2. Intel CPU ネイティブ（ハイブリッド / Thread Director）

出典: Intel Architecture Day 2021、GameDev2023 *Programming for Hybrid*、Alder Lake 仕様。

### 2-1. Thread Director の実体は EHFI

**Enhanced Hardware Feedback Interface**。スレッドを 3 クラスに分ける:

- **Class 0** = P/E どちらでも可
- **Class 1** = P-core 推奨（ピーク性能 or 低レイテンシ要求）
- **Class 2** = E-core 推奨

さらに **performance score 0–255** と **efficiency score 0–255** を持つ。
**ナノ秒精度でスレッドの命令ミックス（scalar/vector）を監視**する。

### 2-2. これが FPS にどう効くか（最重要）

- **Windows 11 必須。** Windows 10 は Thread Director 非対応で全論理コアを等価扱いするため、
  **ゲームスレッドがフレーム途中で E-core に移動する**。
- その症状は **平均 FPS の低下ではなく 99 パーセンタイルのフレーム時間外れ値**として出る。
  → **1% Low の問題。** この Mod のベンチが 1% Low を報告する理由がここにある。
- **E-core は最大 3.9–4.3 GHz、P-core は 5.8 GHz**（Raptor Lake）。誤配置されたフレームは明確に遅い。
- **コア種別をまたぐデータ移動は ring bus 経由で遅延が増える。**
- **P-core だけがハイパースレッディングを持つ。**
- **AVX 命令は優先的に P-core へ置かれる。** → 22 の AVX-512 バックエンドは自然に P-core へ行く。
- 実測事例: **P-core のみの Bartlett Lake Core 9 273PQE が、CPU バウンドのゲームで
  ハイブリッドの 14900K を上回った**（平均 FPS とフレームペーシングの両方）。
  → **誤配置の実コストは実在する。**

### 2-3. 開発者から制御できる手段

- **PowerThrottling API / `EcoQos`** — スレッドに QoS 属性を付け、
  E-core 向きだと明示できる。→ **メッシュ生成や GC 補助などの裏方スレッドは
  Class 2 相当として明示的に E-core へ寄せる**べき。
- **Linux は 5.18+ で EHFI 読み取りに対応。ただしユーザ空間からのヒントが別途必要。**
- **AVX-512 は E-core が物理的に実行できない。** Alder/Raptor Lake の消費者向けでは
  AVX-512 自体がヒューズで無効化されている（E-core が持たないため）。
  → **バックエンドの AVX-512 経路は必ず実行時ディスパッチ**（実装済み）。
  → **SIMD スレッドを P-core に固定する必要がある**場合がある。未検証。

### 2-4. キャッシュ形状

- **P-core L2 = 1.25MB（Golden Cove）/ 2MB（Raptor Cove）per core**
- **E-core は 4 個で 2MB L2 を共有**
- **L3 最大 30MB 共有**（AMD の CCD 単位パーティションとは構造が違う）

→ **Intel の L3 は共有。** 22 の `CacheTopology` は「ドメイン単位」を返す設計なので、
**Intel では domains=1 として扱えば正しくなる**。V-Cache のランダム置換も Intel には当てはまらない。
**既に実装済みの抽象で両ベンダーを表現できる。**

---

## 3. ベンダー横断で効果が大きいもの（XeSS 2 / XeLL）

出典: Intel *Xe Super Sampling 2 Whitepaper*、XeSS SDK 2.1.0 リリース、Eurogamer/DigitalFoundry。

### 3-1. XeSS 2 の三本柱

| 要素 | 内容 | ハードウェア要件 |
|---|---|---|
| **XeSS-SR** | AI アップスケール | **SM 6.4+ なら全ベンダー**（非 Intel は DP4a） |
| **XeSS-FG** | フレーム生成 | Intel は XMX。**SDK 2.1.0 以降は AMD RDNA+ / NVIDIA Pascal+ でも可**（compute shader） |
| **XeLL** | 低レイテンシ | Xe-HPG 以降 |

### 3-2. XeLL の仕組み（この Mod に直接使える）

1. **入力を集める前にスリープする**ようアプリに指示する
2. **CPU と GPU のフレーム時間を計測し、両者が並列に動ける瞬間まで CPU 作業をブロックしない**
3. → **レンダーキューの待ち時間が完全に消え、CPU 時間が GPU 時間の背後に隠れる**

- **レイテンシ最大 45% 削減**
- **最も効くのは GPU バウンド時、または VSync ON / フレームレート制限時**
  → **Minecraft はまさにフレームレート制限を掛けるゲーム。直結する。**
- 実測（F1 24、Arc B580）: **native 48fps/57ms → XeLL 32ms → XeSS+XeLL 19ms → XeSS+XeLL+FG 152fps/28ms**

### 3-3. フレーム生成の実際のコスト

- **オーバーヘッドは 1 フレーム時間の 5–10%**。1440p 実測で理論 98fps に対し 86fps。
- **低解像度ほど効く。** アップスケールは内部解像度が低いと CPU バウンドになるが、
  フレーム生成は CPU バウンドの影響を受けない。
- **表示順序を入れ替える**: 補間フレームを先に出し、原本フレームを遅らせる。
  ドライバが各フレームの描画時間を継続計測し、**その半分の間隔で表示をスケジュール**する。
- **フレーム間隔のスパイクが減る**（最大の dt3 が `dt3' - dt2'/2` になる）

---

## 4. 計算系の軽量化（描画以外）

ユーザー指示「単純な描画関連だけではなく、計算関連も全部軽く」。

### 4-1. ワールド生成 — 最大級の計算コスト

**C2ME の構造が参考になる**（出典: c2me-opencl-forge1201 リポジトリ、C2ME 開発者発言）。

| モジュール | 役割 |
|---|---|
| `c2me_opts_dfc` | **Density Function Compiler** — MC の density function を **AST 化して最適化し、OpenCL カーネルを生成** |
| `c2me_opts_accel_opencl` | GPU でバッチ実行（biome multinoise ＋ block density）、デバイス列挙、CL プログラムコンパイル、**シェーダキャッシュ**、**ベンダー別ワークアラウンド** |

**決定的な設計上の事実:**

- **FP64 を使ってバニラのノイズ/density function と厳密一致させる。**
  → **FP32 にすると地形が変わる。これは最適化ではなく別世界生成になる。**
  → **AMD-Faster が GPU で density を評価するなら `shaderFloat64` 必須。**
    AMD RDNA の FP64 は消費者向けで 1/16 レート、Intel iGPU は世代により FP64 有無が分かれる
    （6〜9 世代は持つ）。**FP64 無しの環境では CPU にフォールバックする設計が要る。**
- **C2ME は GPU 以前に AVX による SIMD 加速を既に持っている。**
- 実測: **9950X3D ＋ GPU で 2500+ chunks/sec**、9800X3D ＋ 200 mod で 700+ cps、
  **素の i5 で約 170 cps**。
- **ただし注意（開発者の反論）**: RAM→GPU→RAM の往復レイテンシがあるため
  **CPU でもかなり追いつける**。GPU が明確に勝つのは大規模な地形生成。
  → **持ち込まない。まず CPU 側を測る。**

**AMD-Faster としての狙い目:**
- **density function の AST 化＋共通部分式削除**はベンダー非依存で確実に効く。**未着手。**
- **CPU 側の AVX2/AVX-512 ノイズ評価**（FP64 でバニラ一致）。バックエンドに置ける。**未着手。**
- GPU 化は FP64 が使える環境のみ、かつ測定してから。

### 4-2. エンティティ・ブロックエンティティ

出典: Entity Culling 実装解説、minecrafthub。

- **Sodium のエンティティカリングはチャンク単位でしか見ない。**
  → **可視チャンクの中に壁で隠れたエンティティがあっても描く。**
- **Entity Culling はエンティティごとに完全な視線判定を行う。カメラから 64 ブロック、全方向。**
- **spare CPU スレッドで非同期パスレーシング。**
- **ホワイトリストされたエンティティはティック自体をスキップする**（描画だけでなく）。
- **密集した建築で FPS 20〜60% 向上。何もない平原ではほぼ無効。**
- **Iris 併用で効果が拡大**（スキップしたエンティティがシェーダパイプラインも通らないため）。

→ **AMD-Faster は 21 §3 の raster occlusion を CPU で持つのだから、
エンティティにも同じ深度バッファを適用できる。これが Entity Culling との差別化点。**

### 4-3. その他の計算系（競合が押さえている領域）

| Mod | 対象 |
|---|---|
| **Lithium** | パスファインディング、チャンクロード、エンティティトラッキング、**衝突判定** |
| **Mobtimizations** | **エンティティ AI の無駄な処理** |
| **Entity Collision FPS Fix** | クライアント側の衝突判定をスキップ（1.18.2–1.19.2 のみ） |
| **ThreadTweak** | CPU / スレッド管理 |
| **Starlight / ScalableLux / Phosphor** | ライトエンジン全面置換 |
| **Enhanced Block Entities** | ブロックエンティティ描画 |
| **More Culling** | **葉の内側の面**（OptiFine の Smart Leaves 相当）、ブロックの見えない部分、**額縁の地図**、**ビーコンのビーム** |
| **BadOptimizations** | 描画に偏らないマイクロ最適化の集まり |
| **Structure Layout Optimizer** | 構造配置 |
| **Noisium** | **アーカイブ済み。「5% 程度でほぼ効かない」との評** → ノイズ最適化の効果を過大評価しないこと |

---

## 5. 勝つべきベースライン（拡大）

21 の時点より大幅に増えた。**単体でこれら全部入りに勝つ**のが目標。

**描画:** Sodium、Nvidium（**NVIDIA 限定なので AMD/Intel ベースラインには入らない**）、
ImmediatelyFast、Entity Culling、More Culling、Enhanced Block Entities、Cull Less Leaves

**計算:** Lithium、C2ME（**GPU 版含む**）、ThreadTweak、Mobtimizations、
Structure Layout Optimizer、BadOptimizations、ScalableLux

**メモリ/起動:** FerriteCore、ModernFix

**ネットワーク/その他:** Krypton、Dynamic FPS

**AMD/Intel それぞれの「Nvidium 相当」枠が空いている。**
Arc は mesh shader 対応なので、**Intel 経路でここを埋められる**のが今回最大の機会。

---

## 6. 実装順（このノートの範囲）

| 順 | 項目 | ベンダー | 期待効果 |
|---|---|---|---|
| 1 | ベンダー検出層（`CacheTopology` を Intel 形状にも対応） | 両方 | 前提 |
| 2 | XeLL 相当のフレームペーシング（CPU 作業を GPU の背後に隠す） | 両方 | **レイテンシ 45%** |
| 3 | エンティティへの raster occlusion 適用（21 §3 のバッファを共用） | 両方 | **20〜60%** |
| 4 | density function の AST 化＋共通部分式削除 | 両方 | 未測定 |
| 5 | CPU 側 AVX2/AVX-512 ノイズ評価（**FP64 必須**） | Intel/AMD | 未測定 |
| 6 | Thread Director ヒント（裏方スレッドを Class 2 相当に） | Intel | **1% Low** |
| 7 | Intel 経路の mesh shader パス（Nvidium 相当） | Intel | 未測定 |
| 8 | SLM/LDS のサイズを実行時に決めるタイル化 | 両方 | 未測定 |
| 9 | 葉の内側の面・額縁の地図・ビーコンのビームのカリング | 両方 | 未測定 |
| 10 | GPU density 評価（FP64 が使える環境のみ） | 両方 | 未測定 |

**全項目、導入前に必ず測定する。** 特に 4-1 の GPU 化は開発者自身が
「CPU でもかなり追いつける」と述べており、**測らずに持ち込む価値はない**。
