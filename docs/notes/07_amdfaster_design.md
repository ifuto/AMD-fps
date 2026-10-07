# 07 — AMD-Faster 設計メモ（読み込み結果の統合と実装方針）

> **本ドキュメントは「まだ Mod を作らない」段階の設計メモ。**
> 根拠となる一次情報は `01`〜`06` の各メモに、出典と引用つきで記録してある。

---

## 1. 目的と前提

- **Nvidium の AMD 版**を作る。ただし Nvidium の手法をそのまま移植は**できない**
  （根拠: `04` §3.2, `01` §5 — **AMD は sparse/tiled resource を公式に非推奨**、
  `GL_NV_shader_buffer_load` は AMD に存在しない）。
- クライアントサイドのみ。**サーバーには何も送らない**。
- **Modrinth アプリで1ファイル導入できる**こと（ネイティブは LWJGL の natives 分類子を JiJ。
  実証例: VulkanMod の `build.gradle`、`04` §2.2）。
- **Sodium の上に載る**（単独レンダラにしない）。Sodium 0.8.x（MC 1.21.11）を前提に、
  公開 API（`common/src/api`）＋最小 Mixin で結合する。

## 2. ターゲット環境マトリクス（★APU を忘れない）

| 世代 | 代表 | wave | 主な注意 |
|---|---|---|---|
| **GCN / Polaris** | RX 400/500, **Vega 8/11 APU** | wave64 | **copy queue 使用時に L2 フラッシュが発生**（`02` §12）。UAV 書き込みで圧縮解除 |
| **Vega（dGPU）** | Vega 56/64 | wave64 | 同上。**DCC 圧縮書き込みが CU で不可**（`02` §12 の表） |
| **RDNA1** | RX 5700 (XT), **Navi 14** | wave32/64 | Copy Engine も L2 クライアント → L2 フラッシュほぼ無し |
| **RDNA2** | RX 6000, **680M/660M** | wave32/64 | Infinity Cache 128MB（RX6800系、`gpuopen.com/rdna2/` の表） |
| **RDNA3 / 3.5** | RX 7000, **780M/890M** | wave32/64 | **NGG culling が GFX11+ で既定 OFF**（`03` §2）→ 自前カルが有効 |
| **RDNA4 (GFX12)** | RX 9000 | wave32/64 | **HiZ の不具合で RADV 既定 `full` 緩和＝early-Z が効きにくい**（`03` §5） |

**判定方法（ベンダ文字列で分岐しない）**:
- Vulkan: **`VK_AMD_shader_core_properties` / `2`** で shader engine 数・CU 数・wave 情報を取得（`05` §9）。
  加えて `VkPhysicalDeviceProperties` の limits と、
  **memory type 列挙で `DEVICE_LOCAL|HOST_VISIBLE` のヒープサイズ**を見る。
- OpenGL: **機能（拡張）で判定**。`GL_ARB_indirect_parameters` / `GL_ARB_buffer_storage` /
  `GL_AMD_pinned_memory` / `GL_EXT_memory_info` 等の有無。
- **APU / ReBAR 判定**（`01` §5, `03` §2, `05` §2）:
  ```
  largestHostVisibleDeviceLocalHeap = max{ heap.size | heap に DEVICE_LOCAL|HOST_VISIBLE を含む type がある }
  if (largestHostVisibleDeviceLocalHeap >= 0.5 * totalDeviceLocalHeap)  →  UMA/ReBAR パス
  else                                                                  →  staging copy パス
  ```

## 3. アーキテクチャ（3レイヤ）

```
┌─────────────────────────────────────────────────────────────────┐
│  amd-faster-fabric  (Fabric Mod, Mixin, 設定GUI, Sodium連携)      │
├─────────────────────────────────────────────────────────────────┤
│  amd-faster-core    (ベンダ非依存のレンダリング戦略)               │
│   ├ ChunkGeometryStore   : 巨大アリーナ + オフセット表（sparse禁止）│
│   ├ GpuCuller            : compute カル + count buffer            │
│   ├ GpuTranslucencySort  : sorting network (wave32/64両対応)      │
│   ├ UploadPath           : UMA/ReBAR 直書き ⇔ staging ring        │
│   └ FramePlanner         : submit/fence/バリアの計画（06章の予算）  │
├─────────────────────────────────────────────────────────────────┤
│  amd-faster-backend-{gl,vk}  (API 実装)                          │
│   GL: LWJGL OpenGL 4.6 + ARB_indirect_parameters ほか             │
│   VK: LWJGL Vulkan + VMA + shaderc（すべて JiJ で同梱）           │
└─────────────────────────────────────────────────────────────────┘
```

**ポイント**: **backend を interface で分離し、GL を最初に完成させる**。
理由は「`GL_ARB_indirect_parameters` があれば GPU 側カル → count buffer →
multi draw indirect が **OpenGL のまま**実現できる」（`05` §8）。
つまり **Vulkan 化は最適化であって前提ではない**。段階的に移行できる。

## 4. 機能の優先順位（効果／リスク／根拠）

| # | 機能 | 効果 | リスク | 根拠 |
|---|---|---|---|---|
| 1 | **fast clear 対応のクリア色＋`LOAD_OP_CLEAR`/`vkCmdClearAttachments`** | 中〜大（クリアが約100倍速） | 極小 | `01` §8（色は (0,0,0,0)/(0,0,0,1)/(1,1,1,0)/(1,1,1,1)） |
| 2 | **アップロード経路の UMA/ReBAR 直書き化** | 大（APU で特に） | 小 | `01` §5, `03` §2 (`sam`/`dmashaders`), `05` §2, `04` §1.3 |
| 3 | **descriptor / root signature ≤ 13 DWORD 化 + bindless** | 大 | 中 | `01` §7（DWORD 内訳つき） |
| 4 | **GPU 側カル（compute + count buffer + コンパクション）** | 大 | 中 | `01` §11, `05` §5, `03` §2（NGG culling 既定OFF） |
| 5 | **draw をパイプライン順にソート／パイプライン数削減＋単一 pipeline cache** | 中〜大 | 小 | `01` §3 |
| 6 | **半透明の GPU ソート** | 中 | 中 | `04` §3.3（Nvidium が実装済み・AMD 向けに要再設計） |
| 7 | **vertex pull 化（vertex stream 廃止）** | 中 | 中 | `01` §6, §11 |
| 8 | **シェーダ最適化（arc関数排除 / FP16 / readfirstlane / Gather4 / ShuffleXor）** | 中 | 小 | `01` §12, `02` §11 |
| 9 | **workgroup size の世代別切替（RDNA=128, GCN=256）** | 小〜中 | 小 | `02` §6, §9 |
| 10 | **RDNA4 向け Z pre-pass / `VK_AMD_shader_early_and_late_fragment_tests`** | 中（RX9000限定） | 中 | `03` §5, `05` §9 |
| 11 | **async compute / copy queue の限定利用** | 小 | 中 | `06` §4（**AMD 自身が効果を限定と明言**） |
| 12 | **メッシュビルド（CPU側）の最適化** | 大（要計測） | 中 | `08`（JVM/LWJGL メモ） |

## 5. 配布設計（Modrinth）

VulkanMod 0.6.8 の `build.gradle` が実証している形をそのまま使う（`04` §2.2）:
```gradle
lwjglVersion = "3.3.3"   // Minecraft 同梱に合わせる（Sodium の boot は 3.4.3 を使用）
include(implementation("org.lwjgl:lwjgl-vulkan:$lwjglVersion"))
includeNatives("org.lwjgl:lwjgl-vma")      // natives-{windows,linux,macos,macos-arm64}
includeNatives("org.lwjgl:lwjgl-shaderc")
includeNatives("org.lwjgl:lwjgl-spvc")
```
- **Fabric API は必要モジュールだけを JiJ**（`fabric-api-base`, `fabric-resource-loader-v0/v1`, …）。
- **Sodium は JiJ しない**（`depends` で宣言）。
- **`AMD-Faster` は Sodium と競合しない別 jar**（Nvidium と同じ立ち位置）。
- **設定ファイル**: `config/amdfaster.json`（Sodium の `SodiumConfigStore` 方式に倣う）。
- **推奨環境変数／drirc は README に記載**（Mod から `setenv` しても手遅れなため）。

## 6. 計測・検証計画（★「速くなった」を証明する手順）

### 6.1 開発時
- **Vulkan validation + best-practices レイヤーを常時 ON**（AMD 固有チェックが SDK 1.2.189+ で入る。`01` §1）。
  **warning ゼロ運用**。
- **RGA**（GLSL/SPIR-V → ISA / VGPR / occupancy 静的解析）でシェーダを審査。
  Linux では **`RADV_DEBUG=shaderstats` / `asm` / `ir`** が代替（`03` §1）。
- **`RADV_DEBUG=psocachestats`** でプリコンパイルのヒット/ミスを検証（`03` §1）。
- **RMV** でチャンクメッシュのバッファ戦略（アリーナ断片化）を検証。
- **GPU Detective / `RADV_DEBUG=hang`** でデバイスロスト調査（`03` §1）。
- **RGP**（Windows: DX12/Vulkan のみ、Linux: `MESA_VK_TRACE=rgp`）で
  **Barriers UI の Drain time** を見る（`06` §7）。

### 6.2 A/B スイッチとして使える環境変数（Mesa / Linux）
| 検証したいこと | 環境変数 |
|---|---|
| fast clear が効いているか | `RADV_DEBUG=nofastclears` |
| DCC 圧縮の効果 | `RADV_DEBUG=nodcc,nodisplaydcc` / `AMD_DEBUG=nodcc` |
| persistent-mapped アップロードの効果 | `AMD_DEBUG=nowc`（GTT write combining 無効） |
| NGG カリングの効果 | `AMD_DEBUG=nongg` / `nonggc` / `nggc` |
| wave32/64 の影響 | `RADV_PERFTEST=pswave32,cswave32,gewave32` / `AMD_DEBUG=w32ps,w64ps` |
| ReBAR/SAM 最適化の効果 | `RADV_PERFTEST=nosam` |
| queue フォールバックの検証 | `RADV_QUEUE_DISABLE=compute,transfer` |
| 同期バグの切り分け | `RADV_DEBUG=syncshaders` / `fullsync` |
| クロック固定 | `RADV_PROFILE_PSTATE=peak`（既定） |

### 6.3 Mod 内蔵の計測
- **フレームタイム**: `VK_EXT_present_timing` / `VK_EXT_calibrated_timestamps`（Vulkan）、
  OpenGL は `GL_ARB_timer_query`。
- **HUD 表示項目**: draw 数 / dispatch 数 / **バリア数** / **fence 数（目標 ≤9）** /
  **パイプライン数** / **VGPR 最大値と推定 occupancy** / **VRAM 使用量と budget（80%ルール）**。
- **JMH でマイクロベンチ**（メッシュビルド、頂点シリアライズ、ソート）。
  実機は **spark**（Minecraft プロファイラ）＋ **async-profiler / JFR**。

## 7. リスクと対策

| リスク | 内容 | 対策 |
|---|---|---|
| **MC バージョン体系の変更** | 1.21.11 の次は **26.1**（Java 25 必須）。**26.x には Mojang 公式の Vulkan バックエンド（RenderPearl）が存在**（`04` §0） | **対象バージョンを確定してから着手**（要ユーザー判断）。26.x なら設計が根本から変わる |
| Sodium の内部変更 | Nvidium は Sodium 内部へ深く Mixin して追随不能に（`04` §3.4） | 公開 API ＋ 最小 Mixin。バージョン差分を1ファイルに閉じ込める |
| Iris 併用 | シェーダパックが地形パスを奪う | 初日から Iris 検出＆フォールバック（Nvidium の `IrisCheck` 相当） |
| AMD ドライバの世代差 | sparse 非推奨 / copy queue の L2 フラッシュ / GFX11 NGG / GFX12 HiZ | **世代別プロファイル**を持ち、機能検出で分岐 |
| **ライセンス（★要注意）** | **Sodium 0.8.12(1.21.11) も dev も `PolyForm Shield License 1.0.0`**（`LICENSE.md` を実確認。LGPL では**ない**）。VulkanMod / Nvidium / Iris は **LGPL-3.0** | **§7.1 を必ず読む**。Sodium のコードを流用しない。**Sodium の上に載る add-on として作る** |
| native 配布 | LWJGL natives が重い | JiJ（VulkanMod 実証済み）。macOS は Vulkan が MoltenVK 依存 → **当初は Windows/Linux のみサポート**を明記 |

### 7.1 ライセンス実態（★各リポジトリの `LICENSE*` を実際に開いて確認した結果）

| 参照先 | ライセンス | AMD-Faster への影響 |
|---|---|---|
| **Sodium 0.8.12（MC 1.21.11）** | **PolyForm Shield License 1.0.0** | **ソースの流用・派生は実質不可**（下記） |
| Sodium dev（0.9.3-alpha.1 / MC 26.3） | **PolyForm Shield License 1.0.0** | 同上 |
| **VulkanMod 0.6.8** | **LGPL-3.0** | 参照・派生可（LGPL 義務を負う） |
| **Nvidium 0.3.0** | **LGPL-3.0** | 参照・派生可（LGPL 義務を負う） |
| **Iris** | **LGPL-3.0** | 同上 |

**PolyForm Shield の該当条項（`sodium-1211/LICENSE.md` の原文）**:
> ## Noncompete
> Any purpose is a permitted purpose, except for providing any product that
> **competes with the software** or any product the licensor or any of its
> affiliates provides using the software.
>
> ## Competition
> Goods and services compete even when they provide functionality through
> different kinds of interfaces or for different technical platforms.
> Applications can compete with services, **libraries with plugins**, frameworks
> with development tools … **Goods and services compete even when provided free
> of charge. If you market a product as a practical substitute for the software
> or another product, it definitely competes.**

**AMD-Faster への結論（設計制約）**:
1. **Sodium のコードをコピーして「Sodium の置き換え」を作るのは不可**
   （"practical substitute" は明示的に競合とされる）。
2. **Sodium の上で動く add-on（Nvidium と同じ立ち位置）なら競合ではない** —
   Sodium を置き換えず、Sodium を前提に機能追加するから。
   → **`07` §1 の「Sodium の上に載る（単独レンダラにしない）」は
   性能上の理由だけでなくライセンス上の理由でもある。**
3. **VulkanMod 方式（Blaze3D を丸ごと置き換える独立レンダラ）を採るなら、
   Sodium のソースは一切参照実装に使わない**こと（LGPL の VulkanMod / Nvidium / Iris は可）。
4. **AMD-Faster 自身のライセンス**: **LGPL-3.0 推奨**
   （Sodium 由来コードを含まないため PolyForm の制約を受けない。
   Nvidium/VulkanMod と同じ＝エコシステムと整合）。

## 8. 段階的マイルストーン（提案）

1. **M0 — 計測基盤**: HUD（draw/dispatch/バリア/fence/パイプライン数）＋ JMH ＋ A/B スイッチ。
   *最適化の前に「今どうなっているか」を出す。*
2. **M1 — 低リスク最適化**: fast clear 色、パイプライン順ソート、単一 pipeline cache、
   `glGetError` の削減、descriptor/uniform 整理。
3. **M2 — アップロード経路**: UMA/ReBAR 判定 → 直書きパス。APU で最大効果。
4. **M3 — GPU カル**: compute カル + count buffer + argument コンパクション + vertex pull。
5. **M4 — 半透明 GPU ソート**（wave32/64 両対応、LDS 32bank 対策）。
6. **M5 — Vulkan バックエンド**（VMA + dynamic rendering + descriptor indexing/heap）。
7. **M6 — mesh shader パス**（`VK_EXT_mesh_shader` / `VK_AMDX_shader_enqueue` がある場合のみ）。

## 9. 未解決の判断事項（ユーザー確認が必要）

1. **対象 Minecraft バージョン**: `1.21.11`（Java 21 / OpenGL のみ / Sodium 0.8.12）か、
   `26.3`（Java 25 / **公式 Vulkan バックエンドあり** / Sodium 0.9.x）か。
2. **最初のバックエンド**: OpenGL 優先（低リスク・段階的）か、Vulkan 優先（RGP が使える・上限が高い）。
3. **Sodium 依存を必須にするか**（Nvidium 方式）／独立レンダラにするか（VulkanMod 方式）。
