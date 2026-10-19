# 22. AMD CPU 側の軽量化と、Sodium Add-on 化

このノートは 21 の続きで、**新しく調べた技術**を扱う。対象は三つ。AMD CPU 固有の機能、
それを使うための**バックエンドプロセス方式**、そして **Sodium が同時導入されている場合に
Add-on として「でも」動く**ための仕組み。

各項に出典を付ける。数値の無いものは **未測定** と明記し、導入前に必ず測定する。

---

## 0. なぜバックエンドプロセスなのか（前提）

`build.gradle` は `release = 21`。Java 21 では:

- **FFM (`java.lang.foreign`) はプレビュー**で `--enable-preview` が必要。
- **Vector API (`jdk.incubator.vector`) は `--add-modules=jdk.incubator.vector` が必要**。
  JDK 16 から 26 まで 11 回 incubate され、**JDK 22 以降 API 変更なし**（Valhalla 待ち）。

**どちらも Mod 側からは指定できない。** したがって Mod は LWJGL が既に bind している以外の
ネイティブコードを呼べず、Vector API も一切使えない。

一方 **子プロセスなら両方使える**。しかも Java 16 以降 `UnixDomainSocketAddress` と
`SocketChannel` が**標準ライブラリでフラグ不要**。これがバックエンド方式の根拠。

現状の確認: `ProcessBuilder` / `Runtime.exec` / `UnixDomainSocket` はリポジトリに **0 件**。
`vk/` は LWJGL 経由の**プロセス内** Vulkan、`MeshWorkerPool` は素の JVM スレッド。
**まだバックエンドプロセスではない。** これから作る。

副次利点: **GC 圧がカリングのホットパスに乗らない**、**バックエンドが落ちても Mod は
scalar/GPU に落ちるだけ**、**バイナリを Mod 本体と別に更新できる**。

---

## 1. AVX-512（Zen 4 / Zen 5）

出典: Agner Fog の Zen 4 実測、Mysticial の Zen 5 teardown、Blend2D の Zen 5 移行記事。

| 項目 | Zen 4 | Zen 5 desktop (Granite Ridge) | Zen 5 mobile / Ryzen AI APU |
|---|---|---|---|
| 512bit データパス | **256bit を double-pump** | **native 512bit** | **256bit（Zen 4 同等）** |
| スループット | 1024 bits/cycle | **2048 bits/cycle** | 1024 bits/cycle |
| 512bit 演算の最小レイテンシ | 1 cycle | **2 cycle に悪化** | — |
| L1D | 32 KB | **48 KB** | 48 KB |
| FP レジスタファイル | 192 × 512bit | 384 × 512bit | — |

**設計への含意:**

1. **Zen 4 でも 512bit を使う価値がある。** double-pump だが 2 つの 256bit ユニットで
   *同時*実行され、レイテンシは倍にならない。1 命令が 2 命令分になりフロントエンドの
   デコード負荷が減る。Blend2D 実測でも **Zen 4 で AVX-512 コードは等価な AVX2 より速く、
   消費電力も少ない**。
2. **APU では速度向上を期待しない。** Strix Point は命令セットはフルだが 256bit 幅なので
   Zen 4 相当。**780M 世代を含むターゲットはここに該当する。**
3. **Zen 5 では独立した仕事を複数飛ばす。** 最小レイテンシが 2 cycle に上がったため、
   依存チェーンにすると広いデータパスの利点を打ち消す。→ `SimdSupport.needsLatencyHiding()`
   が 512bit 以上で true を返す。
4. **gather / scatter は Zen で遅い。** → 全カリングカーネルを **SoA** にして連続ロードのみ。
5. AVX-512 は 10 入力ポート制限で 4 FMA/cycle は出ない（Zen 5）。

**実装済み:** `src/main/resources/native/afback_cull.c` — 16 ボックス/回のフラストムカリング、
scalar twin 同梱、`--selftest` で 1000 ボックスを両パス照合。
**このマシンでの実測: 1000 ボックス、可視 35、カリング 965、不一致 0**（`-Wall -Wextra` クリーン）。

---

## 2. 3D V-Cache

出典: *Measuring Data Access Latency in Large CPU Caches* (ACM, 2024)、Chips and Cheese
Zen 3 V-Cache deep dive、Phoronix の amd_x3d_vcache ドライバ記事。

**測定で判明した二つの事実。どちらも教科書と違う。**

### 2-1. V-Cache はパーティションであって共有ではない

X3D の 64MB スタックは**1 つの CCD の 32MB スライスの上**に乗る。その CCD は 96MB になるが、
**他の CCD は拡張されない。** 実測でも CCD 数に関わらず有効容量は 96MB/CCD。

→ **ワーキングセット予算はドメイン単位。** チップ合計で予算すると CCD 数分だけ過大評価して
スラッシングする。`CacheTopology.l3PerDomainBytes()` がドメイン単位を返すのはこのため。

| 構成 | ドメインあたり L3 | 予算（半分） |
|---|---|---|
| 非 X3D CCD | 32 MB | 16 MB |
| **X3D CCD** | **96 MB** | **48 MB** |
| 読めなかった場合 | 8 MB（保守側） | 4 MB |

Genoa X は 12 CCD × 96MB = 1152MB だが、**1 スレッドが使えるのは 96MB**。

### 2-2. V-Cache の置換ポリシーは LRU でなくランダム寄り

同一の測定で判明。**「最近触ったからまだ居る」に依存するコードは X3D で成立しない。**

→ `CacheTopology.canAssumeLruEviction()` が X3D で **false**。
→ 方針依存でなく**そもそも収まるサイズに抑える**設計にする。

### 2-3. レイテンシ代償は小さい

Zen 3 でスライスを 3 倍にして **+3〜4 cycle**。対して Zen 2→3 で 16MB×2 を 32MB に統一した時は
**+7〜8 cycle**。→ **大きな L3 は「遅いメモリ」ではなく使うべき資源。**

### 2-4. 検出方法（Linux）

- `/sys/devices/system/cpu/cpu0/cache/index*/{level,size,shared_cpu_list}`
- `/sys/bus/platform/drivers/amd_x3d_vcache/AMDI0101:00/amd_x3d_mode` — **このドライバは
  スタックキャッシュを持つ部品にしか bind しない**ので、それ自体が V-Cache の証拠。
  値は `cache` / `frequency`（Linux 6.13+、`AMD_3D_VCACHE` Kconfig）。

**実装済み:** `cpu/CacheTopology.java` — sysfs を実読、読めなければ保守側へフォールバック。

### 2-5. まだやっていない V-Cache 活用例

- **ワーカーのスレッドアフィニティを CCD に固定**し、各ワーカーのワーキングセットを
  1 CCD の L3 に収める。**未測定。**
- **メッシュキャッシュを 48MB 予算内に収める**（現状 `GeometryCache` は予算を参照していない）。
- **`amd_x3d_mode` を `cache` に寄せる提案**を HUD に出す（書き換えはしない）。

---

## 3. CPU 側の徹底的カリング

### 3-1. Intel MOC 方式（実装予定・最優先）

出典: Intel Masked Occlusion Culling。

- **単一スレッド CPU で全ジオメトリを 4ms**、フル解像度 depth buffer の **98% をカリング**、
  既存手法の **3 倍速**。
- **32×8 タイル = 256 px を並列**。各タイルは **8×4 サブタイル**に分割され、サブタイルごとに
  **min/max depth 2 float ＋ カバレッジマスク**を **SoA** で持つ。
  → 8 サブタイル × 2 float = 8 float ずつ = **ちょうど 1 本の AVX2 ベクタ**。
- **オクルーダのラスタライズとクエリをペナルティ無しで交互実行**できる。
- 前面→背面順がよく効く。**「working layer を捨てる」ヒューリスティック**。
- SSE2→AVX2 で **5 倍**。Hi-Z 構築 ~1ms。バッファマージのオーバーヘッドは
  640×400 で 0.9%、1080p で 4.4%。

**判定は保守的**: ボックスのスクリーン空間 AABB が重なる全サブタイルが
「カバー済み かつ サブタイル minDepth < ボックス近端 depth」のときだけ不可視。
**可視なものを取りこぼさない**ことが唯一重要な性質。

### 3-2. SoA フラストムカリング（実装済み）

`cull/SoaFrustumCuller.java` ＋ `native/afback_cull.c`。
**positive vertex 判定**（平面法線方向に最も遠い角を使う）で、可視なボックスを絶対に落とさない。

### 3-3. 実装順

1. `TileDepthBuffer` ＋ `SoftwareRasterizer`（3-1）
2. 前フレームの可視集合で depth を prime し、当フレームのクエリに使う temporal 再利用
3. `CameraCutDetector`（既存）でカメラカットを検出して temporal を無効化

---

## 4. Sodium Add-on 化

### 4-1. 仕組み（出典: Sodium/Meteor の mixin 設定、Fabric の mixin plugin）

**`IMixinConfigPlugin.shouldApplyMixin(targetClassName, mixinClassName)`** で
mixin を個別に ON/OFF できる。mixins json に `"plugin"` を指定する。

```json
{
  "required": false,
  "defaultRequire": 0,
  "plugin": "net.amdfaster.sodium.SodiumMixinPlugin",
  "package": "net.amdfaster.sodium.mixin",
  "mixins": [ ... ]
}
```

- **`"required": false` ＋ `defaultRequire: 0`** が必須。これがないと MC 更新や競合 Mod で
  ターゲットが動いたとき**クラッシュする**。あれば**無効化されるだけ**（VectorX と同じ方針）。
- Sodium 自身の `SodiumMixinPlugin` は **`embeddium` を検出したら全 Sodium mixin を無効化**する
  前例がある。→ **こちら側も Embeddium を検出したら Add-on 経路を落とす**べき。
- 参考: Meteor Client は Mod ごとに別 mixins json（`meteor-client-sodium.mixins.json`）を切り、
  `breaks` で非互換 Mod を、バージョン制約で最低版本を宣言する。

### 4-2. 検出

`FabricLoader.getInstance().isModLoaded("sodium")`。
Embeddium も同時に判定し、**両方ある場合は何もしない**（Sodium 側が自分を殺すので状態が不定）。

### 4-3. Add-on として何を AMD ネイティブ化する

Sodium が居る場合は独立レンダラを起動せず、**Sodium の処理を AMD 向けに置き換える**:

| 対象 | Sodium の現状 | AMD ネイティブ化 |
|---|---|---|
| チャンク描画の送出 | MDI（multi-draw indirect） | **mesh shader / GPU-driven cull**（Nvidium と同じ位置） |
| オクルージョン | Hi-Z ベース | **raster occlusion（21 §3、Hi-Z 比 2.2×）** |
| メッシュ生成 | Sodium の mesher | **greedy ＋ AO 対角フリップ（21 §1/§2）** |
| アップロード | Sodium の buffer | **`BufferArena`/`UploadRing`（write-combined 順次書き込み）** |
| カリング | Sodium の frustum | **`SoaFrustumCuller` ＋ バックエンド AVX-512** |

**ライセンス:** Sodium は PolyForm Shield 1.0.0 で**派生不可**。したがって
**Sodium のソースを複製せず、mixin で処理を差し替える**形にする。

### 4-4. 未解決

- **`build.gradle` に `loom { }` ブロックと mixin AP が無く、`fabric.mod.json` に `"mixins"` キーが無い。**
  Add-on を有効化するにはこの二つが要る。**これは配線側の作業**（ユーザー指示により後回し）。
- Sodium を `compileOnly` 依存にすると CI が解決できるか未検証。**依存を増やさず
  リフレクション＋文字列ターゲットで済ませる**方針を優先。

---

## 5. 実装順（このノートの範囲）

| 順 | 項目 | 期待効果 | 状態 |
|---|---|---|---|
| 1 | SoA フラストムカリング ＋ AVX-512 バックエンド | 未測定 | **実装済み（差分テスト緑）** |
| 2 | `CacheTopology`（V-Cache 検出と予算） | 未測定 | **実装済み** |
| 3 | `SimdSupport`（probe ＋ 解決ラダー） | 未測定 | **実装済み** |
| 4 | `TileDepthBuffer` ＋ `SoftwareRasterizer` | **98% カリング / 4ms** | **実装済み（31テスト緑）** |
| 4b | `cull/SectionBoundaryMask` ＋ `cull/SectionGraph`（項目32・27） | 未測定 | **実装済み** |
| 4c | `entity/BlockEntityCuller`（項目28・36） | **400チェストで1.20×** | **実装済み** |
| 4d | `draw/DrawBatchOrder`（項目35） | 未測定 | **実装済み** |
| 5 | バックエンドの IPC プロトコルとライフサイクル | — | 未着手 |
| 6 | `NativeBundle`（JAR からバイナリ展開） | — | 未着手 |
| 7 | `SodiumPresence` ＋ `SodiumMixinPlugin` | — | 未着手 |
| 8 | ワーカーの CCD アフィニティ | 未測定 | 未着手 |

**表の 1-4 は実装済み。配線も済んだ（2026-10-19、`932efd8` でビルド緑）。**
「配線は後回し」という指示は取り下げられ、`mixin/` の5クラスがゲームの呼び出し経路に接続された。
詳細は `26_mixin_layer.md`。

**ただし表の 1-4 のうち、ゲームのイベントから実際に値が流れているのは一部である。**
光のみ再構築・パーティクル予算・音・爆発曝露・チェーン反応は毎tick/毎パケットで動く。
エンティティのカリング結果とメッシュの並列構築は、モデルとしては完成しているが
バニラの描画ループへの投入経路が未確定で、値を供給されていない（`26_mixin_layer.md` 末尾）。
**効果を測るには、まずこの2本の投入経路が要る。** 5-8 は未着手のまま。

**全項目、導入前に必ず測定する。** 論文の数字を Minecraft にそのまま持ち込まない。
