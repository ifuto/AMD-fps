# 06 — GPU キュー / プリエンプション / バリア（Breaking Down Barriers ほか）

## 取得方法についての正直な記録

| 資料 | 取得状況 |
|---|---|
| **Breaking Down Barriers Part 3（Multiple Command Processors）** | **本文を直接取得して読了**（therealmjp.github.io、chunk 0） |
| **同 Part 4（GPU Preemption）** | **本文を直接取得して chunk 0 を読了**（全3チャンク） |
| 同 Part 1 / 2 / 5 / 6 | **検索経由の抜粋（原文引用）のみ**。全文は未取得 |
| **GPUOpen「Leveraging asynchronous queues for concurrent execution」** | **検索経由の原文抜粋**（gpuopen.com/learn/concurrent-execution-asynchronous-queues/） |
| **GPUOpen "Micro Engine Scheduler Specification"（2024-04, PDF）** | **検索経由の原文抜粋**（gpuopen.com/download/micro_engine_scheduler.pdf） |
| **GPUOpen GDC2016 "Right on Queue: Advanced DX12 Programming"** | **検索経由の原文抜粋**（gpuopen.com/download/GDC_2016_D3D12_Right_On_Queue_final.pdf） |

> 注記: GPUOpen の `learn/breaking-down-barriers-*` という URL は **404 だった**。
> "Breaking Down Barriers" シリーズは **Matt Pettineo 氏（The Real MJP）の連載**で、
> **Khronos Vulkan Guide が公式に参照リンクとして挙げている**
> （`chapters/queues.adoc` の冒頭 NOTE が AMD の async queues 記事と並べて言及）。
> GPUOpen 側の対応記事は **"Leveraging asynchronous queues for concurrent execution"**。

---

## 1. バリア／フラッシュの実コスト（Part 3 から、直接読了）

- 「全 GPU スレッドの完了を待つ GPU 全体のバリア（＝**flush**）」は
  **非常に鈍い道具（very blunt instrument）**。
  - sub-dispatch 単位の依存を表現できない
  - **依存の無い dispatch 同士まで重ならなくなる**
  - 数千スレッドで埋める GPU では、**スレッド数が少なく実行時間が長い dispatch ほど
    コアが長時間アイドルになる**
- 改善策: **バリアを 2 段階に割る（split barrier）**
  = **`SIGNAL_POST_SHADER`（完了通知）と `WAIT_SIGNAL`（待ち）を分離**。
  これにより「全スレッド待ち」ではなく「1 dispatch 分のスレッドだけ待つ」にできる。
  ただし **1回しか使えない（signal が "全キュー済みスレッド完了" でしか出せない場合）**。
- 実例: **bloom はダウンサンプル数回＋ブラー数回＋アップスケール数回＝6〜8 dispatch が
  数珠つなぎ**になる。等時間の大 dispatch と重ねたい場合、
  単純な重ね方では**最初のバリアで長い無overlap 区間**ができる。
  → **split barrier でバリア自体を大 dispatch と重ねる**のが正解。

**AMD-Faster への含意**:
- **Vulkan では `vkCmdPipelineBarrier` を「src/dst を分けて 2 回」に割れる**
  （Vulkan のバリアは本質的に split barrier として書ける）。
  → **カル結果（indirect buffer）を書く compute と、それを使う draw の間に
  他の作業（例: 半透明ソート、次フレームのアップロード）を挟む**。
- **1フレームのバリア数を数えて HUD に出す**。RGP の Barriers UI は
  「他イベントと重なっていない時間だけをカウントする」（後述）。

## 2. プリエンプション粒度（Part 4 から、直接読了 + AMD スケジューラ仕様）

- OS/ドライバは **アプリの submission を小さい command buffer に分割**することで、
  高優先度ワークを割り込ませやすくする（＝**command buffer-level preemption**）。
- **プリエンプション遅延は「1つの command buffer の実行時間」に縛られる**。
  そして**ドライバは command buffer の実行時間を予測できない**（原文）。
- **GPUOpen Micro Engine Scheduler 仕様書（原文抜粋）が AMD の粒度を明記**:
  > compute work can be preempted at a **submission, dispatch, thread group or at a
  > shader instruction boundary**. The preemption latency and amount of saved or restored
  > states will vary based on the preemption granularity.
- 同仕様書のその他の原文抜粋:
  - キュー状態: `Unmapped` / `Mid command buffer preemption` など。
    **hardware queue は preempt 後にしか unmap できない**。
  - **Focus / Normal 優先度**: Focus キューは Normal と同じ connection priority だが
    **quantum が大きく、pipe priority が高い**。
    **Normal キューは長いシェーダが Focus の発射を妨げていると unmap されることがある**。
  - **`gang_quantum` — Quantum provided by Windows OS, usually 2ms, queue is considered
    "expired" after its quantum runs out**

**AMD-Faster への含意（★Minecraft 特有の重要ポイント）**:
- **Windows では OS の quantum が約 2ms**。
  **1つの dispatch / 1つの command buffer が 2ms を超えると、
  Minecraft のフレームが OS によって強制的に切られ、遅延とジッタの原因になる。**
- → **「1 dispatch が長くなる GPU カル／ソートは分割する」**。
  Minecraft の描画時間は 16.6ms（60fps）だが、**GPU 側の1単位は 1〜2ms に収める**設計にする。
- これは GPUOpen GDC2016 の推奨（下記 §4）とも一致する。

## 3. AMD のハードウェアキュー構成（Part 5 の原文抜粋）

> AMD's processors support **up to 8 ACE's on a single chip**, potentially allowing for
> **64 different command streams to be in-flight simultaneously** (each ACE contains up to
> **8 hardware queues**).
> The separate queues support various scheduling and synchronization operations, effectively
> allowing them to serve as a very simple hardware task scheduler.
> Their documentation suggests that this allows ACE's to **submit high-priority workloads
> that take priority over work submitted from other command processors**, which should allow
> for the kind of thread-level preemption …

**AMD-Faster への含意**: **ハードウェアは十分に多くの queue を持つ**。
制約は HW ではなく **API と OS スケジューラ**。
→ queue 数を増やすより、**submit 回数とバリア数を減らす**方が効く（01章 §2, §8 と一致）。

## 4. GPUOpen 公式の非同期キュー指針（原文抜粋、★最重要）

**"Leveraging asynchronous queues for concurrent execution"**（gpuopen.com/learn/）:

> Since latency for memory access can cause significant stalls in shader execution,
> **up to 10 wavefronts can be scheduled on each SIMD simultaneously** to hide this latency.

> **GCN hardware contains a single geometry frontend**, so **no additional performance will
> be gained by creating multiple direct queues** in DirectX 12. **Any command lists scheduled
> to a direct queue will get serialized onto the same hardware queue.**

> **While GCN hardware supports multiple compute engines we haven't seen significant
> performance benefits from using more than one compute queue** in applications profiled so far.

> Splitting tasks into sub-tasks and interleaving them can reduce barriers and create
> opportunities for efficient async compute usage (e.g. instead of
> "for each light: clear shadow map, render shadow, compute VSM"
> do **"clear all shadow maps, render all shadow maps, compute VSM for all shadow maps"**).

**AMD-Faster への含意（★★設計に直結）**:
1. **graphics queue を複数作っても AMD では無意味**（単一ジオメトリフロントエンドで直列化される）。
   → **graphics queue は 1 本**。
2. **compute queue も 1 本で十分**（AMD 自身が「複数で有意な効果を見なかった」と明言）。
   → VulkanMod の `ComputeQueue` 1本構成（04章 §2.4）は正しい。
3. **「各オブジェクトについて A→B→C」ではなく「全 A → 全 B → 全 C」に並べ替える**。
   → **Minecraft では: 「チャンクごとに メッシュ化→アップロード→描画」ではなく
   「全チャンク メッシュ化 → 全チャンク アップロード → 全チャンク 描画」**。
   これは Sodium が既にやっている構造（build → upload → draw の3フェーズ）と一致し、
   **AMD で正しいことが裏付けられた**。
4. `10 wavefronts / SIMD` は 02章の「RDNA: 20 waves / SIMD32」とは別の世代の話（GCN）。
   **世代で数字が違うので、occupancy 目標を世代別に持つ**こと。

## 5. GPUOpen GDC2016 "Right on Queue"（原文抜粋）

- **Implementation advice（原文）**:
  - **Build a job based renderer** — This will help with barriers, too!
  - Manually specify which tasks should run in parallel
  - **Jobs should not be too small**
  - **Keep number of fences/frame in single digit range**
  - **Each signal stalls the frontend and flushes the pipeline**
- **ASYNC COMPUTE IN ASHES（原文）**:
  - **No preemption on most cards**
  - Thus, **break apart frame to have multiple submits, trying to keep command buffers in the
    1-2ms range**
  - Windows can then insert present at the boundary
  - End up with only about **1/2 to 1/3 extra latency**
- **BARRIERS（原文）**:
  - barrier の役割は **Synchronisation / Visibility / Format conversion** の3つ
  - **Split barrier**: "Done" after draw 1, "Make ready" before draw 3 → **draw 2 は影響を受けない**
  - **Multiple simultaneous barriers**: 「全部のバリアを一度に片付ける」
  - キャッシュ構成: **Many small L1 "caches", Big L2 cache, connected mostly to shader core**

**AMD-Faster への含意（★具体的な数値目標）**:
| 指標 | 目標値 | 根拠 |
|---|---|---|
| **fence / frame** | **1桁**（≤ 9） | GDC2016 原文 |
| **command buffer 1本の長さ** | **1〜2 ms** | GDC2016（Ashes 実例）+ MES quantum 2ms |
| **command buffer 1本あたりの draw/dispatch** | **≥ 10** | RDNA Perf Guide（01章 §2） |
| **submit / frame** | できるだけ少なく（sync 時とフレーム末尾） | RDNA Perf Guide |
| **signal ごとにフロントエンドがストールしパイプラインがフラッシュされる** | signal を減らす | GDC2016 |

## 6. Part 6（実測系）の原文抜粋

- **`PRIORITY_HIGH` フラグ**: AMD と Nvidia では **ほとんど差が出ない**。
  Intel では COMPUTE submission が DIRECT より先に実行される（＝**このフラグは
  「OS が複数ソフトウェアキューを単一 HW キューに直列化する場合の順序」にしか影響しない**）。
- **プリエンプション解析**: GPUView で見ると、アプリと VR コンポジタ（Oculus Home）の
  submission が **別々のハードウェアキュー（`3D` と `Graphics_1`）**に乗る。
  **大 dispatch が GPU を占有すると、他の submission は ~100ms 完了を待たされた**
  （＝**実行中の dispatch は preempt できなかった**）。

**AMD-Faster への含意**: **大きな dispatch は「他プロセスの GPU 作業を止める」**。
Minecraft + Discord/ブラウザ + VR という現実的な環境で **100ms のスタック**を起こしうる。
→ **GPU カル/ソートの dispatch は必ず小さく分割する**（§2 の 2ms ルールと同じ結論）。

## 7. RGP のバリア解析（gpuopen.com/manuals/rgp_manual/overview_windows/ の原文抜粋）

- RGP の Barriers UI は **ドライバが挿入した追加バリアも含めて**一覧表示する。
- **パーセンテージ計算は「どのキューのどのイベントにも重なっていない時間」だけを数える**。
  例: バリアが 100ns でも、うち 80ns が他イベントと重なっていれば **20ns だけが計上**。
- 列挙される情報: **Event Numbers / Duration / Drain time（パイプラインが空くのを待つ時間）/
  Stalls（パイプのどの部分からドレインする必要があるか）**。
- Stall 原因の例: **Data Dependencies**（前後 dispatch のデータ依存→キャッシュ無効化のためのバリア）、
  **Queue Profiling**（OpenCL 固有）。
- **HIP アプリのバリアは通常ごく短い**（inter-dispatch 依存はキャッシュ無効化しか生まないため）。

**AMD-Faster への含意**: **RGP で「バリアの実効コスト」を測れる**。
ベンチ手順書に「RGP の Barriers UI で Drain time を見よ」を書き込む。
ただし **OpenGL では RGP が使えない**（ユーザー指摘の通り）→ Vulkan 化の動機。

## 8. この章からの実装ルール（追加分）

1. **graphics queue 1本 / compute queue 1本 / transfer は PCIe 転送のみ**。
   複数 graphics queue は作らない（AMD で無意味）。
2. **fence ≤ 9 / frame**、signal を最小化。
3. **command buffer 1本 = 10 draw 以上、かつ 1〜2 ms 以内**。
4. **大 dispatch は分割**（プリエンプション不能区間を 2ms 未満に）。
5. **バリアは split 化 + 複数バリアの同時発行**、「全 A → 全 B → 全 C」に並べ替え。
6. **ベンチは RGP の Barriers UI（Drain time）＋ `MESA_VK_TRACE=rgp`**（Linux）。
