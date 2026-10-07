# AMD hardware notes for AMD-Faster

Everything the tuning core (`dev.amdfaster.core`) assumes about AMD silicon, with the source for
each number. This file is the reference the code comments point at; if a number here changes, the
enum in `core/arch/AmdArch.java` has to change with it (and `core/occupancy/OccupancyModelTest`
covers the maths that consumes it).

> **Access note.** This project was developed in an environment with access to GitHub, PyPI and npm
> but *not* to gpuopen.com, where AMD's ISA reference guides and the RDNA whitepaper live. The
> primary documents therefore could not be quoted line by line. To avoid shipping numbers from
> memory, the register file model was cross-checked against two independent open implementations of
> the same hardware, both of which are in `.research/`:
>
> * **LLVM's AMDGPU backend** - `AMDGPU.td` (`FeatureMaxWavesPerEU{8,10,16,20}`,
>   `Feature1536VGPRs`), `Utils/AMDGPUBaseInfo.cpp` (`getNumWavesPerEUWithNumVGPRs`,
>   `getVGPRAllocGranule`, `getOccupancyWithNumSGPRs`, `isSGPROccupancyLimited`, `getMaxNumSGPRs`),
>   `GCNSubtarget.cpp` (`getBaseReservedNumSGPRs`). LLVM is the compiler behind LLPC and radeonsi's
>   old GLSL path, so its model *is* the model AMD's own tools use.
> * **Mesa** - `src/amd/common/ac_gpu_info.c` (`lds_size_per_workgroup`, `num_simd_per_compute_unit`)
>   and `src/amd/common/amd_family.c` (chip names).
>
> Anything that could only come from the guides themselves is flagged **[guide]** below; those are
> the values to re-check with **Radeon GPU Analyzer**, which reports VGPR/SGPR usage, the resulting
> occupancy and the compiled ISA for a specific target.

## 1. Execution model

| | GCN (gfx6 - gfx9) | RDNA (gfx10+) |
|---|---|---|
| VALU width | 16 lanes (SIMD16 x4) | 32 lanes (SIMD32 x2) |
| Wave size | wave64 only | wave32 or wave64, per pipeline |
| SIMDs per CU | 4 | 2 |
| Workgroups per CU | 16 | 32 |
| LDS per CU | 64 KiB | 128 KiB |
| Scalar registers limit occupancy | yes | no |

Sources: `ac_gpu_info.c` sets `num_simd_per_compute_unit = 2` for GFX10+ and 4 below, and
`lds_size_per_workgroup = 128 KiB` from GFX10 on (64 KiB before). `isSGPROccupancyLimited()` in
`AMDGPUBaseInfo.cpp` returns false from GFX10.

What this means for the renderer: on GCN a wave64 is the only option and the scalar file is a real
constraint; on RDNA the choice per pipeline is a tool ("wave32 where the control flow diverges,
wave64 where the work is bulk vertex processing"), and the scalar file can be ignored.

## 2. Occupancy

```
wavesPerSimd = clamp(registerFile / alignTo(vgprs, granule), 1, maxWavesPerSimd)
maxVgprs     = alignDown(registerFile / wavesPerSimd, granule)
```

This is LLVM's `getNumWavesPerEUWithNumVGPRs()` (`AMDGPUBaseInfo.cpp`).

| Family | GFX ip | max waves/SIMD | register file (VGPR/lane, wave32) | granule (w32/w64) | LDS |
|---|---|---|---|---|---|
| GCN 1-3 | gfx6, gfx7, gfx8 | 10 | 256 (wave64 view) | 4 / 4 | 64 KiB |
| GCN 4 Polaris | gfx8 | 10 | 256 | 4 / 4 | 64 KiB |
| GCN 5 Vega | gfx9 | 10 | 256 | 4 / 4 | 64 KiB |
| RDNA 1 | gfx10.1 | 20 | 512 | 8 / 4 | 128 KiB |
| RDNA 2 | gfx10.3 | 16 | 1024 | 16 / 8 | 128 KiB |
| RDNA 3 | gfx11 | 16 | 1536 | 24 / 12 | 128 KiB |
| RDNA 3.5 | gfx11.5 | 16 | 1536 | 24 / 12 | 128 KiB |
| RDNA 4 | gfx12 | 16 | 1536 (+ dynamic VGPR) | 24 / 12 | 128 KiB |
| CDNA 2 | gfx90a | 8 | 512 | 8 / 8 | 64 KiB |

`max waves/SIMD` values are LLVM's `FeatureMaxWavesPerEU*` assignments per ISA version (10 for
gfx6-gfx9, 20 for gfx10.1, 16 for gfx10.3 and newer, 8 for gfx90a). The register file sizes follow
from `Feature1536VGPRs` (gfx11 and newer) and `getVGPRAllocGranule()`; **[guide]** the per-generation
table itself should be re-read from the ISA guides for gfx12 specifically, since RDNA 4 changes the
allocation rules (dynamic VGPR).

### Why the granule is the useful number

On RDNA 2 wave32 (granule 16, file 1024):

| VGPRs | waves/SIMD | occupancy |
|---|---|---|
| 64 | 16 | 100 % |
| 80 | 12 | 75 % |
| 96 | 10 | 62.5 % |
| 128 | 8 | 50 % |

* 96 VGPRs and 97 VGPRs cost exactly the same: `OccupancyModel.vgprsToRelease()` therefore reports
  the number that gets the kernel to the next **wave**, not the next register.
* A kernel that uses fewer registers than the granule cannot be register limited at all.

### Scalar registers

GCN/CDNA: 800 SGPRs per SIMD, granule 8, plus a per-wave reserve (`getBaseReservedNumSGPRs`).
More than one block of 8 per wave loses a whole wave, so SGPR spills are expensive there. From GFX10
on this limit does not apply and the mod does not report it.

## 3. Features that change tuning decisions

* **VOPD (RDNA 3+)**: two VALU operations co-issue per cycle - this is where "128 FP32 ops per CU
  and clock" comes from. It pays only when the two halves are independent simple VALU ops, so it is
  a scheduling property, not something the API can request. AMD-Faster acknowledges it rather than
  pretending to control it: the win comes from keeping shaders short and ILP high.
* **Dynamic VGPR allocation (gfx12)**: the register file is re-partitioned between waves at runtime,
  so a heavy shader no longer forces every other wave down a step. LLVM models it with a dynamic
  VGPR block size; AMD-Faster enables the corresponding technique on RDNA 4 only.
* **Mesh shaders**: from gfx10.3 (RDNA 2) on. RDNA 1 has none, period - no amount of driver support
  creates it.
* **Async compute queues**: the hardware exposes independent compute queues from gfx8 on; the value
  for this mod is running the visibility pass overlapped with the previous frame's translucent pass.
* **ReBAR / Smart Access Memory**: the default BAR is 256 MiB. With ReBAR the window covers the
  frame buffer, and only then is a `DEVICE_LOCAL | HOST_VISIBLE` heap worth allocating on a discrete
  card. On APUs the same memory is host visible by construction, so the interesting lever there is
  bandwidth, not copies.

## 4. What AMD-Faster deliberately does not do

* It does not emit ISA or schedule instructions: Vulkan and OpenGL do not accept ISA, and the
  driver's compiler (LLPC/PAL on Windows and AMDVLK, ACO on RADV, radeonsi's LLVM/ACO path on Mesa)
  owns that stage. The bundled AMD compiler is used for *analysis* (VGPR/SGPR/occupancy/ISA
  inspection, offline pipeline precompilation), never as a code path.
* It does not depend on GDS or async DMA: not reachable from either API, and it would make behaviour
  depend on undocumented driver internals.
