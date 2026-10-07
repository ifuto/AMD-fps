# 10 — 一次ソースの再検証（`fetched_content/` に落ちた原文に対する照合）

`.github/workflows/fetch.yml` が `fetch-web.md` の URL を全部落としてくれたので、
**これまで到達できなかった一次ソースをローカルで直接読めるようになった**。
`docs/notes/01`〜`09` は web 経由の要約から書いたものなので、ここで原文と突き合わせる。

対象: `fetched_content/gpuopen.com_learn_rdna-performance-guide_`（RDNA Performance Guide 全文, 39,468 字）

---

## 1. 訂正（自分のメモ／コードが間違っていた箇所）

### 1.1 workgroup size は「128（RDNA）/256（GCN）」ではない → **64 の倍数**

原文（"Compute shaders" 節）:

> GCN runs shader threads in groups of 64 known as wave64. RDNA runs shader threads in groups of
> 32 known as wave32. Unused threads in a wave get masked out when running the shader.
> **Make the workgroup size a multiple of 64 to obtain best performance across all GPU
> generations.**

- 誤っていたもの: `docs/notes/02` §13-1、`docs/notes/09` §7、そして **`AmdArchitecture` の
  `recommendedWorkgroupSize()`（RDNA=128 / GCN=256 として実装していた）**。
- 原因: `docs/notes/02` §6 の **occupancy 議論**（RDNA の WGP は 4×32=128 スレッドで ALU が飽和しうる）を
  「設計指針」と取り違えた。occupancy の話と workgroup size の推奨値は別の話。
- 修正: `AmdArchitecture.WORKGROUP_SIZE = 64` に統一。テスト
  `GpuIdentityTest#workGroupSizesFollowTheRdnaPerformanceGuide` で
  「64 の倍数」「nativeWaveSize の倍数（部分 wavefront を作らない）」を固定した。
- 補強材料（同ガイド "Async compute" 節）:
  > **Smaller workgroups (64 threads) usually perform better than larger workgroups when run
  > async.** This decreases resource requirements and increase opportunities for launching the shader.

  → **async compute に載せるカルは 64 スレッド workgroup で正しい**。

## 2. 一次ソースで確定した追加事実（実装に直接効くもの）

### 2.1 HOST_VISIBLE メモリの扱い（★ persistent mapped buffer 設計の制約）

原文（"Copying" 節）:

> Memory in UPLOAD and GPU_UPLOAD heap (DX12) / **HOST_VISIBLE and non-HOST_CACHED type (Vulkan)
> is uncached and write-combined.** Be careful when accessing it through a mapped pointer:
> - **Only write to it using memcpy or sequentially number-by-number. Avoid random accesses.**
> - **Never read from it.** Watch out for accidental reads, e.g. `pMappedPtr[i] += v`.

→ **AMD-Faster の「チャンク形状を VRAM に直接書く」設計は、必ず
`memcpy` ないし逐次的な書き込みでなければならない。読み戻しは禁止。**
read-modify-write（`p[i] += v`）を 1 回でもやると write-combining が崩れる。
→ メッシュビルダの出力は **append-only のシーケンシャル書き込み**に限定する。

### 2.2 GPU 内コピーには transfer queue を使わない

原文:

> **Use the copy queue to move memory over PCIe.** This is marginally faster than the other queues.
> It allows independent graphics and compute work to run without waiting.
> **Use compute or graphics queues to copy when both: the destination and source are on the GPU,
> and the result is needed immediately or the queue is otherwise idle.**

→ **チャンクメッシュのアップロード（GPU 内、直後に使う）は transfer queue に投げない。**
graphics か compute queue でやる。transfer queue は PCIe 越し（APU では事実上使わない）。
`docs/notes/07` の「単一 graphics + 単一 compute」構成と整合。

### 2.3 深度フォーマットは 32bit、reversed-Z

原文（"Resources" 節）:

> **24-bit depth formats have the same cost as 32-bit formats on GCN and RDNA.**
> **Use a 32-bit format on AMD to get higher precision for the same memory cost as 24-bit.**
> 16-bit depth does not necessarily compress better than 32-bit depth.
> **Use reversed depth (near plane at 1.0)** to improve the distribution of floating-point values.
> Try to use the full range of the depth buffer. Having all the values clumped up in a small part
> of the range can reduce depth test performance.

→ **`D32_SFLOAT` + reversed-Z**。`X8_D24` は使わない。Hi-Z を自前で構築する設計なので
精度分布は直接カリング精度に効く。

### 2.4 頂点ストリームは分割する

原文:

> **Split your vertex data into multiple streams.**
> **Allocating position data in its own stream can improve depth only passes.**
> If there is an attribute that is not being used in all passes, consider moving it to a new stream.
> **Avoid setting vertex streams per draw call.** Use vertex and instance draw offsets.
> **Vertex data can also be stored in SBOs/Structured buffers and fetched in a vertex shader
> instead of using vertex streams.**

→ **Z pre-pass を入れるなら position だけの別ストリーム。**
そして「vertex pull（SBO から VS で読む）」は AMD が明示的に認めている手法。
**meshlet デコードを VS でやる**設計（`09` §4.3 の compressed meshlet）と整合する。

### 2.5 fast clear の条件（追記）

原文:

> Fast clears are designed to be ~100x faster than normal clears.
> **Fast clears require full image clears.**
> Render target fast clears need one of the following colors: RGBA(0,0,0,0) / RGBA(0,0,0,1) /
> RGBA(1,1,1,0) / RGBA(1,1,1,1).
> **Depth target fast clears need 1.f or 0.f depth values (with stencil set to 0).**
> **Depth target arrays must have all slices cleared at once** to do a fast clear.
> Use Discard / LOAD_OP_DONT_CARE to break dependencies when skipping a clear.
> Vulkan: Prefer using `LOAD_OP_CLEAR` and `vkCmdClearAttachments` over `vkCmdClearColorImage`
> and `vkCmdClearDepthStencilImage`.

→ **reversed-Z（near=1.0）なら depth clear は 1.0 で fast clear できる。**
**ステンシルを使うなら 0 でクリアすること**。シャドウマップのアトラス（array）は
**全スライスを一度にクリアしないと fast clear にならない** ← Minecraft のシャドウマップで踏む。

### 2.6 primitive restart index は避ける

> **Avoid using primitive restart index when possible.** Restart index can reduce the primitive
> rate on older generations.

→ meshlet のトライアングルストリップ化で restart index を使わない。

### 2.7 VS でのカリング

> Culling primitives using a geometry shader or cull distances is expensive.
> **Cull primitives from the vertex shader by setting any vertex position to NaN.**

→ **mesh shader を持たない世代（RDNA1/2/3, Vega）での per-primitive カルのフォールバック**。
AMD-Faster は基本 compute でカルするが、頂点単位の最終フォールバックとして NaN 化を使う。

### 2.8 Z pre-pass と discard

> Avoid discard in long running shaders when there are other paths.
> **Use depth test equal to skip pixels that were discarded in a Z pre-pass, instead of calling
> `discard`.**

→ **Minecraft の cutout（葉・ガラス枠）は `discard` ではなく Z pre-pass + EQUAL** にする。
`docs/notes/07` の「RDNA4 で early-Z が弱まるので Z pre-pass を入れる」という判断と合致し、
さらにその pre-pass を**本パス側でも活かせる**。

### 2.9 その他の数値

| 項目 | 原文の数値 |
| --- | --- |
| root signature / push constant | **13 DWORD 未満**。descriptor set = 1 DWORD、dynamic buffer = 2（robust buffer access ON なら 4）、push constant = 4バイトごとに 1 DWORD |
| command buffer | **最低 10 draw/dispatch**。bundle/secondary は避け、使うなら 10 draw 以上 |
| LDS | **32 bank × 32 bit**。float4 配列を X 方向に読むと **8 bank conflict**、float の配列なら **2**。→ **SoA** |
| compute の image 書き込み | **wave あたり 256 バイトの coalesced ブロック**。**8×8 thread group が 8×8 ピクセルブロック**を書く。swizzle に `ARmpRed8x8()` |
| ray tracing の WGP | **8×4 threadgroup size** が RDNA WGP に最適（LDS を多用するため） |
| transcendentals | **quarter rate**。RDNA は co-execute できる。`tan` は sin/cos に展開されて 3 個 |
| arc 関数 | `atan`/`acos` 等は **100 サイクル以上**のコードになりうる |
| compute vs pixel | 発散の多い処理は **RDNA 3 以前では compute の方が速い**（PS は他の wave に export を阻まれる） |
| fullscreen | **fullscreen quad より compute pass が最大 2% 速い**（helper lane が不要、cache hit 率も上がる） |
| Gather4 | **8 VGPR → 2 VGPR**。帯域は減らないがテクスチャユニットへの traffic と cache miss は減る |
| `readFirstLane` | VGPR を scalar 化して VGPR 圧を下げる |
| ExecuteIndirect | **root signature を変えないのが最速**。**RDNA 以降は ExecuteIndirect 内の root signature 変更に最適化パスが追加された**（pre-RDNA は slow path）。vertex buffer 更新は vertex pull で代替。**カルしたら argument buffer を compact し count buffer を渡す** |
| sparse resource | 「過度に sparse なリソースの読み出しは cache 効率を悪化させる」 |
| present | **AMD では compute queue からも present できる**。async compute と重ねるなら present は別 queue から |
| async compute の苦手 | **export bound なシェーダと並走させると性能が出ない** |
| pipelined vs async | **小さい dispatch は async より pipelined compute の方が良い**。大きな dispatch の後の大きな draw も pipelined が効く |

## 3. この節で確定した実装ルール（`07` への追記分）

1. **workgroup = 64**（全 AMD 世代共通。`AmdArchitecture.WORKGROUP_SIZE`）。
2. **HOST_VISIBLE への書き込みは memcpy/逐次限定、読み戻し禁止**。メッシュビルダは append-only。
3. **GPU 内コピーは transfer queue を使わない**。
4. **`D32_SFLOAT` + reversed-Z**。ステンシルを使うなら 0 クリア。シャドウアトラスは全スライス一括クリア。
5. **position だけの頂点ストリームを別に持つ**（Z pre-pass 用）。
6. **cutout は `discard` ではなく Z pre-pass + `DEPTH_TEST_EQUAL`**。
7. **primitive restart index を使わない**。
8. **LDS は SoA**。float4 の X 読みは 8 bank conflict。
9. **カルした indirect draw は必ず compact + count buffer**。
10. **小さいカル dispatch は graphics queue に pipelined で載せる**（async にしない）。
