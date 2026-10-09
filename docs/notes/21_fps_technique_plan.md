# 21 — FPS 向上技術の全体計画（再プラン）

**前提**: 目標は「全軽量化Mod全入れの Fabric に、このMod単体で勝つ」。
比較対象は **Sodium＋ImmediatelyFast＋EntityCulling＋Lithium＋FerriteCore＋ModernFix＋MoreCulling**。
ベンチマークは **1% Low／フレームタイム分散／チャンク再構築時間／RAM／GPU時間**を報告する。

**この計画の組織原則**: 技術ではなく**結線経路**で分ける。前回までの失敗は、
部品を 60 個作って 1 つも配線しなかったこと。技術の列挙はそれ自体では進捗ではない。

---

## 結線状態の定義

| 状態 | 意味 |
|---|---|
| **稼働** | ゲーム実行中に実際に呼ばれ、効果が出る |
| **配線済** | イベント／Mixin から到達できるが、まだ測定していない |
| **部品** | クラスはあるが何も呼んでいない（**禁止状態**） |
| **要レンダラ** | Vulkan バックエンドが無ければ動かない |

---

## A. 稼働中（このリポジトリで今動いているもの）

| # | 技術 | 出典 | 結線経路 |
|---|---|---|---|
| A1 | フレーム時間計測（1% Low／p95／分散） | 自社要件 | `HudRenderCallback` → `FrameTimeRecorder` |
| A2 | 段階別タイマ | 自社要件 | `ClientTickEvents` → `StageTimer` |
| A3 | **非フォーカス時のFPS上限** | Dynamic FPS | `AmdFasterRuntime.applyGovernor` → `options.framerateLimit()` |
| A4 | アイドル検出（カメラ移動で判定） | 自社 | `isPlayerActive` → `FrameGovernor` |
| A5 | 統計オーバーレイ | 自社 | `HudRenderCallback` → `AmdFasterHud` |
| A6 | チャンク出入り時のキャッシュ無効化 | FerriteCore 系 | `ClientChunkEvents` → `BlockStateCache.clear` |
| A7 | GPU 世代プローブ | 自社 | `onInitializeClient` → `GpuReport.probe` |

---

## B. 次に配線するもの（Mixin 不要、Fabric イベントで到達可能）

| # | 技術 | 出典・根拠 | 期待効果 | 結線経路 |
|---|---|---|---|---|
| B1 | パーティクルのカリング＋SoA | ImmediatelyFast（パーティクルをバッチ）／gamedev: パーティクルはフィルレート律速 | 大量パーティクル時に大 | `ParticleEngine` 系イベント or Mixin |
| B2 | オーバードロー計測 | `ParticleField.overdrawPixels` | 診断（改善ではない） | オーバーレイへ |
| B3 | エンティティの非同期オクルージョン | EntityCulling（1000体: 24→45→140+ FPS） | **エンティティ密集時 20〜50%** | `EntityOcclusionCuller` を描画前イベントへ |
| B4 | エンティティのグリッド空間索引 | 空間分割で 1000 体時 ~50× チェック減 | 近接検索 | `EntityGrid` を tick イベントへ |
| B5 | エンティティ描画のバッチ化 | ImmediatelyFast（1000牛 16→60、+Sodium 21→82） | **3.75×** | `EntityBatcher` を描画イベントへ |
| B6 | モデルの遅延ロード＋参照カウント | FerriteCore（メモリ） | 起動時間・RAM | `ModelCache` をモデル解決経路へ |
| B7 | セクションスナップショット（1回解決） | Minecraft Forum「`getBlockState` + BlockPos キャッシュで**性能が文字通り2倍**」 | **〜2×** | Mixin `Level.getBlockState` |
| B8 | ジオメトリの限定キャッシュ | 松明1個で〜50チャンク再構築 | **再構築スタッタ** | `GeometryCache` を再構築経路へ |
| B9 | メッシュ構築の並列化 | Sodium「threaded chunk builder」 | コア数次第 | `MeshWorkerPool` を再構築経路へ |
| B10 | 高さマップ統合 | 光の PR#414 で 14ms→8ms（43%） | 光・陰影 | `HeightmapIndex` |

**B7 が単体で最大**。ただし Mixin が必要で、ターゲット解決失敗は起動時クラッシュになる。

---

## C. Mixin が必要なもの（ターゲットを mappings で検証してから着手）

| # | 技術 | 対象 | 根拠 |
|---|---|---|---|
| C1 | `Level.getBlockState` のキャッシュ化 | `net.minecraft.world.level.Level` | B7 |
| C2 | ブロック面カリングの強化 | チャンクメッシャ | MoreCulling「見た目不変で大幅向上」 |
| C3 | 額縁・地図・看板・ビーコンの描画修正 | 各 BlockEntityRenderer | MoreCulling |
| C4 | 三角関数テーブル | `net.minecraft.util.Mth` | 65536エントリ表が `Math.sin` を上回る |
| C5 | Random Tick 早期除外 | サーバー側（対象外） | — |
| C6 | チャンクパケット並列デコード | Netty パイプライン | C2ME 系 |

---

## D. Vulkan レンダラが必要なもの（要レンダラ）

Multi-Draw Indirect／GPU カリング／HZB オクルージョン／二段階カリング／メッシュレット／
バックフェースカリング／永続マップドバッファ／非同期アップロード／GPU 生成描画コマンド／
遅延初期化ジオメトリ。

**これらは部品として検証済みだが、ディスパッチ層が無い限り動かない。**
「未結線禁止」の原則に従うなら、**これらはレンダラ着手と同時に結線するか、
台帳で明確に「要レンダラ」と分離する**。曖昧な「完了」にしない。

---

## E. JVM・データ構造（横断的、結線先は各所）

| 技術 | 出典 | 数値 |
|---|---|---|
| オブジェクトプール | exchange-core 系 | **p99 250µs→12µs（20×）**、スポーン 60× |
| プリミティブ配列・ボックス化排除 | JMH／async-profiler | 割当率 2〜4× 低下 |
| **ホットメソッドを 35 バイト未満に** | HotSpot `MaxInlineSize` | **インライン化だけで 7〜10×** |
| Stream をホットループで使わない | escape analysis がラムダで失敗 | 割当増 |
| ループ内文字列結合を避ける | 同上 | 毎回 StringBuilder |
| `Optional` をホットパスで使わない | 常に確保 | 同上 |
| SoA | ECS 系 | **エンティティ更新 ~6×** |
| 空間分割 | 同上 | 1000体で ~50× |
| オフヒープバッファ | LMAX／Aeron | GC 停止ゼロ |
| G1GC を維持 | 自社 note | ZGC は 12GB 以上かつ live-set 4× のみ |
| 割当率 30% 減 → GC CPU 30% 減 | 実測則 | **同じ梃子** |

**Escape analysis を壊す 6 パターン**: ラムダがインライン予算を超える／深いオブジェクトグラフ／
ループ内文字列結合／humongous 確保／`Optional`／毎回 `Pattern.compile`。

---

## F. AMD 固有（他社では得られないもの）

| # | 技術 | 状態 | 数値 |
|---|---|---|---|
| F1 | 占有率モデル（世代別） | **部品** | RDNA4: 96 VGPR で満占有率、granule 24 |
| F2 | ワークグループ 64 の倍数 | 設計則 | AMD 公式推奨 |
| F3 | wave32/wave64 選択 | 未計測 | wave64 は**レジスタ2倍**消費 |
| F4 | LDS 32 バンク・SoA | 設計則 | バンク競合回避 |
| F5 | 256B コアレシング | 未検証 | wave あたり 256B ブロック |
| F6 | 超越関数は 1/4 レート | 設計則 | テーブル化で回避 |
| F7 | Gather4 で VGPR 8→2 | 設計則 | — |
| F8 | RDNA4 の Hi-Z 欠陥回避 | 設計則 | Z プリパスで代替 |
| F9 | specialized constant でシェーダ変種 | 未使用 | 6 シェーダ固定中 |
| F10 | 非同期コンピュートで CPU/GPU 重複 | 未 | note 19 §3 |

---

## G. 実行順（結線を最優先）

1. **B3・B4・B5** — エンティティ系。数値が大きく（3.75×、20〜50%）、Mixin が浅い
2. **B1・B2** — パーティクル。TNT ラグに直接効く
3. **B6・B8** — キャッシュ。RAM と再構築スタッタ
4. **B7** — `Level.getBlockState`。**単体最大（〜2×）**だが Mixin 検証が要る
5. **B9・B10** — 並列メッシュと高さマップ
6. **E 系** — 各所で横断的に適用（特に 35 バイト制限）
7. **D 系** — Vulkan レンダラ着手と同時に結線

**各項目の完了条件**: 「テストが通った」ではなく **ゲーム実行中に呼ばれ、
オーバーレイか `/amdfaster stats` で効果が見えること**。
