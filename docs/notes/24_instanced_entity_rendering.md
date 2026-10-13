# 24. インスタンス化エンティティ描画（Rentities 方式）と AMD での勝ち筋

最終更新: 2026-10-13

## 1. Rentities とは（実在確認済み）

GitHub `samveerone-hue/Rentities`。バニラが**画面内のエンティティ 1 体につき 1 draw call**を
出しているのを、エンティティ描画ループをインターセプトして**同じ型のエンティティを 1 回の
インスタンス draw call に圧縮**する Mod。位置・回転・アニメーション状態を **SSBO** に一括
アップロードし、ボーンごとのアニメーションを**自作 GLSL 頂点シェーダで GPU 計算**。

- 公表ベンチ: **平均 FPS +155%、1% Low +107%**
- 要件: **NVIDIA GPU（GTX 10 以降）**、Fabric Loader 0.17.2+、**Sodium 0.8.13**、MC 1.21.11
- Nvidium 非依存、共存可
- 中核の頂点シェーダは **21 種類のアニメーションを扱う 500 行超の GLSL**
- 既知の不具合: アーマースタンドのポーズ、エンティティの向き、バニラ正確なアニメーション、
  テクスチャ UV の向き
- **Planned に "AMD and Intel support" がある＝未対応。ここが我々の入り口**

類似先行実装 **Zeolite**（display entity 向け）: インスタンス記録を **std140 UBO 配列**に置き
`gl_InstanceID` で読む（ドライバの UBO 上限のため **1 チャンク約 800 体**）。100 tick 変化の
ないものは静的層へベイク。GPU アニメーションプログラムを頂点テクスチャで評価。

## 2. **1.21.11 は OpenGL である（自分のノートの誤りを訂正）**

`docs/notes/04` 等に「1.21.11 で Blaze3D が Vulkan 化」と書いていたが**誤り**。

- **1.21.11（2025年後半、pack format 75.0）のレンダラは OpenGL**
- Vulkan 化は **26.2 以降の Vibrant Visuals** で、**2026-02-18 に Mojang が発表**。
  スナップショットで OpenGL と並行提供し、最終的に OpenGL を削除する計画
- 26.2 の公開 Blaze3D API でも **SSBO・GPU カリング・間接描画は出せない**（Voxyrium の
  Blaze3D バックエンドが「できないこと」として明記）

**したがって 1.21.11 では SSBO と GLSL を直接使うのが正解**で、妥協ではない。

## 3. AMD で Rentities より速くする要点

頂点シェーダでボーン行列を評価すると、**同じ行列を頂点ごとに再計算する**。

| 方式 | 500 体・120 頂点・12 ボーンの行列評価回数 |
|---|---|
| 頂点シェーダで評価（Rentities） | **720,000 回/フレーム** |
| CPU か compute で 1 回だけ（本実装） | **6,000 回/フレーム** |

**120 分の 1。** しかも評価の本体は `sin`/`cos` で、**RDNA では 1/4 レート、Xe では 1/8 レート**。
この Mod が対象とするハードウェアで最も高い演算を、最も回数の多い場所でやっている。

代償はアップロード帯域: **24 ボーン = 1.5 KiB/体**、500 体で **0.73 MiB/フレーム**、
1000 体で 1.46 MiB。インスタンス化が意味を持つエンティティ数では、演算側の方がはるかに大きい。

**さらに良い手は compute パス**で同じ評価を GPU 側でやることで、120 倍の削減を
アップロードなしで得られる。`BonePalette` の評価はリグと角度だけの純関数にしてあるので、
パイプラインの他を触らずにそこへ移せる。

## 4. 既存実装との接続（重複を作らない）

`entity/` には既に 13 クラスあり、**バッチ化（draw call 削減）は完成している**:

- `EntityInstance` — `modelKey, x, y, z, yaw, light, halfWidth, height`
- `EntityBuffer` — SoA 格納
- `EntityGpuLayout` — **32 B/インスタンス、16 B 整列**（理由まで javadoc に記載）
- `EntityBatcher` → `EntityBatchSet` / `EntityBatch(firstInstance, instanceCount)`

**欠けていたのはアニメーション半分**（`EntityInstance` にポーズもボーンも無い）。
今回追加したのはそれだけ:

- `entity/BoneRig` — 階層を配列化。**親が子より前**であることを構築時に強制
- `entity/BonePalette` — 全ボーンを 1 回だけ評価して連続バッファへ
  （`instanceIndex * 24 + boneIndex`、列優先 mat4、テスト14件）

シェーダは `src/main/resources/shaders/entity_instanced.vsh`。
binding 0 = 既存の 32 B レコード、binding 1 = ボーンパレット。

### 行列の規約（Python で検証して固定）

`world = parentWorld × translate(base) × rotate(axis, angle)`

translate が先なので**ボーンは自分の base 位置を軸に回り、base 自体は回転しない**。
結果、world 行列の平行移動列は base オフセットの素直な合計で、アニメーションを載せるのは
回転部だけ。5 ボーンのリグで検証: base (2,-6,6) の脚を X 軸 0.6 rad 回しても平行移動は
(2,-6,6) のまま。

## 5. 実装済み（このノートの範囲）

| クラス | 役割 | テスト |
|---|---|---|
| `entity/BoneRig` | 階層を配列化。親が子より前であることを構築時に強制 | 14（`BonePaletteTest`） |
| `entity/BonePalette` | 全ボーンを体ごとに1回だけ評価（CPU 経路） | 同上 |
| `entity/EntitySkinnedMesh` | 64 B/頂点のスキニング形式。重みを正確に 1 へ正規化 | 13 |
| `entity/EntityAnimation` | 21 チャンネル → ボーン角度。**反対の脚は符号反転** | 10 |
| `shaders/entity_instanced.vsh` | インスタンス描画。**アニメーションを評価しない** | — |
| `shaders/entity_bones.comp` | ボーン評価の compute 版 | — |

## 6. compute 版の並列化（依存関係の解き方）

ボーン b の world 行列は親の行列を必要とする。1 ボーン = 1 起動にすると**同一ディスパッチ内の
起動順を保証できない**。自明な2案はどちらも採用しなかった:

- **エンティティごとに1ワークグループ** → ボーンが1スレッドに直列化する
- **階層レベルごとにバリア** → レベル構造をリグと一緒にアップロードする必要がある

**採用したのは、各起動が自分のボーンからルートまで遡って降りながら累積する方法。**
同期が一切不要で完全に並列。代わりに行列乗算が 1 回ではなく depth 回になる。
バニラのエンティティモデルは 3〜4 段なので、**直列版の約4倍の演算をして直列化を回避する**。
遊休レーンが数千ある GPU で階層がこれだけ浅いなら、これが正しい取引。

## 7. 未完了

- バニラの `ModelPart` からの**リグとスキニングメッシュの抽出**（残る最大の作業）
- `EntityBatcher` の出力を実際の instanced draw へ**結線**
- **測定**。+155% は Rentities の NVIDIA 上の数字で、そのまま期待しない
- **CPU 経路と compute 経路のどちらが速いかの実測**。500〜2000 体では CPU 経路の
  アップロード（0.73〜2.9 MiB）と compute の起動オーバーヘッドのどちらが勝つかは未確定
