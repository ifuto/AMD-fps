# GCN (Polaris / Vega) and the APU line — explicitly required coverage

The brief calls out two things that are easy to forget when you only think
about discrete RX cards:

1. **GCN-generation hardware (Polaris = RX 400/500, Vega = RX Vega / Radeon VII)**
   is still on AMD's support matrix and still runs RADV/AMDVLK perfectly well.
2. **APUs** — *Vega 8 (Raven/Picasso), 680M (Rembrandt), 780M (Phoenix), and
   the Steam Deck (VanGogh)* — are not "slow dGPUs", they are a different
   memory topology and a different power envelope.

AMD-Faster must not regress on either.

## GCN specifics

| Property | GCN4 (Polaris) | GCN5 (Vega) |
|---|---|---|
| Wave size | wave64 (wave32 optional) | wave64 (wave32 optional) |
| LDS/workgroup | 64 KiB | 64 KiB |
| VGPRs/thread | 256 | 256 |
| Async compute | limited (graphics+compute can overlap on Vega) | good |
| Mesh shaders | **no** | **no** |
| RADV support | full | full |

Implications for the mod:

- **No mesh shaders on GCN.** The pipeline must have a classic VS/PS path.
  Our v0.1 pipeline *is* the classic path, so GCN works out of the box.
- **Prefer smaller indirect batches.** GCN's ACE/HS scheduler is less
  forgiving of very large `MULTI_DRAW_INDIRECT` lists than RDNA's. The
  `AmdTuning.maxIndirectDrawsPerCall` table caps GCN at 512, RDNA3/4 at 4096.
- **wave64 is fine for compute** on GCN, but graphics is wave32 on RDNA and
  wave64 on GCN — our shaders don't depend on wave size, so this is a
  non-issue, just logged.

## APU specifics — the important part

An APU has **no dedicated VRAM**. The GPU's memory is carved out of system
RAM, and the same physical pages are shared with:

- the **display engine** (scanout),
- the **OS / other processes**,
- the **CPU side of the game** (the JVM heap, the meshing threads).

Consequences:

1. **There is no "upload to VRAM" step.** CPU writes to a
   `DEVICE_LOCAL | HOST_VISIBLE` allocation land in the same memory the GPU
   reads. This is *good* for us — persistent mapping is essentially free —
   but it also means **CPU and GPU contend for the same memory bandwidth**.
2. **Budgets must be conservative.** Claiming 60% of "VRAM" on an APU means
   claiming 60% of the carve-out, which starves the display engine and the
   OS. `AmdTuning` caps APU direct-upload at `min(heap/2, 1.5 GiB)`.
3. **More frames in flight helps.** On a dGPU, 2 frames in flight is usually
   enough because the GPU has its own VRAM bandwidth. On an APU, a third
   frame hides the CPU→GPU upload latency while the GPU is busy with the
   previous frame. `AmdTuning` defaults APUs to **3 frames in flight**,
   dGPUs to 2.
4. **The display engine is a real competitor.** A 4K@60Hz display pulls
   ~500 MB/s of bandwidth just for scanout. Heavy fragment shaders on an APU
   directly eat into the FPS the player sees.

### The specific APUs named in the brief

| APU | GPU | Codename | Arch | Notes |
|---|---|---|---|---|
| Vega 8 | RX Vega 8 | Raven / Picasso | GCN5 | 2017–2020 Ryzen 2000/3000 mobile |
| Vega 11 | RX Vega 11 | Raven Ridge | GCN5 | desktop Ryzen 5 2400G |
| 680M | Radeon 680M | Rembrandt | RDNA2 | Ryzen 6000 mobile, very common |
| 780M | Radeon 780M | Phoenix | RDNA3 | Ryzen 7040/8040 mobile, **the most common AMD laptop GPU in 2024–2026** |
| Steam Deck APU | VanGogh | Aerith | RDNA2 | 8 CU, unified memory, **the best-selling AMD handheld** |

All five are detected by `AmdArchitecture.classify` (PCI id first, then
codename from the radeonsi renderer string, then marketing name).

### What the APU path changes in code

- `AmdTuning.derive` returns `apu = true` → smaller staging ring (32 MiB
  cap), 3 frames in flight, conservative direct-upload budget.
- `MemoryManager` allocates the section arenas from the
  `DEVICE_LOCAL | HOST_VISIBLE` heap when it exists (it always does on APUs)
  and persistently maps them — **zero-copy meshing**.
- The overlay's VRAM readout (`VK_EXT_memory_budget`) shows the APU
  carve-out usage so the user can see how close we are to the budget.

## Detection

`GpuProbe` collects:

- GL vendor/renderer strings (on Linux/radeonsi these embed the LLVM chip
  name, e.g. `AMD Radeon 780M (radeonsi, phoenix, ...)`),
- Vulkan PCI vendor/device id,
- `VkPhysicalDeviceDriverProperties` (driver name → RADV vs AMDVLK),
- `VkPhysicalDeviceSubgroupProperties` (wave size).

`AmdArchitecture.classify` turns that into one of: `GCN_POLARIS`,
`GCN_VEGA`, `APU_VEGA`, `RDNA1`, `RDNA2`, `RDNA3`, `RDNA4`, `APU_RDNA2`,
`APU_RDNA3`, `STEAM_DECK`, `UNKNOWN`, `NON_AMD`.
