# 29. 実描画ベンチ — 推定をやめてラスタライズして測る

## なぜこれを書いたか

`docs/notes/28_three_x_target.md` までの FPS は全部**算数**だった。フレーム内訳に割合を掛けて
比率を出す。それは一度、致命的な形で間違えた: 「削減不能コスト 4.0 ms」という黒箱が、モデルが
報告できる倍率を 7.72 / 4.0 = **1.93 倍**で固定していた。コードを一行書く前に、目標の 3 倍が
到達不能になっていた。コストモデルは自己矛盾がなくても虚構になり得る。

なので**描画する**。`src/test/java/net/amdfaster/bench/`。

## 何を実行しているか

- **実アセット** — `bench/assets/` に本物の PNG 25 枚と本物のモデル JSON 13 個。
  `bench/tools/make_assets.py` が生成し、コミットされている。PIL が無い環境なので PNG
  エンコーダも書いた（IHDR / filter-0 スキャンラインの IDAT / IEND、各チャンク CRC32）。
  Java 側は `javax.imageio.ImageIO` で読む。**ファイルが無ければ例外**で、手続的フォールバックは
  しない。フォールバックするベンチは自分のフォールバックを測る。
- **実ワールド** — 176×64×176 ブロック。2 オクターブの値ノイズ、海面、木、村と洞窟（シナリオ B）、
  爆発跡（シナリオ C）。セクション 16³ にメッシュ化、隣接面カリング。
- **実ラスタライザ** — 1280×720、遠近補正（u/z, v/z, 1/z を線形補間して最後に割る）、
  深度は 1/z をそのまま比較（reversed-Z が無変換で出る）、近平面クリップ、スキャンラインは
  エッジ関数の加算のみ。cutout / blend 両対応。
- **実クラスの呼び出し** — AMD-Faster 側は `HierarchicalFrustumCuller`
  `MeshletFaceMaskCuller` `NormalConeCuller` `SoftwareRasterizer` `TileDepthBuffer` `SortKey`
  `SortKeyRadixSort` `TranslucentSortCache` `EntityLodSelector` `EntityBatcher` `ShadowCuller`
  `AnimationSkipper` `StaticGeometryBaker` `HudUpdateScheduler` `GuiBatcher`
  `AnimatedTextureScheduler` `CloudMeshCache` `WeatherMeshCache` `FastRandom` `ViewProjection`
  `Frustum` を呼ぶ。**スタブは無い。**

## 二つのパイプラインの差

| 区分 | 全Mod入り（ベースライン） | AMD-Faster |
| --- | --- | --- |
| 地形 | セクション単位の視錐台＋距離、前→後ろソート | 階層視錐台 → ソート → **Hi-Z オクルージョン** → **面方向マスク**＋法線コーン |
| エンティティ | 視錐台＋距離、常にフル詳細 | ＋Hi-Z、**LOD**、インスタンスバッチ、影カリング |
| ブロックエンティティ | 箱＋蓋を毎フレーム | **静的箱はベイク**、蓋は `AnimationSkipper` が動いた時だけ |
| パーティクル | 全部描く | 視錐台カリング＋`FastRandom` |
| GUI | 毎フレーム要素リスト再構築 | `HudUpdateScheduler` で再利用、`GuiBatcher` で合バッチ |
| 雨 | 毎フレーム 600 クアッド再構築 | `WeatherMeshCache`、オフセットのみ |
| テクスチャアニメ | 毎フレーム補間 | `AnimatedTextureScheduler`、時刻由来で間引き |

## このベンチに見えるものと、見えないもの

**見える:** サブミッションされるジオメトリ量とフィル量。両パイプラインで 1 ピクセルと
1 頂点の単価は同一なので、比率は「どれだけ少なく出したか」そのもの。

**見えない:** **GL ドローコールの代価。** ソフトウェアラスタライザにドライバは無い。だから
`EntityBatcher` が 300 ドローを 9 に減らしても、ここでは**回数としてしか出ない**。AMD の GL
ドライバは NVIDIA の約 21 倍ドローコールが高い（Khronos フォーラムの実測）ので、実機では
この回数差が勝率の大きな一部になる。**このベンチはその分を過小評価する。** 隠さずに書く。

同様に `HudTextureCache` の blit 経路は GPU 側の勝ちで、CPU ラスタライザでは全屏 blit の方が
高くつく。だから計上していない。

## 正しさのガード

倍率だけを見ると「描画をサボれば速い」で勝ててしまう。なので
`MIN_PIXEL_RETENTION = 0.90` を入れた。**AMD-Faster がベースラインの 90 % 以上のピクセルを
描いていなければ、カリングが可視ジオメトリを消していると判断してテストを落とす。**
オクルージョンで消えたジオメトリは元々ピクセルを生んでいないし、背面も生んでいないので、
正常ならこの値はほぼ 1.0 になる。マスクか行列が間違っていれば、FPS は上がってこの値が下がる。

## 数字の読み出し方

CI のログはこの環境からダウンロードできない（`api.github.com` から Azure blob へ 302 で遮断）。
読めるのはアノテーションだけで、`reportTestFailures` は失敗メッセージの**先頭 4 行**を出す。
なので測定値はアサーションメッセージに入れてある。3 倍に届くまでテストは赤く、
届いた瞬間に緑になる。

## 環境メモ（重要）

- **ローカルに javac は無い。** PyPI の `jdk4py` は **JRE しか入っていない**
  （`jdk.compiler` モジュールが無く `bin/javac` が無い）。`jdk` / `pyjdk` / `jdkman` /
  `install-jdk` は全て外部ダウンローダで、Adoptium は許可ホストに無い。apt のソースも無い。
  ⇒ **Java のコンパイラは CI だけ。**
- `/tmp` は bash 呼び出しの間で消える。アセットとベンチはリポジトリ内にある。
- ベンチは `src/test/java` に置いたので `build.gradle` の変更は不要。`./gradlew build` の
  `test` タスクがそのまま実行する。
