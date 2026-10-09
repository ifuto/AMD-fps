# 18. ウェーブ協調アトミックと、GPU へ積むコードの欠落

`docs/notes/09` の §5 に**実測値つきの**知見が既にあったのに、カリングパスを実装したときに読んでいなかった。
ユーザーからの指摘（「memo取っただけで参考にしてないの何」）は正しく、これは実際に損失を出していた箇所。
以後、実装前に該当ノートを grep して、書いてあることを反映する。

## 1. 1 ウェーブ 1 アトミック（RE Engine 実測 10 倍）

### 問題

カリングディスパッチで生き残ったレーンは全員が同じカウンタに `atomicAdd` する。

```glsl
atomicAdd(visibleCount, 1u);   // ← ディスパッチ中の全生存レーンが同じアドレスへ
```

このアドレスがパス中で最も競合する場所で、トラフィックは**ウェーブ数ではなく可視メッシュレット数**に比例する。
`meshlet_cull.comp` は `visibleCount` と `testedCount` の 2 本をこうやっていた。

### 解（DXC wave-intrinsics wiki / Drobot SIGGRAPH 2017 / Interplay of Light で同一の形）

1. 述語を**1 回だけ** `subgroupBallot`
2. `subgroupBallotExclusiveBitCount` → そのレーンのウェーブ内スロット
3. `subgroupBallotBitCount` → ウェーブ合計
4. `subgroupElect()` のレーンだけが 1 回 `atomicAdd`
5. `subgroupBroadcastFirst` でベースを全レーンへ

```glsl
uvec4 visibleMask = subgroupBallot(visible);
uint laneSlot    = subgroupBallotExclusiveBitCount(visibleMask);
uint waveVisible = subgroupBallotBitCount(visibleMask);

uint visibleBase = 0u;
if (subgroupElect()) {
    if (waveVisible != 0u) {
        visibleBase = atomicAdd(visibleCount, waveVisible);
    }
}
visibleBase = subgroupBroadcastFirst(visibleBase);

if (visible) {
    uint base = (visibleBase + laneSlot) * 5u;
    ...
}
```

DXC wiki の主張は 2 つあって、2 つ目が意外と効く:
> we only issue one atomic for the entire wave, which reduces contention
> **and keeps the output data for each lane in this wave together in the output buffer**

競合低減だけでなく**出力の局所性**も得る。同一ウェーブの生存レーンが連続スロットに入る。

**RE Engine 実測**: 1 クラスタ/ウェーブで **0.543 ms** → 協調アペンドで **0.051 ms**。10 倍超。

### 実装で嵌る 3 点（すべて実際に踏んだ）

**(a) ballot より前で `return` してはいけない。** subgroup 命令は**アクティブなレーンしか見ない**。
早期 return したレーンはマスクから消え、ウェーブ合計が短くなる。リスト自体は正しく詰まるのに
カウンタだけが過少になる、という**ディスパッチ末尾のジオメトリが消える**症状になる。

`meshlet_cull.comp` の `main()` は早期 return を全て廃し、`bool inRange = index < meshletCount;` を
述語として ballot に畳み込む形にした。`meshlet_occlusion.comp` は早期 return を残しているが、
ballot は末尾にあり、return したレーンは非アクティブなので**除外される方が正しい**。

**(b) `lane 0` ではなく `subgroupElect()`。** 返るのは**最小のアクティブレーン**。
メッシュレットを持たないレーンが混ざる場合にこれでないと壊れる。

**(c) ウェーブが何も出さなければアトミックごと飛ばす。**
ディスパッチはワークグループ境界に切り上げるので末尾ウェーブは大半が空。
値 0 のアトミックでも競合アドレスへの往復は発生する。

### GLSL 4.50 での前提

```glsl
#extension GL_KHR_shader_subgroup_basic : require   // subgroupElect, subgroupBroadcastFirst
#extension GL_KHR_shader_subgroup_ballot : require  // subgroupBallot 系
```

subgroup 命令は**ドライバが報告する実際のウェーブ幅に追従**するので、wave32 の RDNA と
wave64 の GCN で条件分岐もコンパイル時選択も要らない。`local_size_x = 64` のままで両対応。

**述語ごとに ballot は 1 回。** `subgroupExclusiveAdd` と `subgroupAdd` を別々に呼ぶのは同じ述語の
2 回計算で、コンパイラによってはマスクをキャッシュせず `OpGroupNonUniformBallot` を 2 個出す
（Slang issue #12847）。1 回 ballot して両方を導出する。

## 2. Aokana §1 — 自チャンクだけ保存的でない

`docs/notes/09` §1（Aokana, arXiv 2505.02017）が明示している、保存的 AABB 深度テストが
**保存的でなくなる唯一の場合**。

- カメラを**含まない**箱: 箱は実ジオメトリの上位集合なので、最近点は実際の最近面より手前か同じ。
  深度を過小評価 → テストは「残す」側に倒れる → **安全方向**。
- カメラを**含む**箱: 最近点はカメラ自身。算出深度は取り得る最小値なのに、箱が表すジオメトリは
  プレイヤーの周囲全部。比較は「この箱は壁より近い」と読むが、そのフットプリントは全面が壁。
  → **プレイヤーが立っているセクションをカリングする**。

本実装では `clip.w <= 0.0` ガードがこれを塞いでいる:
カメラを含む箱は corner の 1 つがカメラ面より後ろ → `clip.w < 0` → 投影不能 → **残す**。

- `entity_occlusion.comp:105` と `meshlet_occlusion.comp:99` — 両方に存在を確認
- `HiZ.projectToPixels` の Java 側は `HiZ.java:109` の `if (clipW <= 0f) return ScreenRect.everything();`
- `nearestDepth` の `clipZ / clipW` は信頼性チェックの**後**でしか呼ばれないのでゼロ除算は起きない

**自セクション専用の特殊ケースは不要**という結論。ただしこれは偶然ではなく、
「深度比較がたまたま通ったから残った」のではガードにならない。テストは
`keptUnreliableProjection == 1` を明示的に見て固定した
（`EntityOcclusionCullerTest.anEntityWhoseBoxContainsTheCameraIsNeverCulled`）。

## 3. cull データを GPU に積むコードが**存在しなかった**

監査で残っていた項目を確認したら、想定より広かった。

- `MeshletGpuLayout` はバッファの**サイズを計算していた**（`cullDataBytes()`, `orientationBytes()`）
- `CullBindings` は**バインディング 4 と 5 を宣言していた**
- 両カリングシェーダは**それを読んでいた**
- しかし境界もオリエンテーションも**書き込むコードが無かった**

さらに `Meshlet` には `writePositions` / `writeAttributes` / `writeLights` / `writeIndices` が
あったが、**`src/main/java` のどこからも呼ばれていなかった**（grep で確認）。
つまり 1 箇所の漏れではなく、アップロード経路が未接続だった。

追加: `MeshletGpuLayout.writeCullData(SectionMesh, ByteBuffer records, ByteBuffer orientations)`。
オリエンテーション順に**1 回のウォーク**で両バッファを書く。

1 回のウォークである理由: レコードとオリエンテーションでスロットがずれると、
あるメッシュレットの境界に**別バケットの法線**が組になり、バックフェイスカリングが
**間違ったジオメトリを落とす**。症状は「特定の角度からだけ面が消える」だけ。

末尾で `slot != meshletCount` を throw。レイアウト構築後にセクションが再メッシュされると
バッファ末尾に前セクションのデータが残るので、黙って積まずに落とす。

## 4. 原点エンコーディングのバグ（3 の中で見つけた）

両シェーダが `uvec4` の `record.yzw` に対して:

```glsl
vec3 origin = vec3(record.yzw) - cameraOrigin.xyz;   // ← uint → float 変換
```

セクション原点は**ワールド原点より西・北の全セクションで負**。
2 の補数の負を uint→float で読むと小さくならない:

| 原点 | 旧シェーダが読む値 |
|---|---|
| −32 | **4.29497e+09** |

箱が 40 億ブロック先へ飛び、フラスタムテストが弾く。**エラーは一切出ず、地形が消える。**

修正: float ビットで格納し `uintBitsToFloat` で読む。
セクション原点は 16 の倍数なので float の 24 bit 厳密範囲内で、**損失ゼロ**。
`MeshletGpuLayoutTest.aNegativeOriginSurvivesTheFloatEncoding` が
−1048576 / −16 / −1 / 0 / 1 / 1048560 で厳密性を固定。

## 5. CI に caught された失敗（今回）

`CullShaderTest` がシェーダソースの**部分文字列を固定**していて、`main()` の再構成で 2 件落ちた。
どちらも意図は正しく、リテラルだけが古くなっていた。

- `"slot * 5u"` → `"(visibleBase + laneSlot) * 5u"`。
  新アサーションは**両方の半分が在ること**を見る。どちらか一方だけだと別のバグになる
  （ウェーブベースだけなら 1 ウェーブの生存者全員が同一コマンドへ、
  レーンオフセットだけならウェーブ同士が上書きし合う）。
- `"if (index >= meshletCount)"` → ballot に畳み込んだ述語。
  このシェーダでは早期 return がもう使えないので、ガードと
  「除外されているのが末尾レーンであること」の両方を固定した。

**教訓**: シェーダソースを文字列で固定するテストは、意図（1 draw = 5 DWORD、
ディスパッチ末尾をガードする）を書き、リテラルは式が変わったら更新する。
ローカルにコンパイラが無いので、この種のテストは CI でしか検出できない。

## 5.5 `indexCount` が常に 372 だった（3 の中で 2 番目に見つけた）

cull シェーダは draw コマンドを自分で書くが、`indexCount` を**全メッシュレットで 372 固定**にしていた。

```glsl
draws[base + 0u] = 372u;   // ← 実際が 1 quad のメッシュレットでも 62 quad 分描画
```

固定ストライド（372 index / 248 vertex）を予約しているので、末尾は**未使用**。
そこはゼロ埋めなので退化三角形になり、**見た目は何も壊れない**。
つまり「正しく動くが、部分的に埋まった全メッシュレットで無駄なバーテックスシェーディングをする」
という、レビューを素通りする種類のバグ。

quad 数はレコードの 4 語が全部埋まっていて入れ場所が無いので、
**サイドデータ語のオリエンテーション 3 bit の上**に載せた:

```java
(quadCount << 3) | orientation.ordinal()
```

```glsl
uint o     = sideData[index] & 7u;          // バケット
draws[base + 0u] = (sideData[index] >> 3u) * 6u;   // indexCount = quads * 6
```

ゼロ埋めが安全なのはこの `indexCount` が正確だから。逆に言えば
**正確な `indexCount` とゼロ埋めパディングは組で成立している** — 片方だけだと壊れる。

## 5.6 頂点ストリームのアップロード（`SectionUpload`）

`Meshlet` の `writePositions` / `writeAttributes` / `writeLights` / `writeIndices` は
**どれも呼び出し元が無かった**。cull データを接続しても、draw コマンドが指す
ジオメトリが未アップロードのまま。

`gpu/SectionUpload` を追加。6 バッファを**1 回のウォーク**で埋める。

**要点は「後ろに詰めて書く」のではなく「スロットに書いて残りをゼロ埋めする」こと。**

```java
int start = out.position();
writer.write(out);
for (int i = out.position() - start; i < strideBytes; i++) out.put((byte) 0);
```

後ろに詰めると、62 quad 未満の最初のメッシュレット以降**全部がずれる**。
slot `i` は `i * 372` index / `i * 248` vertex から始まる前提なので、
以降の全 draw が**前のメッシュレットの頂点を読む**。壊れたジオメトリが出るだけでクラッシュはしない。

テストは**書き込む前にバッファを `0x5A` で汚してから**書く。
こうしないと「埋めなかったパディング」が「たまたまゼロだった」と区別できない。

## 6. 未解決

- `CullingPipeline` はパイプラインとレイアウトを作るだけで、**ディスパッチもバッファも持たない**。
  `SectionUpload` の出力を実際に `GpuBuffer.mapForWrite()` へ流し、
  カリングをディスパッチする層が未実装。
- `block.vert` / `block.frag` 側の頂点バインディングがこのストライドと一致するかの照合が未実施。
