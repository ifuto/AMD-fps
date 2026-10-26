# 30. 本物の GL ドライバで測る — ドローコールに値段をつける

## なぜ 29 だけでは足りなかったか

`29_cpu_render_benchmark.md` の実測は 1.1〜1.5 倍だった。そしてその数字には**構造的な天井**がある。
実装を足しても上げられない天井で、理由は二つ。

1. **フィルは削れない。** オクルージョンで消したセクションは**元々 1 ピクセルも塗っていない**。
   隠れていたものを消しても塗る量は減らない。ソフトウェアラスタライザでは 1 コアが
   フィルもサブミッションも担うので、CPU 側の節約は全部「減らないフィル」で割られる。
2. **ドローコールの値段がゼロ。** 906 → 123 は 7.4 倍の削減だが、ドライバが無いので
   FPS にほぼ反映されない。

つまり 29 は「どれだけ少なく出したか」は測れるが、「出したものがいくらするか」を測れない。
後者を測るには**ドライバが要る**。

## この環境に何が有るか（推測せず probe した）

`GlProbeTest`（用済みなので削除済み）が CI 上で `ldconfig` と EGL 初期化を試して報告した結果:

| 項目 | 結果 |
| --- | --- |
| `libEGL.so.1` | **無い** → EGL surfaceless は不可 |
| `libOSMesa` | **無い** |
| `libGL.so.1` / `libGLX.so.0` | 有る |
| Mesa | **25.2.8**、gallium DRI ドライバ一式 |
| `xvfb` | 有る（2:21.1.12） |
| `libX11/Xrandr/Xinerama/Xcursor/Xi/Xxf86vm/Xfixes/Xext` | 全部有る |
| コア / RAM / CPU | **4 コア / 16 GB / AMD EPYC 7763** |

⇒ 残る経路は **GLX + 仮想 X サーバ** だけ。ただし **JVM は自分の環境変数を変えられない**ので、
`DISPLAY` を GLFW 初期化前に用意するには**子プロセス**が要る（`VirtualDisplay`）。

実際に取れたコンテキスト:

```
GL 4.5 (Core Profile) Mesa 25.2.8   renderer=llvmpipe (LLVM 20.1.2, 256 bits)
```

**llvmpipe はソフトウェアラスタライザだが、本物の OpenGL 実装**である。状態機械もドローコールも
Mesa を通る。そして**ラスタライズは別の 3 コアで走る** — これが肝心で、CPU とフィルを
別スレッドに分けられる唯一の手段がこれだった（GPU は無い）。

## ベンチの形

`src/test/java/net/amdfaster/bench/`:

- **`GlRaster extends SoftRenderer`** — カメラ・射影・カウンタ・`projectBox` は**継承**。
  再実装すると「セクションが画面のどこに来るか」について二つの意見が生まれ、ずれる。
  上書きするのは `clear` / `drawMeshWith` / `drawFaceMeshes` / `drawScreenQuad` /
  `drawScreenBatch` / `beginTerrain` / `endTerrain` / `endFrame` だけ。
- **アトラス 1 枚** — 25 テクスチャを shelf packer で 1 枚に詰め、UV をアップロード時に
  焼き直す。バインドはフレームで 1 回。Minecraft のブロックアトラスと同じ構造。
- **地形は常駐バッファ 3 本**（opaque / cutout / blend）。**Sodium と同じ multi-draw** で提出。
- **`glFinish()` を毎フレーム** — GL は非同期なので、これをやらないと計測するのは
  「キューがどれだけ速く埋まるか」で「どれだけ速く捌けるか」ではない。
- **reversed-Z**: `glClipControl(LOWER_LEFT, ZERO_TO_ONE)` + `glDepthFunc(GL_GREATER)` +
  clear depth 0。`ViewProjection` の行列をそのまま使える。

## ベースラインの訂正（重要）

**以前のベースラインは 1 セクションにつき 6 ドロー（面方向ごと）を提出していた。Sodium は
そんなことをしない。** Sodium のメッシャはセクション×レイヤーにつき 1 メッシュを作り、
region 単位で `glMultiDrawElementsBaseVertex` に出す（deepwiki / Sodium ソースで確認）。
6 ドローを課す比較は藁人形で、倍率は発明を測ることになる。

なので両パイプラインに同じ multi-draw を与えた:
- ベースライン: `drawFaceMeshes(section.opaque, …)` = 1 セクション 1 レンジ（6 面を連結）。
- AMD-Faster: 面マスクと法線コーンで生き残った**面ごと**のレンジ。

差は「ドローコール数」ではなくなり、**レンジ数と頂点数**になった。

## 正しさのガード（29 より強い）

倍率だけ見ると「描画をサボれば速い」で勝てる。なので:

1. **両パイプラインの最終フレームを `glReadPixels` で読み戻し、ピクセル単位で比較**
   （`diffPercent`）。同じ世界を描いているなら数 % 以内。
2. **GL の被覆ピクセル数をソフトウェアラスタライザの同じシーンと比較**。
   GL は左下原点で上下が反転するので、ピクセルそのものではなく**枚数**で比べる。
3. シーン 3 本すべてに適用。

**このガードが実際に 2 回、本物のバグを捕まえた**（下記）。コストモデルでも、
「描いた枚数」だけを見るガードでも捕まらない種類。

## このバックエンドが見つけたバグ

### 1. レンジを float で数えて vertex として読んでいた（本命）

`buildTerrain` のカーソル `at` は **float 個数** で進む（1 頂点 6 float）。それをそのまま
`glMultiDrawArrays` / `glDrawArrays` の first/count に渡していた。**両者は vertex 数で数える。**

結果、すべてのレンジが**バッファの 6 倍先を指し、6 倍多く読む**。しかも**何も描かないのではなく
別セクションを描く**ので、画面には三角形が出る。被覆は 6〜26 % で、**ゼロではなかった**。
だから「パイプラインが壊れている」ではなく「遅いレンダラ」に見え、調査が 6 周空振りした。

確定させた数値:

| 検査 | 結果 |
| --- | --- |
| opaque レイヤーの頂点数 | **621,636**（= 207,212 三角形 × 3。修正前は 3,729,816 ＝ float 数だった） |
| バグ時のレンジ | `first=2815560, count=12960` → 1 セクションで 4,320 三角形。**16³ セクションの上限 1,536 quad を超える** |
| 修正後のレンジ | `first=28524, count=582`、セクション単位 `count=2160`（720 三角形）＝妥当 |
| 修正後の被覆 | **477,549 / 542,108 / 497,858**（ソフトウェア 552,493 / 610,987 / 582,750 ＝ 86〜89 %） |

**単位を混ぜるとエラーは出ない。** `Mesh.vertexCount()` を使うメッシュ単位の経路だけは正しく、
それが「同じ三角形・同じ行列なのに片方だけ描ける」という不可解な非対称の正体だった。

### 2. model-view 行列に view が二重に入っていた

`SoftRenderer.drawMeshWith` が受け取るのは **model-VIEW 行列**（呼び出し側がカメラを畳み込み済み）。
これを view-projection と掛けると **view が二重適用**になる。しかも**派手に壊れない** —
エンティティ・パーティクル・雨粒が別の場所に移動するだけで、大抵は画面内に残るので
フレームは賑やかで数字ももっともらしい。

地形は model を渡さない（頂点が世界座標）ので正しく、**だから絵の半分だけが合っていた**。
修正: `GlRaster.setProjection(near, nearHalfWidth, nearHalfHeight, far)` で射影単体を別に作り、
`compose()` は model が null のとき view-projection、model があるとき射影単体を使う。

### 3. 表面の向き（`glFrontFace`）

`ViewProjection` は reversed-Z のために第 3 行に **負の係数** `a = −near/(far−near)` を持つ。
画面 x は `right·p`、画面 y は `up·p` で増加し、カメラは `forward` を見る。つまり絵を描く座標系は
**(right, up, −forward) ＝左手系**（(right, up, forward) が右手系なので）。
だから**外から見て反時計回りに巻いた三角形は、フレームバッファには時計回りで届く**はず —
というのが机上の推导で、**2 回とも外した**。1 回目と 2 回目は上記のレンジのバグが
答えを隠していたので、どちらが正しかったのかも分からない。

なので**推測をやめて測る**: 同一セクションを `GL_CCW` と `GL_CW` の両方で 1 回ずつ描き、
被覆で決める（`debugProbeOpaque`）。**カリングを無効化すると 477,549 px 描ける**ことは
確定しているので、どちらかがそれに一致し、もう片方は 0 になる。

### 切り分けに使った検査（すべて実測、推測は 1 つもない）

| # | 疑い | 検査 | 結果 |
| --- | --- | --- | --- |
| 1 | ベイクが空 | `terrainVertices` | 621,636 頂点。空でない |
| 2 | 行列 | `GLBENCHMATRIX` 行 | `m0=0.803 m5=1.428 m12=−75.5`。正しい |
| 3 | 起動時に作った VAO | 描画時に属性を再宣言 | 変化なし。無実 |
| 4 | 常駐バッファ | 描画直前に新 VAO を作って同一レンジを再描画 | 完全一致。無実 |
| 5 | multi-draw | `glDrawArrays` をレンジ毎に発行 | **完全一致（27353 = 27353）。無実** |
| 6 | 表面の向き | 同一セクションを `GL_CCW`/`GL_CW` で 1 回ずつ | **ccw=0 / cw=8882。CW が正しく、既に CW** |
| 7 | **深度テスト** | 無効化 | **474,401 に回復。犯人** |
| 8 | 深度の設定値 | `glGetFloat`/`glGetInteger` | `clearDepth=1.00 depthFunc=0x201(GL_LESS)`。**渡した通り** |
| 9 | 深度バッファの有無 | FBO のアタッチメント照会 | `status=0x8cd5 err=0x0 depthType=0x8d41 name=1`。**存在する** |
| 10 | Z 規約 | reversed-Z ↔ standard Z を切替 | **`d(cull,depth,multi)` が 27353 のまま。無関係** |

**ここまでの教訓**: 深度テストが全フラグメントを落とすと、GUI（深度テストなし）だけが残る。
それは「地形が描けていない」という症状と区別がつかず、しかも被覆が 6〜26 % と
**ゼロでないので「遅いだけ」に見える**。1 項目ずつ潰す以外に方法はなかった。

## ここまでの実測

CI run 39915192294 / commit `220eb3a`（レンジ修正済み、表面の向きはまだ未確定）:

| シナリオ | ドロー（全Mod入り → AMD-Faster） | レンジ |
| --- | --- | --- |
| A 平地 | 15,567 → 7,796 | 17,266 → 18,634 |
| B 村・洞窟 | 37,000 → 20,347 | 12,385 → 17,756 |
| C TNT×100 | 83,871 → 48,127 | 15,424 → 21,524 |

倍率は 1.64〜1.85 倍。**ただし地形がまだ描けていない状態の数字なので報告しない。**
向きが確定して被覆チェックを通ってからが本測定。

なお **レンジ数は AMD-Faster の方が多い**（面ごとに分けるので 1 セクションあたり約 3 レンジ、
ベースラインは 1 レンジ）。面方向メッシュ分割はカリングの手段であってバッチングの手段ではなく、
これをドロー数で取り返すのは MDI / GPU 駆動カリングの仕事である。

## 未解決: 深度バッファがクリアされない

**深度テストの比較自体は正常に動いている。** `GL_ALWAYS` にすると 474,401 px（深度オフと完全一致）、
`GL_NEVER` にすると 8,254 px（GUI だけ）。だからテストは効いていて、**比較対象のバッファが wrong**。

決め手は次: **`GL_LESS`（クリア値 1.0）でも、`GL_GREATER`（クリア値 0.0）でも、全フラグメントが落ちる。**
これを同時に満たすバッファ内容は 1 つしかない —— 中间の値。そして深度の読み戻しは
**0.6164 / 0.9964** を返していた。**この値を 2 回「未初期化メモリだ」と書き捨てたが、それが答えだった。**
0.25 でクリアしても読み値が変わらないのを「読みが壊れている」と解釈したが、実際は
**クリアが効いていない**ので読み値が変わらなかった。

診断フレームは毎回フレーム 0、つまり同じカメラなので、**毎フレームが前フレームの深度と比べられ、
ほぼ全部が等値で落ちる**。27,353 px だけ通るのは、2 つのパイプラインで僅かに手前に来る部分。

潰したもの（すべて実測）:

| 疑い | 検査 | 結果 |
| --- | --- | --- |
| 表面の向き | 同一セクションを CCW/CW で 1 回ずつ | `ccw=0 cw=8882` → **CW が正しく、既に CW** |
| 深度テストの有無 | 無効化 | 474,401 に回復 → **犯人はここ** |
| 比較関数 | `GL_ALWAYS` / `GL_NEVER` | 474,401 / 8,254 → **比較は正常** |
| Z 規約 | reversed ↔ standard | `d(all)` が 27,353 のまま → 無関係 |
| `glClipControl` | 呼ばない | 変化なし → 無関係 |
| アタッチメント形式 | レンダーバッファ / テクスチャ | `0x8d41` でも `0x1702` でも同じ |
| クリア方法 | `glClear` / `glClearBufferfv` | どちらも値が入らない |
| FBO の状態 | 照会 | `status=0x8cd5 err=0x0 depthType=0x8d41 name=1` |

**このため GL 側の 1.78〜2.04 倍は報告しない。** 隠面消去の無い状態で出た数字で、しかも
llvmpipe の 1 ドローコストは AMD の OpenGL ドライバよりずっと安い。**上にも下にも使えない。**

### ここで得た方法上の教訓

1. **被覆チェックは倍率チェックより強い。** 世界の全面が消えていても FPS は 2.65〜3.24 倍と
   「良さそう」に出た。描画をサボれば速くなるのだから当然で、倍率だけ見る検査はこれを通す。
2. **単位を混ぜるとエラーが出ない。** float と vertex の取り違えは例外も GL エラーも出さず、
   **別のセクションを描く**。被覆 6〜26 % は「ゼロ」より遥かに見つけにくい。
3. **推測を 2 回外したら測る。** 表面の向きは机上で 2 回導出して 2 回外した。1 回は別のバグが
   答えを隠していた。1 フレームを 2 通りの設定で描いて被覆で決める方が速かった。
4. **「おかしい値」を未初期化メモリと決めつけない。** 0.6164 は 2 回捨てて、2 回とも正解だった。

## 環境メモ

- 子 JVM の起動: `java -Xmx3G -cp $java.class.path net.amdfaster.bench.GlBenchmarkMain`、
  環境変数 `DISPLAY=:99` `LIBGL_ALWAYS_SOFTWARE=1` `GALLIUM_DRIVER=llvmpipe`。
- Xvfb は `/tmp/.X11-unix/X99` のソケットを待ってから返す。
- LWJGL の罠 3 つ（すべて CI に捕まった）:
  - `GL_DEPTH_COMPONENT24` は **GL 1.4** の定数で `GL11` に無い。
  - `GL_CLAMP_TO_EDGE` も **GL 1.2** の定数。
  - `glBufferData(target, (FloatBuffer) null, usage)` は **null を参照して NPE**。サイズを渡す。
- ワークフローは build.log を grep して**先頭 15 行**しか annotation 化しないので、
  報告は**必ず 1 行**にまとめる。複数行の失敗メッセージは 2 行目以降が消える。

## 2026-10-24 — the depth buffer, the fill, and a baseline that was not Sodium

**The depth buffer is solved.** It was never a clear that failed to land: `glClear(GL_DEPTH_BUFFER_BIT)`
genuinely does not write this driver's depth buffer (`readAt0.25=1.0000` after a clear to 0.25,
`readErr=0x0`), and neither does `glClearBufferfv`. It is now written by drawing the far plane over it
with the comparison forced to pass and colour writes masked off. `d(all)` went from 27,379 to 474,425,
which equals `d(noDepth)` and `d(ALWAYS)`, and coverage against the software rasteriser is 85–88 %.

Two other things were wrong underneath it. The depth row flip was `z' = w - z`, which lands the world in
the top half of a 24 bit buffer; GL's window transform wants `z' = w - 2z`. And the probed vertex was the
first one *baked*, which belongs to whichever section was baked first — it was behind the camera, its
clip w was -87.0, and the perspective divide mirrored it to an ndc that looked perfectly reasonable. Four
runs were spent checking a matrix that was never wrong.

**The fill was measured, not argued.** The same pipelines run again with rasterisation clipped to one
pixel: every draw, vertex and state change still happens. Fill is **81 % of the baseline frame** on this
driver. Two pipelines that paint the same pixels cannot differ by 3× while that is true.

**Greedy meshing was missing, and adding it made every number worse.** The mesher emitted one quad per
exposed face, so a flat plain cost 512 triangles per section top. Sodium merges coplanar runs. Adding it
cut base triangles 37,494 → 16,203 and fast 15,202 → 7,851, held pixel retention at 96.1 %, and dropped
the fill-bound ratio 1.43× → 1.38× and the GL driver+cpu ratio 1.96× → 1.11×. **The earlier numbers were
inflated by a baseline that was not the baseline it claimed to be.**

**Where AMD-Faster's frame actually goes**, measured per stage in microseconds on scene A:
`terrain 793, occlusion 72, entities 38, particles 41, clouds 20, gui 49`. The baseline's *entire* frame
is 815 µs. So the terrain stage alone costs more than the whole baseline, and it is not the Hi-Z
completion (72 µs) — it is the occluder rasterisation, which happens during submission and runs at
framebuffer resolution. A hierarchical Z does not need that; 1/8 per axis is what the mips are for.

Also fixed: the report read `renderer.glDraws` at the bottom of `run()`, after the clipped runs and the
seven diagnostic frames had all added to the same counters. It reported nine passes as one pipeline's
frame, which is what made AMD-Faster look like it issued three times the baseline's draws. Corrected, it
issues 2.8–3.3× fewer.

## 2026-10-26: GL バックエンドと software バックエンドが一致していない（未解決・重要）

インデックス付きジオメトリを実装した際に発覚。**GL の比率をそのまま信じてはいけない。**

`GLBENCHPAINT`（同一シーン・同一カメラで1フレーム描いた塗像素数）:

| | glBase | softBase | 一致 | glFast | softFast | 食い違い |
|---|---|---|---|---|---|---|
| scene 0 | 559,784 | 552,428 | **1.3 %** | **330,425** | 541,683 | **1.64×** |
| scene 1 | 575,443 | 610,944 | 6 % | 203,994 | 601,862 | **2.95×** |
| scene 2 | 563,383 | 582,711 | 3 % | 284,605 | 574,902 | **2.02×** |

**baseline は GL と software がほぼ一致するのに、AMD-Faster 側だけが GL で大きく少ない。**
つまり GL バックエンドは AMD-Faster の経路で何かを描き落としている。`diffPercent` も
75 % 前後で、これは最初の記録から一貫してそう。

候補（未検証）:
- **CUTOUT / BLEND レイヤの winding 未補正。** `buildTerrain` は layer 0 にだけ
  `FACE_NORMALS[face]` を渡して巻き順を補正し、layer 1 と 2 は `append(data, at, mesh, null)`
  で補正なし。`GL_CULL_FACE`/`GL_BACK`/`GL_CW` が有効なので、裏向きと判定された分が捨てられる。
  software 側は screen-space の `area > 0` で個別に判定するので影響が違う。
- CUTOUT の `uniformCut = 0.5` による discard とアトラス UV の相性。

**これが直るまで、`GL driver+cpu` と `720p full` の比率は上振れしている。**
software 側（`CpuRenderBenchmark` の fill-bound / cpu-bound）は両パスが同じ
`SoftRenderer` を通るので、この問題の影響を受けない。**信頼できるのはそちら。**

### インデックス付きジオメトリ（実装したが revert した: `fa20cb5`）
- **dedup 自体は成功:** `quadVerts=106,368 → indexedVerts=70,904` = **1.50×**、
  予測（2三角形の quad で 6 スロット → 4 一意頂点）と完全一致。
- **しかし塗像素数が 330,425 → 142,346 に低下**し、レイヤ別計測で
  `idxTris = 2,624,360 / 3,919,238 / 0`。**CUTOUT レイヤが OPAQUE の 1.5 倍の三角形を
  送出していた。** 上記の winding 問題と無関係ではないはず。
- 上記のカバレッジ不一致を先に直してから再挑戦すること。dedup のコード自体は正しい。

### 訂正: 裏面除去という説明は誤りだった（`a60a09a` → `ea63cb2` で revert）

上の候補のうち「CUTOUT / BLEND の winding 未補正」を**確定と書いたが、それは間違い**。

`glFastNoCull`（裏面除去を切って1フレーム描いた塗像素数）を測って
scene 0 で 330,118 → 522,561 と跳ねたので確定とした。しかし実際に winding 補正を
入れて測り直すと:

| scene | glFast | glFastNoCull | softFast |
|---|---|---|---|
| 0 | 330,023 | **335,283** | 541,921 |
| 1 | 364,158 | 541,792 | 602,339 |
| 2 | 570,474 | 649,597 | 577,622 |

- 補正を入れても `glFast` は **330,118 → 330,023 でほぼ不変**。効いていない。
- 裏面除去を切っても 335,283 までしか増えない。**除去を切れば巻き順は可視性に無関係**
  なのに、同じ条件が 522,561 と 335,283 の両方を出した。
- **つまり `glFastNoCull` は決定的な測定ではなかった。** `applyState` は
  `currentState` をキャッシュしていて状態が変わらない限り再適用しないため、
  `debugNoCull` を実行途中で切り替えても反映されるとは限らない。
  522,561 という数字は直前の実行が残した状態の産物。

**教訓: デバッグトグルを途中で切り替える測定は、そのトグルが実際に効いたことを
 separately 検証しない限り証拠にならない。**

`triFace` を三角形ごとに持たせる実装自体は正しく動いたが、software 側で
**cpu-bound 1.63 → 1.46** と約 10 % 悪化した（毎フレーム作る particle / rain メッシュで
配列書き込みと `build()` のコピーが増える）。GL のカバレッジを直さないので revert した。

### 未解決のまま残っている事実（これが本当の問題）
`GLBENCHPAINT`（同一シーン・同一カメラ・1フレーム）:

| scene | glBase | softBase | 一致 | glFast | softFast | 食い違い |
|---|---|---|---|---|---|---|
| 0 | 538,720 | 552,428 | 2.5 % | **330,023** | 541,921 | **1.64×** |
| 1 | 662,724 | 610,944 | 8 % | 364,158 | 602,339 | 1.65× |
| 2 | 554,195 | 582,711 | 5 % | 570,474 | 577,622 | 1.01× |

**baseline は両バックエンドで一致するのに AMD-Faster だけ GL で大きく少ない。**
裏面除去ではない。次に見るべきは `applyState` の状態遷移そのものと、
AMD-Faster だけが通る経路（`drawDynamicMesh` の particle、`drawScreenBatch` の GUI、
occlusion culling 後の cutout キュー）。

**これが直るまで GL 系の比率（driver+cpu / 720p full）は上振れしている。
software 系（fill-bound / cpu-bound）は両パイプラインが同じ `SoftRenderer` を通るので影響なし。**

### 本当の原因: 別々のフレームを比較していた（`GLBENCHPAINT` のバグ）

上に「未解決の問題」として残した GL と software の食い違いは、**描画のバグではなかった。
比較のバグだった。**

`softwareCoverage` は明示的に **frame 0** を描く:

    scene.view(0, aspect, view);
    pipeline.renderFrame(scene, assets, renderer, view, 0);

一方 `readBack(fastImage)` は `measureBest(...)` の**直後**に呼ばれていて、そのループは
`REPEATS × (WARMUP_FRAMES + 最大 MAX_FRAMES)` ≒ **800 フレーム**を回している。
カメラは 1 フレーム 1.5° 回るので、**GL 側は約 1,200° 回転した後のフレーム**だった。

**つまり frame 0 の software 画像と、約 800 フレーム目の GL 画像を並べて
「一致しない」と言っていた。** これで以下が全部説明できる:

- `diffPercent` が最初から一貫して 75 % 前後だった（別アングルなので当然）
- `glFast` が実行ごとに 577,026 / 570,474 / 440,983 と揺れた（ループが止まる帧が違う）
- baseline は「一致して見えた」（たまたま似通ったアングルだった）
- 裏面除去を切った値が 522,561 / 335,283 / 620,764 と全く再現しなかった

**修正:** `glCoverage(...)` を追加し、GL 側も frame 0 を描いてから読み出すようにした。
`basePainted` / `fastPainted` / `diffPercent` はこれで同一フレーム同士の比較になる。

**撤回:** 「GL 系の比率は上振れしている」という記述は**根拠がなかった**。
壊れた比較から推論したもの。GL の比率を疑う理由は今のところ無い。

`glFastNoCull` の計測は削除した。`applyState` が `currentState` をキャッシュするため
トグルを途中で切り替えても反映される保証がなく、そもそも調べたかった仮説が誤りだった。

### インデックス付きジオメトリは本物に壊れている（`3d3e75f` → revert 済み）

比較を同一フレームに直した上で測り直したので、今度は数字が信用できる:

| scene | インデックス無 glFast | インデックス有 glFast | softFast | diffPercent |
|---|---|---|---|---|
| 0 | 515,058 | **88,941** | 541,828 | 1.8 % → **46.5 %** |
| 1 | 574,869 | 263,897 | 602,495 | 9.5 % → 40.4 % |
| 2 | 551,643 | 251,024 | 571,671 | 7.0 % → 38.5 % |

**scene 0 で塗像素数の 83 % が消える。** dedup の算術は正しい
（`quadVerts=106,368 → indexedVerts=70,904`、2三角形 quad の期待値 1.5 と一致）ので、
**故障はベイクではなく描画側**（`glMultiDrawElements` のオフセット／VAO と EBO の状態の
どちらか）。レイヤ別計測では CUTOUT が OPAQUE の 1.5 倍の三角形を送出していた。

**次の着手時にやること（推測でなく検証で）:**
1. 単一レイヤ・単一レンジだけを描く最小ケースを CI で1フレーム回し、
   `glFast` が software と一致するかを見る。
2. 一致するなら range の刻み（`first` の単位）を、しないなら EBO のバインドを疑う。
3. **dedup のコードは正しいので捨てないこと。** 1.5× の頂点削減は実在の利得。

### この回の教訓
`GLBENCHPAINT` が別フレームを比較していたせいで、**正しい実装を壊れていると誤判定して
revert し、直した後にもう一度入れて再び壊れた**。計測が壊れている間は、
その計測を根拠にした判断（revert を含む）が全部あてにならない。
**まず計測を直し、その後に実装を評価する。**
