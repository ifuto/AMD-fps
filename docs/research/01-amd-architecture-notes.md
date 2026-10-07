# AMD architecture notes (what AMD-Faster actually relies on)

Scope: only the hardware facts the tuning core uses. Each fact is followed by where it was
confirmed.

> **Provenance.** `gpuopen.com` (where the RDNA/RDNA2/RDNA3/RDNA4 ISA Reference Guides, the RDNA
> Architecture Whitepaper and the GPUOpen performance guides live) is **not reachable from the
> development sandbox** used to write this mod, so those PDFs could not be downloaded and quoted
> line by line. To avoid shipping numbers taken from memory, every constant in
> `dev.amdfaster.core.arch` was cross-checked against two independent open implementations of the
> same hardware:
>
> * **LLVM's AMDGPU backend** (fetched from `github.com/llvm/llvm-project`, `main`):
>   `lib/Target/AMDGPU/AMDGPU.td` (`FeatureMaxWavesPerEU{8,10,16,20}`, `Feature1536VGPRs`),
>   `Utils/AMDGPUBaseInfo.cpp` (`getNumWavesPerEUWithNumVGPRs`, `getVGPRAllocGranule`,
>   `getOccupancyWithNumSGPRs`, `isSGPROccupancyLimited`, `getMaxNumSGPRs`, `getMinNumVGPRs`,
>   `getMaxNumVGPRs`), `GCNSubtarget.cpp` (`getBaseReservedNumSGPRs`), `SIRegisterInfo.cpp`.
> * **Mesa** (fetched from `github.com/Igalia/mesa`, `main`): `src/amd/common/ac_gpu_info.c`
>   (`lds_size_per_workgroup`, `num_simd_per_compute_unit`, chip handling),
>   `src/amd/common/amd_family.c/.h` (chip names).
>
> Anything that could only be sourced from the ISA guides themselves is marked
> **[guide-dependent]** and is a candidate for verification with Radeon GPU Analyzer (RGA), which
> reports VGPR/SGPR usage and occupancy for a compiled shader.
>
> These same documents are the ones a reader should consult for instruction level work; the notes
> below record only what the *tuning policy* depends on.

## 1. Execution model

| | GCN (GFX6-GFX9) | RDNA (GFX10+) |
|---|---|---|
| VALU width | 16 lanes (SIMD16) | 32 lanes (SIMD32) |
| Wave size | wave64 only | wave32 or wave64, selectable per pipeline |
| SIMD per CU | 4 | 2 (CU = 64 FP32 lanes; 2 CUs per WGP) |
| Workgroups per CU | 16 | 32 |
| LDS per CU | 64 KiB | 128 KiB |
| Scalar register file limits occupancy | yes | **no** |

The wave32/wave64 split is the single biggest difference to design around. It is not simply
"wave32 = faster": wave32 keeps both halves of a divergent branch busy and packs more waves into the
same register file, while wave64 halves the per-wave fixed cost and lets one scalar operation feed 64
lanes. AMD-Faster exposes the choice per pipeline
(`VK_EXT_subgroup_size_control`) and picks wave32 for culling/divergent work and wave64 for bulk
terrain shading.

Confirmations: `ac_gpu_info.c` sets `num_simd_per_compute_unit = 2` for GFX10+ and 4 otherwise, and
`lds_size_per_workgroup = 128 KiB` for chip class >= GFX10 (64 KiB before). `AMDGPU.td` gives the
per-generation maximum wave counts through `FeatureMaxWavesPerEU8/10/16/20`.

## 2. Register file and occupancy (the part that changes frame times)

The vector register file per SIMD, the allocation block size, and the resulting wave count are:

```
wavesPerSimd = clamp(TotalNumVGPRs / alignTo(NumVGPRs, Granule), 1, MaxWavesPerSimd)
maxNumVGPRs  = alignDown(TotalNumVGPRs / wavesPerSimd, Granule)
```

This is exactly LLVM's `getNumWavesPerEUWithNumVGPRs()`; AMD's own documentation describes the same
behaviour (registers are handed out in blocks, a wave gets a whole block or it does not run).

| Family | GFX | Max waves/SIMD | Register file (wave32 VGPRs) | VGPR block (w32/w64) |
|---|---|---|---|---|
| GCN 1-3 (Tahiti/Hawaii/Tonga/Fiji) | gfx6-gfx8 | 10 (wave64 slots) | 256 (wave64 view) | 4/4 |
| Polaris (GCN 4) | gfx8 | 10 | 256 | 4/4 |
| Vega (GCN 5) | gfx9 | 10 | 256 | 4/4 |
| RDNA 1 | gfx10 | 16 | 512 | 8/4 |
| RDNA 2 | gfx10.3 | 16 | 1024 | 16/8 |
| RDNA 3 | gfx11 | 16 | **1536** | 24/12 |
| RDNA 4 | gfx12 | 16 | 1536 (+dynamic re-allocation) | 24/12 |
| CDNA 2 | gfx90a | 8 | 512 | 8/8 |

`Feature1536VGPRs` in `AMDGPU.td` is what identifies the 1536 register parts (GFX11 ISA versions and
GFX12/13); `getVGPRAllocGranule()` in `AMDGPUBaseInfo.cpp` is the source of the 24/12 and 16/8
granularities. GCN's 10 waves per SIMD and the 4-VGPR block come from the GFX6-GFX9 guides and from
LLVM's `MaxWavesPerEU10`/default granule path.

### Why the block size is the actionable number

On RDNA 2 wave32 (block = 16, file = 1024):

| VGPRs used | waves/SIMD | occupancy |
|---|---|---|
| 64 | 16 | 100 % |
| 80 | 12 | 75 % |
| 96 | 10 | 62.5 % |
| 128 | 8 | 50 % |

Two conclusions that the mod encodes:

* **96 and 97 VGPRs cost the same.** `OccupancyModel.maxVgprsForNextWaveStep()` exists so a shader
  author is told "free 16 VGPRs (down to 80) and you gain a wave", rather than being told to shave
  one register.
* **Below one block the limit stops mattering.** A kernel using fewer VGPRs than the block size runs
  at the full wave slot limit; that is why a culling shader can afford to be wasteful with ALU but
  not with registers.

### Scalar registers

GCN/CDNA: 800 SGPRs per SIMD, allocation block 8, plus a small per-wave reserve
(`getBaseReservedNumSGPRs()`: 2 for VCC, 6 when flat scratch is live on GFX8/9). Any overage costs a
whole wave, so SGPR pressure is a first-class limiter there.
GFX10+: `isSGPROccupancyLimited()` returns false - the scalar file is big enough that SGPR use never
reduces occupancy, which is why AMD-Faster reports SGPR limiting only for GCN/CDNA.

### LDS

64 KiB per CU on GCN, 128 KiB from GFX10 on (`lds_size_per_workgroup` in `ac_gpu_info.c`). On GFX10+
a single workgroup may claim the whole CU-local LDS, so a 100 KiB workgroup means exactly one
resident workgroup per CU and hence 8 waves in a 256-thread configuration. That is modelled in
`OccupancyModel.wavesForLds()`.

### Wave slots and workgroups

The hardware caps resident workgroups (16 on GCN, 32 on RDNA), and a workgroup larger than the wave
slots of one SIMD is spread across SIMDs: a 1024-thread workgroup on RDNA needs 16 wave32 slots on
each of two SIMDs. `OccupancyModel.hardwareWorkgroupsPerCu()` and `wavesPerWorkgroup()` capture this,
and `Result.maxWorkgroupsPerCu` reports the resulting residency.

### VOPD and dynamic VGPRs

* **VOPD (RDNA 3+)**: two VALU operations can be co-issued in one cycle, which is how RDNA 3 reaches
  128 FP32 ops per CU per clock. It only pays off when both instructions are independent and both
  are simple VALU ops, which is why the plan prefers wave32 for the culling kernels where ILP is
  high but the work is small.
* **Dynamic VGPR allocation (GFX12)**: the register file can be re-partitioned between waves at
  runtime, so a heavy shader no longer forces every other wave on the CU to a smaller budget.
  `AMDGPU.td` models this through the dynamic VGPR block attributes; AMD-Faster enables
  `Technique.DYNAMIC_VGPR_ALLOCATION` on RDNA 4 and records it in the plan.

## 3. Memory: the reason APUs need different tuning

* Discrete cards: VRAM over PCIe. The **default BAR is 256 MiB**; Resizable BAR / Smart Access
  Memory raises the window to (most of) the frame buffer. Only with a large BAR does it make sense to
  allocate upload rings in `DEVICE_LOCAL | HOST_VISIBLE` and write to them directly.
* APUs (Vega 8/11, Radeon 660M/680M, 740M/760M/780M, 890M) share the DDR bus with the CPU. GTT
  allocations are host-visible by construction, so copy elimination is a small win while bandwidth
  reduction is a large one. That is why the plan shrinks the staging ring
  (`AmdTuner.stagingRingMiB()` -> system RAM / 128, capped at 128 MiB) and favours the compact vertex
  format on integrated parts.
* The kernel driver exposes the split as VRAM vs GTT: `amdgpu` documentation (kernel.org) describes
  GTT as the system-memory aperture and VRAM as the device-local heap. This maps onto VMA's
  `DEVICE_LOCAL` vs `HOST_VISIBLE` device memory types, which is the vocabulary AMD-Faster uses in
  the plan.

## 4. What AMD-Faster deliberately does *not* claim

* No instruction-level scheduling: the driver (LLPC on AMDVLK/Windows Vulkan, ACO on RADV,
  radeonsi+LLVM on Mesa OpenGL) owns that. The mod's role is to feed it better shaders, fewer
  barriers and better batches.
* No ISA emission. The Vulkan API does not accept ISA; the value of the bundled AMD compiler is
  *analysis* (VGPR/SGPR/occupancy/ISA inspection, pipeline precompilation), not code path
  replacement.
* No GDS or async-DMA copy tricks: they are not reachable from Vulkan/OpenGL and they would make the
  mod's behaviour depend on undocumented driver internals.
