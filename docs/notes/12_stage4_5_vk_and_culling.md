# 12. Stage 4–5：Vulkan バックエンドと GPU カリング

Stage 3 までの記録は `11`。ここは Vulkan の初期化と、GPU 駆動カリングの CPU 側・GPU 側。

## 1. 判断と API 呼び出しを分離した理由

Vulkan のコードで **間違えると高価なのは「判断」の方**で、API 呼び出しではない。
どのアダプタを使うか、どのメモリタイプにバインドするか、root signature が予算に収まるか —
これらは GPU がないと検証できないように見えるが、**GPU 型を一切使わなければ普通に単体テストできる**。

`net.amdfaster.vk` はそうなっている:

| クラス | 判断 | LWJGL 型 |
| --- | --- | --- |
| `MemoryTypeSelector` | バッファをどのヒープに置くか | なし |
| `QueueSelector` | どのキューファミリが何をするか | なし |
| `DeviceScorer` | どのアダプタを選ぶか | なし |
| `RootSignatureBudget` | draw の引数バインドが予算内か | なし |
| `FrameRing` | 次の per-frame 割り当てがどこか | なし |
| `VkContext` | 判断しない。呼ぶだけ | 全部 |

おかげで **4 種の実在トポロジ**をテストで固定できた:
Resizable BAR なしのディスクリート / BAR ありのディスクリート / APU / 旧来の 256 MiB BAR ウィンドウ。

## 2. テストが直した 2 件（両方とも実装のバグ）

期待値を先に手で書いたとき、実装の方が 2 か所間違っていた。

**(a) `GPU_ONLY` が「host-visible でない」ことを優先していた。**
Resizable BAR のカードでは **VRAM 全体が host-visible** なので、この優先は
24 GiB の可視ヒープを飛ばして **256 MiB の不可視ヒープにメッシュを全部押し込む**。
不可視ヒープは本来 transient attachment 用の場所だ。優先を外して
「最大の DEVICE_LOCAL ヒープ」に変えた。

**(b) `hasUnifiedHostDeviceMemory` が総メモリと比べていた。**
総メモリにはシステム RAM が含まれるので、**Resizable BAR のカードで false を返す**。
「最大の DEVICE_LOCAL ヒープ」と比べる方式に変えた（可視 VRAM が VRAM の半分を超えるか）。
`select(CPU_AND_GPU)` も同じ閾値を使い、両者が食い違わないようにした。

256 MiB の旧式 BAR ウィンドウは「存在するが unified とは呼ばない」が正解:
1 フレームのワーキングセットが入らないので、取ると予測可能なコピーを
予測不能なコピーに替えることになる。

## 3. LWJGL の呼び出し形で 3 回 CI に落ちた

ローカルに JDK もネットワークもないので **CI が唯一のコンパイラ**。3 件全部、
「その API がどこにあるか」の思い違いだった。

| 誤り | 正 |
| --- | --- |
| `org.lwjgl.glfw.GLFW.glfwCreateWindowSurface` | `org.lwjgl.glfw.GLFWVulkan` |
| `… , PointerBuffer pSurface)` | **`LongBuffer pSurface`** |
| `glfwCreateWindowSurface(instance.address(), …)` | **`VkInstance` のまま**。`LongBuffer` の誤りを直そうとして一緒に壊した |
| `VkPhysicalDeviceFeatures.shaderInt8()` | 1.2 feature なので `VkPhysicalDeviceVulkan12Features` 側 |

**教訓: 推測で 2 か所を同時に直さない。** 3 回目は、`PointerBuffer` の誤りを直すついでに
「きっと生の long ハンドルだろう」と第 1 引数まで変えて、正しかった方を壊した。

そして権威はここ: **LWJGL の生成ソースは GitHub にコミットされている**。
`modules/lwjgl/<module>/src/generated/java/org/lwjgl/<module>/`。
`HelloVulkan.java` のサンプルより、生成された実物のシグネチャを読むべきだった。
実際の宣言:
```java
public static int glfwCreateWindowSurface(VkInstance instance, long window,
        @Nullable VkAllocationCallbacks allocator, LongBuffer surface)
```
`new VkDevice(handle, physicalDevice, ci)`、`new VkQueue(handle, device)`、
`VkQueueFamilyProperties.malloc(count, stack)` も `HelloVulkan.java` で確認済み。

## 4. root signature 予算が設計を変えた

カリングシェーダを書き始めたとき、view-projection 行列を push constant に入れようとした。
**64 バイト = 16 DWORD、予算は 13。** 自分で作った `RootSignatureBudget` に違反していた。

行列は UBO に移し、push constant は dispatch ごとの 8 バイト（base index と flags）だけにした。
root signature は **2 + 1 = 3 DWORD**。`CullShaderTest` がこれを GLSL から解析して
アサートするので、元に戻せない。

## 5. カリングは 2 パス

| パス | 入力 | 出力 |
| --- | --- | --- |
| frustum | 全 meshlet | `atomicAdd` でスロットを取り、draw command を詰めて書く |
| occlusion | **詰まった配列** | 前フレームの Hi-Z と比べて instanceCount を 0 にする |

occlusion パスが **元の meshlet リストではなく詰まった配列を歩く**のが要点。
draw command の `firstIndex` から自分の meshlet index を復元してバウンドを引く。
元リストを歩くと、既に消えたものを再テストする。

frustum パスは **深度ピラミッドを読まない**。読むと前フレームの深度を
今フレームのジオメトリに当てることになり、それがまさに 2 パス方式が避けるアーティファクト。
`CullShaderTest` がその不在をアサートしている。

Hi-Z のレベルは「ボックスを覆える最も粗いレベル」。1 fetch で済む。
細かいレベルでサンプルすると、ピラミッドが既に縮約した情報を gather することになり
遅いだけでなく正しくもない。

**reversed-Z なので比較が逆向き**: meshlet の *最も近い* 深度が
ピラミッドの *最大値* よりまだ遠いなら遮蔽されている。

## 6. シェーダと Java の整合をテストにした

バインディング番号がシェーダとパイプラインレイアウトでズレても **コンパイルは通る**。
描画されないか、別のバッファを読むか、しかも特定のドライバでだけ起きる。

`CullShaderTest` は GLSL を解析して `CullBindings` と突き合わせる:
バインディング番号、workgroup サイズ、push constant の幅、そして
**宣言されたメンバから計算した std140 オフセット**。
uniform block のメンバがパディング境界を跨ぐと黙ってゴミを読むので、ここが一番効く。

途中、テスト側で「コメント内の `;`」に分割を壊された。
GLSL のコメントは `;` を含みうるので、**分割する前にコメントを剥がす**必要がある。

## 7. 確定した数値

- workgroup **64**（RDNA では wave32 × 2、GCN では wave64 × 1、どちらでもマスクされない）
- meshlet レコード **16 バイト**（packed bounds + section origin 3 ワード）
- draw command **20 バイト**（`VkDrawIndexedIndirectCommand` 5 ワード）
- push constant **8 バイト** → root signature **3 DWORD**（予算 13）
- frame UBO **192 バイト**: viewProj @0、frustumPlanes[6] @64、cameraOrigin @160、
  meshletCount @176、hizWidth @180、hizHeight @184
- meshlet 1 個 = 最大 62 quad = **372 index / 248 vertex**

## 8. 次にやること

1. **swapchain とフレームループ**（`VkContext` の上に載せる）
2. **アップロード経路**（staging ring → device-local、`FrameRing` を使う）
3. **Minecraft アダプタ**（`ChunkSection` → `VoxelView`、隣接チャンク縁込み）
4. **shaderc での SPIR-V コンパイル**（今は GLSL をそのまま資源として同梱しているだけ。
   CI でコンパイル検証できるようになれば、シェーダの構文エラーもここで捕まる）
