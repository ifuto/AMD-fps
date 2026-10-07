# 03 — Mesa 3D（RADV / ACO / RadeonSI）環境変数とチューニング knob

出典（実際に取得）:
- **Mesa 3D 公式ドキュメント `https://docs.mesa3d.org/envvars.html`**（全13チャンクのうち
  chunk 0, 7, 8, 9, 10 を取得・通読。RADV / RadeonSI / ACO の全セクションを含む）
- Mesa のソースは **GitLab (gitlab.freedesktop.org) にのみ存在し、GitHub ミラーは無い**
  （`api.github.com/repos/mesa3d/mesa` → `NotFound`、`orgs/mesa3d/repos` は `.github` のみ）。
  curl では gitlab.freedesktop.org に到達できないため、**ソース精読は docs サイト経由に限定した**。

> AMD-Faster にとっての意味: **Linux 版のユーザー向けトラブルシュート/最適化案内は
> 「環境変数 + driconf」で書ける**。Mod 側で環境変数を強制設定するのではなく、
> **README に「推奨環境変数プロファイル」を載せる**のが正道（Mod 側から `setenv` するのは
> JVM 起動後に遅すぎる＝ドライバ初期化時に読まれるため）。

---

## 1. RADV（Linux の Vulkan ドライバ）— `RADV_DEBUG`

AMD-Faster のデバッグ/検証に直結するものだけ抜粋（原文フラグ名そのまま）:

| フラグ | 効果 | AMD-Faster での用途 |
|---|---|---|
| `info` | GPU 情報を表示 | 起動時ログの照合 |
| `startup` | 起動時に情報表示 | 同上 |
| `hang` | GPU ハング検出＋レポートを `$HOME/radv_dumps_<pid>_<time>` に出力 | **device lost 調査**（Radeon GPU Detective の Linux 相当） |
| `dumpibs` | コマンドストリーム（IB）をダンプ | **command buffer の中身検証** |
| `shaders` / `spirv` / `nir` / `ir` / `asm` | シェーダを各段階でダンプ | **GLSL→ACO→ISA の確認** |
| `vs`,`tcs`,`tes`,`gs`,`ps`,`task`,`mesh`,`cs` | ステージ別ダンプ | 同上 |
| `shaderstats` | シェーダ統計（VGPR/SGPR 等） | **RGA の代替**（RGA が使えない Linux で有効） |
| `psocachestats` | **PSO キャッシュのヒット/ミスを出力（シェーダ事前コンパイルの検証用）** | **Mod のプリコンパイル検証に最適** |
| `pso_history` | PSO 履歴を `/tmp/radv_pso_history.log` へ（UMR/Fossilize 用） | ハング調査 |
| `nofastclears` | fast color/depth clear を無効化 | **★fast clear が効いているかの A/B 検証に使える** |
| `nodcc` / `nodisplaydcc` / `nofmask` / `nohiz` / `notccompatcmask` | DCC / displayable DCC / MSAA FMASK / HiZ / TC-compat CMASK を無効化 | **圧縮の効果測定** |
| `nobinning` | primitive binning 無効化 | ジオメトリパスの調査 |
| `nongg` / `nonggc` | **NGG（Next Gen Geometry）/ NGG culling 無効化（GFX10/10.3）** | **★NGG カリングの効果測定** |
| `nomeshshader` | GFX10.3+ の mesh shader 無効化 | mesh shader パスの A/B |
| `nogpl` | `VK_EXT_graphics_pipeline_library` 無効化 | GPL 依存の検証 |
| `noeso` | `VK_EXT_shader_object` 無効化 | shader object 依存の検証 |
| `noheap` | `VK_EXT_descriptor_heap` 無効化 | **bindless 実装の検証** |
| `syncshaders` / `fullsync` | draw/dispatch ごとに同期（fullsync はキャッシュフラッシュ込み） | **同期バグの切り分け** |
| `nocache` | シェーダキャッシュ無効化 | 初回起動コストの測定 |
| `preoptir` | 最適化前の backend IR（ACO or LLVM）をダンプ | コンパイラ調査 |
| `vm` | **VA 割り当ての間にギャップを入れてページフォルト検出** | OOB アクセス検出 |
| `validatevas` | VA レンジ追跡 | 同上 |
| `bvh4` | bvh8 対応 GPU で bvh4 を使う | RT 調査 |
| `noibchaining` | IB チェーン無効化 | コマンド構築の調査 |

### `RADV_QUEUE_DISABLE`（テスト用）
`gfx`, `compute`, `vdec`, `venc`, `transfer`, `sparse` を個別に無効化できる。
→ **AMD-Faster の「async compute queue / transfer queue 使用パス」のフォールバック検証に使える**
（例: `compute` を殺して graphics queue フォールバックが正しく動くか確認）。

### `RADV_FORCE_VRS` / `RADV_FORCE_VRS_CONFIG_FILE`
- GFX10.3+ で **per-pipeline の vertex VRS rate を強制**（`2x2`,`1x2`,`2x1`,`1x1`）。
- config file 版は **GFX10.3 APU のみ**に影響。
- → **APU（680M/780M 等）向けの「頂点 VRS による軽量化」を Mod 側から使わずに検証できる手段**。

## 2. `RADV_PERFTEST`（★性能実験スイッチ — AMD 特化設計の核心）

| フラグ | 効果 | 含意 |
|---|---|---|
| `cswave32` | **compute shader を wave32 で（GFX10+）** | RDNA 以降はデフォルト wave32 化が進む。Mod の compute は両対応必須 |
| `gewave32` | vertex/tess/geometry を wave32 で | 同上 |
| `pswave32` | **pixel shader を wave32 で** | RDNA のデフォルトは PS=wave64（02章）→ **A/B 実験の手段** |
| `rtwave64` | RT シェーダを wave64 で | — |
| `sam` / `nosam` | **「全 VRAM が CPU 可視」のときに有効化される最適化**を ON/OFF | **★ReBAR/APU（UMA）専用の最適化が実在する証拠**。AMD-Faster は ReBAR 検出を必須にすべき |
| `dmashaders` | **シェーダを invisible VRAM にアップロード（non-resizable BAR 環境で有用）** | **ReBAR 無し環境向けの設計分岐が必要** |
| `localbos` | local BO 有効化 | メモリ戦略 |
| `nogttspill` | **メモリ確保時の GTT スピル無効化** | APU/GTT 枯渇時の挙動 |
| `nggc` | **GFX11+ で NGG culling 有効化** | GFX11 では既定 OFF → 明示 ON で性能差が出る余地 |
| `nircache` | グラフィックパイプラインの per-stage NIR キャッシュ | 起動時コンパイル短縮 |
| `dccmsaa` | MSAA 画像に DCC | — |
| `rtcps` | RT の CPS lowering | — |

**AMD-Faster への含意（重要）**:
1. `sam`/`nosam` と `dmashaders` の存在は、**RADV 自身が「ReBAR 有無」でメモリ戦略を切り替えている**ことを示す。
   → Mod 側も **`VkPhysicalDeviceMemoryProperties` を列挙して `DEVICE_LOCAL|HOST_VISIBLE` の
   ヒープサイズを判定**し、パスを分岐する（256MB 前後なら非 ReBAR、VRAM 全量なら ReBAR/UMA）。
2. **wave32/wave64 はシェーダステージ別にドライバが切り替える**。Mod が subgroup size を仮定すると壊れる。
3. **GFX11+ で NGG culling が既定 OFF** → AMD-Faster が **GPU 側カル（compute カル）を自前で実装する価値がある**。

## 3. `RADV_EXPERIMENTAL`
- `transfer_queue`: **実験的 transfer queue（GFX9+, まだ spec 非準拠）** → copy queue 依存は要注意。
- `emulate_rt`: GFX10_3+ で RT をソフトウェアエミュレート、旧世代でも RT 拡張を出す。
- `hic`: GFX10 で `VK_EXT_host_image_copy` 実験実装。
- `msrtss`: `VK_EXT_multisampled_render_to_single_sampled`。
- `bfloat16`: **GFX11〜11.5 で bfloat16 cooperative matrix**。
- `elf`: シェーダバイナリに ELF 形式（LLVM ビルド必須）。

## 4. RGP/SQTT 連携（★プロファイリング導線）

- `RADV_THREAD_TRACE_BUFFER_SIZE`: **SQTT/RGP バッファサイズ（既定 32MiB、自動拡大）**
- `RADV_THREAD_TRACE_CACHE_COUNTERS`: GFX10+ のキャッシュカウンタ（既定 ON）
- `RADV_THREAD_TRACE_INSTRUCTION_TIMING`: **命令レベルのタイミング（既定 ON）**
- `RADV_THREAD_TRACE_INSTRUCTION_TIMING_SE_MASK`: SE マスク（既定 0xFFFFFFFF）
- `RADV_THREAD_TRACE_QUEUE_EVENTS`: キューイベント（既定 ON）
- `RADV_CACHE_COUNTERS_BUFFER_SIZE`: キャッシュカウンタ用バッファ（既定 32MiB）
- `MESA_VK_TRACE=rgp` で **RGP トレースを取得**できる。
- **`RADV_SPM_COUNTERS_CONFIG`**: RGP トレース時に収集する**カスタム SPM カウンタ**を設定ファイルで指定（GFX10+）。
  フォーマット（原文）:
  ```
  [Cache]
  TCP_PERF_SEL_REQ=SQ_WGP,0x3,ALL,sum
  TCP_PERF_SEL_REQ_MISS=SQ_WGP,0x12,ALL,sum
  GL2C_PERF_SEL_REQ=GL2C,0x3,ALL,sum
  GL2C_PERF_SEL_MISS=GL2C,0x2b,ALL,sum
  ```
  - `BLOCK,EVENT_ID,INSTANCE,OP`（OP は `sum`/`max`/`avg`、INSTANCE は数値か `ALL`）
  - **制限: トレースあたり最大 8 グループ / 48 項目、1グループあたり 16 項目**
- `RADV_PROFILE_PSTATE`: `standard` / `min_sclk` / `min_mclk` / **`peak`（既定）**
  → **ベンチマーク時は既定の `peak` でクロック固定される**ので、Mod 内蔵ベンチの再現性が高い。
- `RADV_TEX_ANISO`: 異方性フィルタ強制（最大16）。

**AMD-Faster への含意**: **RGP は OpenGL を対象外**（ユーザー指摘の通り）だが、
**Linux では `MESA_VK_TRACE=rgp` で Vulkan バックエンドなら RGP が使える**。
→ Vulkan バックエンドを作る動機がもう一つ増えた。**ベンチ手順書にこの env を書く。**

## 5. `radv_gfx12_hiz_wa`（★★RDNA4 特有の既知問題）

原文:
> choose the specific HiZ workaround to apply on GFX12 (RDNA4)
> - `disabled` … 最適だが自己責任
> - `partial` … 部分緩和、概ね性能は最適
> - `full` … **完全に緩和、リスクなし。ただし性能が下がる可能性（デフォルト）**
> - `full_rez` … 完全緩和＋**HiZ 無効で失った early-Z を early-Z-then-ReZ で取り戻す**

**AMD-Faster への含意（極めて重要）**:
- **RX 9000（GFX12 / RDNA4）では HiZ に問題があり、RADV は既定で `full` 緩和＝early-Z が効きにくい。**
- Minecraft は**大量の半透明/カットアウトと奥行き方向のオーバーフロー**があり、early-Z の効きが性能に直結する。
- → **RDNA4 では「深度プレパス（Z pre-pass）」を Mod 側で入れる価値が高い**。
  あるいは `full_rez` を README で案内する（ユーザーが env を設定）。
- **実装**: GPU 世代を判定して **GFX12 のみ Z-prepass を自動 ON** にする候補。

## 6. `ACO_DEBUG`（ACO シェーダコンパイラ）

| フラグ | 効果 |
|---|---|
| `validateir` / `novalidate` | ACO IR 検証の ON/OFF |
| `validatera` | **レジスタ割り当ての検証（RA バグ検出）** |
| `force-waitcnt` / `force-waitdeps` | **waitcnt を強制発行（GFX10+ のハザード調査）** |
| `novn` | value numbering 無効 |
| `noopt` | 各種最適化を無効 |
| `nosched` / `nosched-ilp` / `nosched-vopd` | **pre-RA / ILP / VOPD スケジューリングを無効化** |
| `perfinfo` | **パイプライン統計の計算に使う情報を出力** |
| `liveinfo` | **スケジューリング前のライブネスとレジスタ要求量を出力** |

**AMD-Faster への含意**:
- **`liveinfo` で「シェーダのレジスタ要求量」を直接見られる** → VGPR 削減の指針。
- **`nosched-ilp` がある＝ACO は ILP スケジューリングを自動で行っている**。
  → 02章で見た「命令順で 18→11 サイクル」の話は ACO が一部やってくれるが、
  **GLSL の書き方で依存を作るとコンパイラでも直せない**ので、やはり書き方が重要。
- **`force-waitdeps`（GFX10+ のハザード調査）** → waitcnt 起因のバグ調査に使える。

## 7. RadeonSI（Linux の OpenGL ドライバ）— `AMD_DEBUG`

| フラグ | 効果 |
|---|---|
| `nodcc`, `nodccclear`, `nodisplaydcc`, `nodccmsaa` | DCC 系を無効化 |
| `nodpbb` / `dpbb` | **DPBB（Deferred Primitive Binning and Binning?）を無効/有効化。`gfx9 dGPU` では明示 `dpbb`、`gfx9 APU` と `>= gfx10` は既定 ON** |
| `nohyperz` | Hyper-Z 無効 |
| `nooutoforder` | **アウトオブオーダー・ラスタライズ無効** |
| `nongg` / `nggc` / `nonggc` | NGG / NGG culling の制御（`nggc` は既定 OFF の GPU でも強制 ON） |
| `w32ge` / `w32ps` / `w32cs` | **vertex/tess/geo, pixel, compute を Wave32 に** |
| `w64ge` / `w64ps` / `w64cs` | 逆に Wave64 に |
| `nowc` | **GTT write combining 無効**（＝persistent mapped buffer の性能が落ちる → 効果確認に使える） |
| `notiling` / `no2d` | ティーリング無効 |
| `nofmask` | MSAA 圧縮無効 |
| `mono` | 旧来のモノリシック・シェーダをオンデマンドコンパイル |
| `nooptvariant` | 最適化シェーダバリアントのコンパイル無効 |
| `usellvm` | 可能なら LLVM をシェーダコンパイラに（＝ACO を使わない） |
| `check_vm` / `reserve_vmid` | VM フォルト確認 / VMID 予約 |
| `switch_on_eop` | WD/IA を end-of-packet で切替 |
| `ibcachesflush` | **IB 先頭で全キャッシュをフラッシュ**（＝フラッシュコストの計測に使える） |
| `safe` / `safer` / `safest` | 最適化を段階的に無効化 |
| `precompile`（r600） | シェーダ生成時に 1 バリアントをコンパイル |

**AMD-Faster への含意**:
- **`nowc` で write combining を切ると Sodium 系の persistent-mapped アップロードがどのくらい遅くなるか**が測れる
  → **Mod のアップロード経路の性能寄与を定量化する実験手段**。
- **`nooutoforder`**: AMD のラスタライザは**アウトオブオーダー**に動く。
  → **順序依存を GPU に期待してはいけない**（＝translucent のソートは必須）。
- **`dpbb` が gfx9 APU と gfx10+ で既定 ON** → **APU ではプリミティブビニングが効く**。
  カリングを GPU 側に任せすぎるとビニングの効果が消える可能性 → **A/B 実験対象**。

## 8. driconf / drirc（アプリプロファイル）

- Mesa は **driconf（`drirc`）でアプリ別プロファイル**を持ち、
  `MESA_PROCESS_NAME` を上書きすると **driconf のアプリマッチングにその名前が使われる**（原文）。
- `MESA_EXTENSION_OVERRIDE` は `GL_EXT_foo -GL_EXT_bar` 形式で拡張の有効/無効を上書きでき、
  **driconf の設定より優先される**（原文）。
- `MESA_GL_VERSION_OVERRIDE` で `MAJOR.MINOR[FC|COMPAT]` 形式で GL バージョン/プロファイルを強制。
- `MESA_NO_ERROR=1` で **エラーチェックを無効化**（`GL_KHR_no_error` 相当。CPU コスト減、不正使用は UB）。

**AMD-Faster への含意（配布物アイデア）**:
1. **README/同梱ファイルとして「Minecraft 用 drirc スニペット」を配布**する。
   - アプリ名: Minecraft ランチャが起動するプロセス名（`java` / `javaw.exe` は OS により異なる。
     Windows の AMD OpenGL ドライバには driconf が無い＝**Linux 限定の案内**と明記する）。
2. **`MESA_NO_ERROR=1` は Mod 側の「GL 検証を自分でやる」設計と相性が良い**。
   AMD-Faster が draw 経路を握るなら、dev ビルドで検証・release でスキップする設計にすれば
   `MESA_NO_ERROR` 相当の削減を**Mod 内でも実現できる**（＝`glGetError` を毎 draw 呼ばない）。

## 9. その他の共通変数（抜粋）

- `MESA_DEBUG`（`silent`,`flush`,`incomplete_tex`,`incomplete_fbo`,`context`）
- `MESA_LOG_FILE` / `MESA_LOG_FILE_AUTO` / `MESA_LOG_LEVEL` / `MESA_LOG_PREFIX`
- `GALLIUM_HUD`（未読チャンクにあり — **要追加確認**）

## 10. 未読・未取得の記録（正直な記録）

- envvars.html の chunk 1〜6, 11〜13（GLSL / Gallium / Zink / Nine / VC4 / Asahi 等）は未取得。
  AMD に関連する RADV/RadeonSI/ACO/r600/r300 セクションは取得済み。
- **driconf の詳細ページ（`docs.mesa3d.org/drivers/radeonsi.html` 等）は未読** → 次タスク。
- Mesa ソース（`src/amd/vulkan/*`, `src/amd/compiler/*`）は **GitHub にミラーが無いため未読**。
