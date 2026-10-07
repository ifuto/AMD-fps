# 08 — JVM / LWJGL / Fabric 側（「意外とここがボトルネック」）

## 1. Java ランタイムの版本（★FFM が使えるかの結論）

- **Minecraft 1.20.5〜1.21.11 は Java 21 必須**、**26.1 以降は Java 25 必須**
  （Mojang の `version_manifest_v2.json` と PaperMC の要件表が一致、という二次情報を確認）。
- **FFM（Foreign Function & Memory）API**:
  - **Java 21 では JEP 442（3回目のプレビュー）** → **`--enable-preview` が必要**
  - **Java 22 で JEP 454 により正式化**（Oracle/GraalVM ドキュメントで確認）
- **結論**: **MC 1.21.11（Java 21）では FFM を出荷コードに使えない**
  （ユーザーに `--enable-preview` を要求する Mod は配布物として不適切）。
  → **`sun.misc.Unsafe` / LWJGL `MemoryUtil` / ASM 生成コード**で off-heap を扱う。
- MC 26.x（Java 25）なら FFM が正式機能として使える（＝26.x 対象なら選択肢が増える）。

## 2. Sodium が実際にやっている JVM 最適化（ソースで確認）

### 2.1 off-heap コピーは `Unsafe` 直呼び
`common/src/api/java/net/caffeinemc/mods/sodium/api/memory/MemoryIntrinsics.java`（実コード）:
```java
public static void copyMemory(long src, long dst, int length) {
    // This seems to be faster than MemoryUtil.copyMemory in all cases.
    UNSAFE.copyMemory(src, dst, length);
}
```
- `Unsafe` は `Unsafe.class.getDeclaredField("theUnsafe")` + `setAccessible(true)` で取得。
- **LWJGL の `MemoryUtil.copyMemory` より速い、と Sodium 自身がコメントしている。**
- Javadoc に明記された警告（原文）:
  > WARNING: This function makes no attempt to verify that the parameters are correct.
  > If you pass invalid pointers or read/write memory outside a buffer, the JVM will likely crash!

**AMD-Faster への含意**: **`MemoryIntrinsics` は Sodium の公開 API** なので、
自前で `Unsafe` を触らず**これを使う**。

### 2.2 頂点シリアライザは**実行時バイトコード生成（ASM）**
- `VertexSerializerRegistryImpl.java:88`（実コード）:
  ```java
  var bytecode = VertexSerializerFactory.generate(srcVertexFormat, dstVertexFormat, identifier);
  ```
- `serializers/generated/VertexSerializerFactory.java`（実コード）:
  ```java
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  public static Bytecode generate(VertexFormat srcFormat, VertexFormat dstFormat, String identifier) {
      var name = ".../serializers/generated/VertexSerializer$Impl$" + identifier;
      ClassWriter classWriter = new ClassWriter(0);
  ```
- 生成後はリフレクションでコンストラクタを引いてインスタンス化
  （`VertexSerializerRegistryImpl` のエラーメッセージ "Failed to find constructor of generated class" ほか）。

**AMD-Faster への含意（★重要）**:
> **ホットパスは「リフレクションでも FFM でもなく、実行時に専用クラスを生成する」が正解**。
> リフレクション/汎用ループは C2 のインライン展開を阻害する。
> AMD-Faster が自前の頂点/メッシュ変換を持つなら **同じ ASM 生成方式**を採る。
> 公開 API 側の受け口は `VertexSerializerRegistry` / `VertexBufferWriter` /
> `VertexFormatRegistry`（`04` §1.1）。

## 3. LWJGL の版本と使うべきモジュール

| 出典 | LWJGL | 備考 |
|---|---|---|
| **VulkanMod 0.6.8**（`gradle.properties`/`build.gradle` 実測） | **3.3.3** | `lwjgl-vulkan`, `lwjgl-vma`, `lwjgl-shaderc`, `lwjgl-spvc` ＋ natives 4種を JiJ |
| **Sodium 0.9.3-alpha.1**（`common/build.gradle.kts` 実測） | **3.4.3** | `configurationPreLaunch` に `lwjgl`, `lwjgl-opengl`, **`lwjgl-sdl`** を pre-launch 依存として分離 |

- Sodium のコメント（実コード）:
  > We need to be careful during pre-launch that we don't touch any Minecraft classes,
  > since other mods will not yet have an opportunity to apply transformations.
  → **Mod の初期化を「Minecraft クラスに触れる前／後」で分ける**という設計。
  AMD-Faster も **GPU 探测を pre-launch でやりたくなるが、同じ注意が必要**。

**AMD-Faster への含意**: **Minecraft が同梱する LWJGL 版本に合わせる**（1.21.11 なら 3.3.x 系）。
バージョンがズレると `natives` の ABI や API シグネチャで壊れる。
**`lwjgl-sdl`（Sodium が導入）はウィンドウ/入力側の話**で、レンダラには不要。

## 4. Minecraft 側のホットスポット（Blaze3D / SectionRenderDispatcher 系）

- **Sodium が既に書き換えている範囲**（`04` §1）:
  - チャンクメッシュのビルド（`render/chunk/compile`, `async`）
  - ライティング（`model/light`）
  - カリング（`render/chunk/occlusion/{OcclusionCuller, SectionTree, RayOcclusionSectionTree,
    DirectionalVisGraph, VisibilityEncoding, CullType, GraphDirection(Set)}`）
  - 半透明ソート（`render/chunk/translucent_sorting`）
  - ビューポート/フラスタム（`render/viewport`, `render/viewport/frustum`）
- **AMD-Faster が追加で握るべき点**（Sodium が握っていない or 弱い所）:
  1. **GPU 側カル**（Sodium は CPU で可視集合を作る）
  2. **半透明の GPU ソート**（Sodium は CPU ソート。Nvidium は GPU）
  3. **アップロード経路**（`04` §1.3 のリング→アリーナコピー）
  4. **パイプライン/状態変更の削減**（Sodium は region ごとに draw＝region 数ぶん状態が変わる）
  5. **クリアと fast clear**（`01` §8）

## 5. GC / アロケーション（設計ルール）

- Minecraft は既定で **G1**。チャンクビルドは一時的に大量のオブジェクトを生む。
- AMD-Faster のルール:
  - **フレームあたり 0 アロケーションを目標**（fastutil の primitive 集合、`int[]`、off-heap を使う）。
    Sodium も `it.unimi.dsi.fastutil` を多用（`MappedStagingBuffer` の
    `ObjectArrayFIFOQueue` など、実コードで確認）。
  - **可視集合・カル結果は `int[]` / `IntOpenHashSet`**（Sodium/Nvidium 両方がこの流儀。
    Nvidium の `IntOpenHashSet regionsToSort`、`IntAVLTreeSet regions` を実コードで確認）。
  - **毎フレームの `IntStream`/ボックス化/ラムダキャプチャを避ける**。
  - **JFR / async-profiler で alloc プロファイル**を取り、
    **spark で in-game のスレッド別コスト**を見る。
- **JMH** でメッシュビルド・頂点シリアライズ・ソートのマイクロベンチを用意する
  （Minecraft を起動せずに回せる形にする）。

## 6. Fabric / Mixin 実務

- 対象（1.21.11）の実測構成（`04` §1.1）:
  - Fabric Loader **0.19.2**（Sodium 0.8.12 の `BuildConfig`）／**0.18.4**（VulkanMod 0.6.8）
  - Fabric API **0.140.0+1.21.11**
  - Yarn mappings **1.21.11+build.2**
  - **fabric-loom-remap 1.16.1**（Sodium 0.8.12 の `common/build.gradle.kts` 実測。
    開発ブランチ側は `net.fabricmc.fabric-loom` 1.18.2）
- **MixinExtras 0.5.0** を Sodium が使用（`common/build.gradle.kts` 実測）。
  `@WrapOperation` 等が使える → **`@Inject` の羅列より壊れにくい**。
- **fabric.mod.json の `depends`**:
  ```json
  "depends": { "fabricloader": ">=0.19", "minecraft": "~1.21.11", "java": ">=21", "sodium": ">=0.8" }
  ```
  を基本形にする。**`suggests` に `iris`** を入れて併用フォールバックを示す。
