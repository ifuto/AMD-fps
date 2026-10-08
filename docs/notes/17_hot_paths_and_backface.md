# 17. ホットパスの高速化、フレーム全体のスキップ、メッシュレット単位のバックフェースカリング

**方針**: 見た目を**一切**変えないものに限定。近似や閾値で「だいたい同じ」にするものは入れない。
入力が同一なら出力が同一、という形で正当性を言えるものだけ。

---

## 1. ホットパスから超越関数と分岐を外す

### 1.1 `selectLevel`: `log` 2 回 → ビットカウント 1 回

```java
// 変更前
int level = (int) Math.ceil(Math.log(longest) / Math.log(2.0));
// 変更後
int n = longest >= 2147483647f ? Integer.MAX_VALUE : (int) Math.ceil(longest);
int level = 32 - Integer.numberOfLeadingZeros(n - 1);
```

`Math.ceil` は SSE 1 命令、`numberOfLeadingZeros` は `lzcnt` 1 命令。置き換えたのは **libm 呼び出し
2 回と除算 1 回**。これは meshlet ごと・エンティティごとに毎フレーム走る。

**同一性の検証**: 1〜4096 を**倍ごとに 1024 分割**した密な掃引 + 2 の冪 ±1（100 万画素まで）で
**不一致 0**。

**`log` 版は「たまたま正しかった」。** 両オペランドが不正確で、商が整数に最も近づくのは
**ボックスサイズが 2 の冪のとき**に限られる。そこで 1 ステップ高く丸まるとレベルが 1 つ粗くなり、
footprint がボックスより広がって**無関係な幾何が min に入る**。実測では libm は正しい側に落ちるが、
契約上そうあり続ける保証はない。
（**なお精度そのものは問題ではなかった**: 2 の冪周辺 73 点で不一致 0、float32 の `log2` も
2 の冪で正確。バグだと疑った私の見立ては外れで、直したのは速度と契約の方。）

シェーダも `log2` → `findMSB` に揃えた。**CPU 側ミラーがテストで固定している対象なので、
両者が「おそらく一致」ではなく「正確に一致」する必要がある**。食い違うと、
どちらの経路が答えたかでオブジェクトが出たり消えたりする。

### 1.2 `RebuildScheduler`: band を 2 回計算していた

counting sort は band を 2 回必要とする（バケットサイズの算出と散布）。
**並列配列に保存して 1 回にした** → pending セクションごと・毎フレームの sqrt と射影が半減。

### 1.3 `Frustum.intersectsAabb`: 18 の予測不能分岐 → 0

```java
// 変更前: 面ごとに 3 つの三項演算 = 6 面で 18 の予測不能分岐
float px = nx >= 0f ? maxX : minX;
// 変更後: 両端を掛けて max。Math.max は maxss
float support = Math.max(nx * minX, nx * maxX)
              + Math.max(ny * minY, ny * maxY)
              + Math.max(nz * minZ, nz * maxZ);
```

依拠する恒等式は `nx * maxX >= nx * minX ⟺ nx >= 0`（`maxX >= minX` のとき）。
**40 万のランダムな面／ボックス組 + 幅ゼロのボックス + 零法線でビット一致**。

---

## 2. フレーム全体のスキップ（`cull/CullReuse`）

**回避できる作業として最大のもの。** 静止していることは珍しくない。
カメラ・視野角・ビューポート・セクションメッシュが**ビット単位で同一**なら、
決定論的関数の出力も同一 — **近似ではなく厳密に**。再利用すれば
frustum 平面抽出・両コンピュートディスパッチ・indirect draw の再構築が丸ごと飛ぶ。

### カメラは生ビットで比較する

素の `==` だと:
- **`0.0f == -0.0f` は true** → 原点を**負のゼロ経由で**跨いだカメラが「動いていない」ことになる
- **`NaN != NaN`** → 比較が常に「変わった」と答える

`Float.floatToRawIntBits` は両方を正しく扱う。

### 意図的に無効化**しない**もの 2 つ

| 事象 | 判定 | 理由 |
|---|---|---|
| **ライトのみのリビルド** | 無効化しない | 位置・UV・インデックスに触れないので meshlet は動かない。**リライトはリビルド交通量の大半**なので、ここで無効化するとほぼ毎フレーム再利用を捨てることになる |
| **エンティティの移動** | 別エポック | モブは毎フレーム動く。地形のエポックに畳むと、**モブの居る場所では再利用が永久に効かない** |

無効化**する**もの: カメラ位置／朝向、視野角（望遠・ズームはカメラを動かさず frustum を変える）、
ビューポート（リサイズは aspect とピラミッドの両方を変える）、**セクションの幾何**リビルド。

---

## 3. メッシュレット単位のバックフェースカリング

メッシュレットは **1 つの朝向バケットから作られる**ので、中の全面が法線を共有する。
だからバックフェース判定が**比較 1 回**になる: +X 面は +X 側からしか見えないので、
カメラがその軸の最近面平面と同じ側か後ろに来たらメッシュレット全体を落とせる。

**ラスタライザのバックフェースカリングの上に載るもので、置き換えではない。**
節約できるのは**頂点シェーディングとドロー**。フラグメントは元々問題ではなかった。

### 向きは 3 bit 必要で、空きは 2 bit だった

`packedBounds()` は 6×5 = 30 bit を使い、bit 30-31 が空く。向きは 0..5 で **3 bit 必要**。
代替案はどれも悪かった:

| 案 | 却下理由 |
|---|---|
| bounds から 1 bit 借りる | メッシュレットが 8 ブロック幅に制限される |
| セクション原点ワードの上位ビットに隠す | **ワールドが高くなった日に壊れる** |
| **別バッファ（採用）** | meshlet あたり 4 バイト。メッシュデータ 5 332 バイトに比べれば誤差 |

### root signature のコストはゼロ

**root signature は「バインドされた descriptor *セット*」ごとに 1 DWORD で、
セット内のバインディングごとではない。** カリングパスはそのセットを既にバインドしているので、
6 本目のバインディングは**タダ**。

最初私はこれを「バインディング 1 本 = 1 DWORD」と見積もってテストを書いたが、
`RootSignatureBudget` を読むと**セット単位**だった。**独自モデルでテストを書くのではなく、
実際の会計に対して書く**ことに直した（13 DWORD 予算に対し現状 3 DWORD = push constant 2 + セット 1）。

### 正しさはオプトインに依存する

両面描画される面を含むメッシュレットを culled すると、**玩家に見える面を消す**。
だから宣言は **quad 単位で、AND で畳む**:

```java
public void add(Quad quad, boolean quadIsSingleSided) {
    this.singleSided = this.quadCount == 0
            ? quadIsSingleSided
            : this.singleSided && quadIsSingleSided;
```

**sticky フラグにすると、アルファテストの quad が先に到着したか後に到着したかで答えが変わる。**
それはチャンクによって面がちらつくバグになる。順序に依存しない形は AND だけ。

`VoxelView.isSingleSided` の既定は **`false`（＝カリングしない）**。
考えていない view は最適化を失うだけで、幾何は失わない。
`ArrayVoxelView` は不透明ボクセルに紐づく key に対して true を返す。

`reset()` は宣言をクリアする。**ビルダは全セクションの全バケットで使い回される**ので、
宣言がメッシュレットより長生きすると、頼んでいない後続を黙って culled する。

### bit 30 の配置

6 つの 5 bit フィールドが bit 0..29、フラグが bit 30、**bit 31 は空のまま**。
だから packed bounds は決して負にならず、シェーダの符号なしシフトが全部正しいまま。

---

## 4. CI が捕まえた自分の誤り 4 件

| # | 誤り | 実態 |
|---|---|---|
| 1 | `assertEquals(0.0f, -0.0f)` | **JUnit 5 の float 比較はビット比較**なので失敗する。Java の `==` について言いたいのなら `assertTrue(0.0f == -0.0f)` |
| 2 | 「far plane の外」の箱 z ∈ [0.5, 5] | **far 面（z=1）をまたいでいる**。AABB 検査は保存的なので `true` が正しい。一部でも内側なら内側 |
| 3 | `@Override` を含むパターンで置換しなかった | 元の `@Override` が孤立して**二重**になり、`color()` の注釈が消えた。コンパイル不能 |
| 4 | binding 5 を base に追加 | `bindingNumbersAreUniqueAndDense`（0..n-1 で稠密）と「frustum セットは occlusion セットの prefix」を両方壊した。**番号を振り直した**（orientations=4, hiZ=5） |

2 件目と 4 件目は**既存テストが正しい設計不変条件を持っていた**例で、テストが仕事を果たした。

### near/far bound を判別できないテストを書きかけた

メッシュレットがカメラを跨いでいない場合（両面とも lit 側）、near bound を使っても
far bound を使っても同じ答えになり、**テストが何も検証しない**。
セクション原点 −5 で面が z = −3 と z = 5 になる配置に変えた:

```
POS_Z 面 2..10、セクション原点 −5 → near 面 z=−3、far 面 z=5
  near bound 判定 → False（残す = 正しい）
  far  bound 判定 → True （消す = 誤り、lit な面を削除する）
```

**期待値は全部、書く前に平面の式から計算した。** バックフェースのテストでは
符号を 2 箇所間違えて書きかけ、Python で全ケースを計算し直して書き直している。

---

## 5. 成果

| 項目 | 内容 |
|---|---|
| 新規クラス | `cull/CullReuse` |
| 変更 | `cull/PyramidGeometry`（`selectLevel`）, `cull/Frustum`（branchless）, `cull/MeshletCuller`（`isBackFacing`）, `dirty/RebuildScheduler`（band 1 回）, `mesh/Meshlet`（bit 30）, `mesh/MeshletBuilder`（quad 単位宣言）, `mesh/SectionMeshBuilder`, `mesh/voxel/{VoxelView,ArrayVoxelView,SectionMesher}`, `gpu/{CullBindings,MeshletGpuLayout}` |
| シェーダ | `meshlet_cull.comp`（バックフェース + binding 4）, `meshlet_occlusion.comp`（`findMSB` + binding 5）, `entity_occlusion.comp`（`findMSB`） |
| テスト | 482 → **516**（+34） |
| CI | `4a0a5cb` → `8039696` → `1e9baa3` → `2131fa2` → **`f6a7165` success** |
