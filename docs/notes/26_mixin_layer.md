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

## 未接続（意図的）

- **エンティティのカリング経路**: `EntityWorldRuntime.cullEntities` は
  `EntityCuller`/`HiZ`/`EntityOcclusionCuller` を全部使うが、バニラの描画ループに
  「このエンティティを描かない」を伝える注入先が未確定。`LevelRenderer.renderLevel`
  のシグネチャが JOML + `GpuBufferSlice` に変わっており、エンティティ描画の入口を
  もう一段調べる必要がある。
- **チャンクメッシュの即時リセット**: `LevelRenderer.allChanged()` は記録のみ。
  再構築の投入数を絞るにはメッシュビルドの投入経路が要る。
