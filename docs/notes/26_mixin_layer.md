# Mixin 層 ―― ゲームの呼び出し経路への配線

パイプラインは全部 CPU モデルとしてできていたが、ゲームのイベントに繋がっていなかった。
「配線していないのは禁止」なので、Mixin 層を作って接続した。

## 構成

`src/main/resources/amdfaster.mixins.json`（`fabric.mod.json` の `mixins` から参照）が
クライアント側 5 クラスを宣言:

| Mixin | 接続先 | 動かすもの |
| --- | --- | --- |
| `ClientPacketListenerMixin` | ブロック更新3種・光パケット | `LightRuntime` + `LightOnlyRebuildPlanner` |
| `LevelRendererMixin` | `allChanged()` | `MeshRuntime` |
| `ParticleEngineMixin` | `add(Particle)` | `CrossModParticleBudget` |
| `ParticleMixin` | `tick()` | 予算の返却 |
| `SoundManagerMixin` | `play(SoundInstance)` | `SoundRuntime`（8段の全部） |

## 設計上の決めごと

**Mixin は薄い。判断はしない。** 各フックは値を取り出してランタイムに渡し、
返ってきた答えをそのまま使う。理由は二つ: Mixin は再変換されるので
大きくすると他の mod と衝突しやすい。そして Mixin 内のロジックは
Minecraft 無しではテストできない ―― ランタイムに置いておけば JVM 単体でテストできる。

**null 許容。** どのフックもランタイム未起動なら何もしない。
`offerParticle` は `true`、`refuseSound` は `false` ―― つまり **バニラの挙動**。
Mod が初期化に失敗しても、ゲームは悪化せず動く。

**`@Inject` は HEAD + cancellable のみ。** 既存のコードを上書きしない。
他の mod（Sodium / ImmediatelyFast / Particle Core）と同じ場所に注入しても、
互いの判断は重ねて効く。どれか一つが拒否すれば止まる。

**`defaultRequire: 0`。** 対象メソッドが消えていても起動を止めない。
ただし `verifyMixinTargets` がビルド時に同じことを検査するので、
「黙って効かなくなる」のではなく**ビルドが落ちる**。

## 予算の返却（`ParticleMixin`）

`CrossModParticleBudget.release()` は対応しない解放に対して例外を投げる。
これは意図的で、二重解放は予算を無限に増やすバグの入り口。
だから呼び出し側が `heldBy()` で先に確認する。

`isAlive()` はまだ true のうちに呼ばないと間に合わない: 寿命切れのパーティクルは
tick 中に死に、その tick の間はまだ描画される。だから `tick()` の HEAD で判定し、
その tick は枠を消費させたままで、次 tick から数えない。

## 後から配線した2本（2026-10-19）

最初に「配線完了」と報告した時点で、まだ値が流れていない経路が2本あった。
**それは完全配線ではない。** 両方つながった（`798806a` 以降、ビルド緑）。

| Mixin | 注入先 | 動くもの |
| --- | --- | --- |
| `EntityRenderDispatcherMixin` | `shouldRender` | `EntityCullRuntime` → 距離/視錐台/予算/(深度ピラミッド) |
| `SectionRenderDispatcherMixin` | `setCameraPosition` | 毎フレームのカメラ読み取り（下の2つに共通） |
| `RenderSectionMixin` | `rebuildSectionAsync` | `MeshRuntime.offerRebuild` — 画面外のセクションは延期 |

### エンティティ側

フレームの最初の質問で**全部集めてから判定し、その結果で答える**。
1フレーム遅れないことが重要で、遅れるカリングは「見えるようになった1フレーム後に現れる」
という見た目に出る不具合になる。

**カメラの基底は `Camera.NearPlane` から読む。** 四隅がスクリーンの四隅の世界座標なので、
上辺の差が screen right、左辺の差が screen up、それぞれの半分の長さがニアプレーンの半幅。
射影のスケールがそこから出るので、非正方形ピクセルのアスペクトに別の項は要らない。

yaw/pitch から復元しなかった理由: Minecraft の yaw は **0が南(+Z)で、西に向かって増える**。
ここを取り違えると screen-x の符号が反転し、**identity 行列を使うテストでは全て通り、
ゲーム内では遮蔽判定が全部鏡像になる**。`cull/ViewProjection` が軸を入力に取るのはそのためで、
規約は呼び出し側（＝ゲーム）が既に決めている。

- 距離上限は**クライアントのレンダー距離に追従**（固定値にしない）。
  固定値は「プレイヤーに見えるエンティティを消す」ことで、「見た目は変化しない」が崩れる。
- カメラが乗っているエンティティは絶対にカリングしない。
- 判定できない場合（runtime無し/レベル無し/カメラ無し/ニアプレーン退化）は**常に true**。
  外すカリングは世界の穴で、この経路が唯一避けるべき失敗。
- フレームトークンが 100ms 動かなかったら再計算する。トークンはレンダー経路のフックが
  bump するので、**その経路を差し替える mod（Sodium）が来ると止まる**。古い集合で答え続けるのは
  「プレイヤーが離れたカメラでカリングする」ことなので、集合に寿命を持たせた。

### メッシュ側

`rebuildSectionAsync` を拒否すると**セクションは dirty のまま**なので、
次のフレームにまた聞かれる。つまり**延期であって損失ではない**。
忘れる仕組みが要らず、永久に古くなることもない。
画面内は常に通し、画面外は1フレーム2本まで（`MAX_SPECULATIVE_REBUILDS_PER_FRAME`）。

## 検証で見つかった本当のバグ

`EntityCullRuntime` の生存者集合が**件数だけを0にして中身を消していなかった**。
オープンアドレス法の集合は中身そのものなので、前フレームの id がスロットに残り、
`survived()` がそれを見つけて「いる」と答える。テスト2本が同時に落ちた。
**余分に描くのはフレーム計数には出ず、画面には出る。**

## 到達可能性の歩き方を厳しくした

javadoc の `{@link X}` を辺として数えていたため、
`DrawCallMerger` / `EntityBatcher` / `AdaptiveSoundCutoff` / `SurfacePreferences` /
`Swapchain` / `StructurePieceCache` の6クラスが「到達可能」に見えていた。
import・完全修飾名・同一パッケージ言及だけを辺としたら **6クラスは散文からしか到達していなかった**。
全て配線済み（338/338）。**数を良くするために規則を緩めないこと。**
