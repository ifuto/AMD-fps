# 11 — Stage 2 実装記録：セクションメッシング（`net.amdfaster.mesh`）

実装日: 2026-10-07。CI（`./gradlew build` → `test` → `verifyJar`）で検証済み。

---

## 1. 実装したもの

| クラス | 役割 |
| --- | --- |
| `Orientation` | 6 面の軸整列朝向。法線、軸、反対面、**可視バケットマスク**、そして 4 頂点の巻き順テーブル |
| `Quad` | greedy meshing の出力。軸整列なので対角 2 点＋平面座標で完全に記述でき、4 頂点は導出 |
| `MeshletBuilder` | 同一朝向の quad を集めて meshlet 化。頂点を (position, uv) で dedup |
| `Meshlet` | 不変のクラスタ。packed AABB、GPU バッファ書き出し |
| `SectionMeshBuilder` / `SectionMesh` | 1 セクション分を 6 バケットに振り分けて保持 |

## 2. 確定した数値（すべて根拠つき）

| 項目 | 値 | 根拠 |
| --- | --- | --- |
| meshlet あたりの quad | **62**（= 124 三角形） | 論文の最適値「≤64 頂点 / ≤126 三角形」（`09` §4.2） |
| meshlet あたりの頂点上限 | **256**（8bit ローカルインデックス） | 62 × 4 = 248 ≤ 256 なので quad 数が必ず先に縛る（`09` §4.1） |
| カリングデータ | **4 バイト**（packed AABB, 6軸 × 5bit = 30bit） | 論文は球+コーンで 3 バイト（`09` §4.3）。AABB は 1 バイト増だが**軸整列ボクセルでは保守的でなく厳密**、かつコーン/apex が不要（法線はバケットが既に持っている） |
| position ストリーム | **8 B/頂点**（`short x,y,z` + `short flags`） | 8 頂点 = 128 バイト = RDNA のキャッシュライン（`02` §12）。position 専用ストリームは Z pre-pass を速くする（`10` §2.4） |
| attribute ストリーム | **8 B/頂点**（`float u,v`） | ストリーム分割（`10` §2.4） |
| インデックス | **6 B/quad**（8bit × 6、`0 1 2 0 2 3`） | **primitive restart index は避ける**（`10` §2.6）ので strip ではなく triangle list。8bit は `VK_EXT_index_type_uint8`、無ければ 16bit に拡張 |

## 3. 6 バケット分割（描画時におよそ半分の三角形を落とす）

`Orientation.visibleMask()` の判定（1 軸について）:

```
positive 面が見える  <=>  camera >= min
negative 面が見える  <=>  camera <  min + size
```

- **カメラがセクション内** → 両方残る（正しい。内部の面は見える）
- **カメラが 1 軸でセクション外** → 1 つだけ残る
- 遠くのセクションは **6 バケット中 3** → 三角形がおよそ半減

`SectionMeshBuilderTest#distantCameraDrawsHalfTheTriangles` が
「12 三角形のうち、カメラがセクション内なら 12、外なら 6」を固定している。

**なぜメッシュ構築時に分割するのか**（`09` §6.1 の原文）:
> "To apply this technique to regular chunk meshing, a chunk mesh would have to first be split
> into 6 sub-meshes **increasing driver overhead 6-fold**."

## 4. CI が捕まえた実バグ 2 件（テストを書いておいた効果）

### 4.1 `Orientation.axis` に ordinal を入れていた
`POS_X(1, 1, 0, 0)` と書いてしまい、**X 向き quad の axis が 1（Y）になっていた**。
`Quad.isDegenerate()` は axis で「平面座標を持つ軸」を判定するので、
Y 方向に普通に広がっている quad を「面積ゼロ」と誤判定 → **`SectionMeshBuilder` が黙って捨てる**。
エラーもログも出ず、**X 向きと Z 向きのジオメトリが全チャンクから消える**だけの故障になる。

`WindingTest` の `assertFalse(q.isDegenerate())` が最初の 1 行で止めた。

### 4.2 巻き順テーブルの POS_Y / NEG_Y 行が入れ替わっていた
テーブルは「enum 定数の隣に置いた ordinal インデックス配列」で、行 2 と 3 のコメントを
`POS_Y` → `NEG_Y` の順に書いていたが、実際の ordinal は `NEG_Y=2, POS_Y=3`。
結果 **NEG_Y が POS_Y の巻き順を継承**し、下向き面すべてが裏向きになる
（床が下から見えて上から見えない）。

`WindingTest#emittedWindingPointsAlongTheDeclaredNormal` が
「頂点順から計算した幾何法線 == 宣言された法線」を 6 面全部で検査して捕まえた。

**再発防止**: テーブルを **enum 定数のコンストラクタ引数**に移した。
行が定数から離れられないので、同じ間違いは構造的にできなくなった。
加えて `OrientationTest#axisIsTheAxisTheNormalLiesAlong` で
「法線が載っている軸 == `axis()`」を固定した。

## 5. テストが設計を変えた点：頂点 dedup はほとんど効かない

dedup のキーは **(position, uv)**。しかし **Minecraft の各ブロック面は自分の 0..1 UV を持つ**ので、
隣接ブロックが共有する頂点でも UV が違い、**共有されない**。

- 当初の実装コメントは「隣接 quad は 2 頂点ずつ共有するので greedy meshed セクションは
  1 quad あたり 2 頂点程度に収まる」と主張していた → **間違い**。テストを書いて判明した。
- dedup 自体は正しく残す（同一 quad の再追加などは効く）が、**頂点数を実際に減らすのは
  greedy meshing**（＝より少ない・より大きな quad）である。
- `MeshletBuilder` の Javadoc にこの事実と、次の計測候補を書いた:
  **UV を「quad ごとの origin + scale」で持てば position だけで dedup でき、
  隣接 quad が本当に頂点を共有する**。代わりに頂点ごとの SSBO 参照が 1 回増える。

## 6. GPU への書き出し規約

`Meshlet.writePositions/writeAttributes/writeIndices` は
**リトルエンディアン必須**（違えば `IllegalArgumentException`）で、**strictly sequential な書き込み**。

これは意図的で、`10` §2.1 の一次ソースに従っている:
> HOST_VISIBLE and non-HOST_CACHED type is uncached and write-combined.
> **Only write to it using memcpy or sequentially number-by-number. Avoid random accesses.**
> **Never read from it.**

→ 将来的に persistent mapped buffer へ直接メッシュを書くとき、
**read-modify-write を 1 回でもやると write-combining が崩れる**。
`positionsAreLittleEndianShorts` / `bigEndianBuffersAreRejected` でこの規約を固定している。

## 7. Stage 3 追記：greedy mesher（`net.amdfaster.mesh.voxel`）

§5 で「頂点数を減らすのは dedup ではなく greedy meshing」と判明したので、そこを実装した。

| クラス | 役割 |
| --- | --- |
| `VoxelView` | メッシャが読むブロックデータの抽象。**Minecraft 型を一切含まない**ので、合成ボリュームで単体テストできる |
| `ArrayVoxelView` | 平坦配列実装（テスト／オフラインツール用） |
| `GreedyMesher` | 6 朝向 × 各平面で 2D マスクを作り、右→下へ拡張して矩形化する古典アルゴリズム |
| `MergedFace` | 矩形 1 枚（orientation, plane, u0..u1, v0..v1, key） |
| `SectionMesher` | `VoxelView` → `SectionMesh`。矩形 1 枚 = `Quad` 1 枚、UV は矩形全体を張る |

### 設計上の要点

- **merge key には必ず light と tint を含める**。含めないと 1 ブロックの lightmap 値が
  結合された quad 全体に塗られる（greedy meshing の古典バグ）。`VoxelView.key()` の Javadoc に明記。
- **key は朝向ごと**。上面/側面/下面で見た目が変わるブロック（草など）に対応。
- **UV は origin + per-block scale**。4×3 に結合された面はスプライトを 4×3 回タイリングする。
  §5 で「次の計測候補」と書いた per-quad UV origin+scale が、ここで自然に採用された。
- **ボリューム外は air**。実装では隣接チャンクから 1 ブロックの縁を取る必要がある
  （さもないとセクション境界に不要な面が出る）。`VoxelView` の Javadoc に明記。

### テストが直した期待値 4 件

テストの期待値を先に手で計算したら 4 件間違っていた（実装のバグではない）:

1. 16×16 スラブの**側面は 1×16**であって 16×16 ではない。
2. Z 方向に並んだ 2 ブロックは、**POS_Z/NEG_Z だけ 1 枚**（共有面は不透明な隣に隠される）。
3. 同じ key で Z に並べると **Y 向きも結合される**（Y 向きでは v 軸が Z だから）。
4. **ランダムな塊では削減率は 24% 程度**。平面マスクが短い run ばかりになるため。
   「半減する」という期待は構造化ジオメトリ（スラブ・シェル）でしか成立しない。
   → ランダムテストは「必ず減る」「10% は減る」に緩め、強い主張は
   `aSolidSectionShellMergesToSixQuads`（**1536 面 → 6 quad**）に分離した。

### 検証済みの不変条件

`mergedFaceAreaAlwaysEqualsTheVisibleUnitFaceCount`:
12 種のランダムボリュームで **結合後の面積合計 == 可視ユニット面数** を確認。
greedy meshing は quad の枚数だけを変え、描画される表面積は変えない。

## 8. 次にやること

1. **Minecraft アダプタ**（`ChunkSection` / `BlockRenderManager` → `VoxelView`）。
   隣接チャンクからの 1 ブロック縁を含む。
2. **meshlet カルの compute shader**（GLSL 450、workgroup 64、
   packed AABB によるフラスタム＋背面コーンテスト、`InterlockedAdd` でコンパクション）。
3. **two-pass occlusion culling**（前フレーム Hi-Z、`09` §3）。
4. **Vulkan bring-up**（instance / device / swapchain / render graph）。
