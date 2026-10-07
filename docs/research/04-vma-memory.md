# Vulkan Memory Allocator (VMA) and AMD memory topology

Source: the VMA documentation (VMA is an **AMD** library —
GPUOpen-Drivers/VulkanMemoryAllocator), plus the Vulkan 1.3 spec memory
chapter.

## Why VMA's model is the right model

VMA's core idea: **the driver exposes memory *heaps* and memory *types*
(heap + property flags), and the allocator picks the type that matches the
buffer's usage.** The single most important distinction for us:

```
DEVICE_LOCAL                 -> fastest GPU access, CPU can't write directly
DEVICE_LOCAL | HOST_VISIBLE  -> GPU-local but CPU-mappable (ReBAR / APU)
HOST_VISIBLE | HOST_COHERENT -> CPU write, GPU read (staging / UBOs)
```

## AMD-specific topology

### Resizable BAR / SAM

AMD boards (and Intel/NVIDIA since ~2020) support **Resizable BAR**
(PCIe "SAM" on AMD). When enabled in the BIOS:

- the GPU exposes a single large `DEVICE_LOCAL | HOST_VISIBLE` heap
  (often the full VRAM size, e.g. 8–16 GiB on an RX 7900 XTX),
- the CPU can map all of it,
- **CPU writes go straight into VRAM** — no staging copy, no
  `vkCmdCopyBuffer`.

This is the single biggest AMD upload win and it is *free* — we just have
to ask for the right memory type. `MemoryManager` does exactly that:

```java
int rebar = device.findMemoryType(0xFFFFFFFF,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
if (device.tuning.directUploadMemory && rebar != -1) {
    // allocate section arenas here, persistently map, write directly
}
```

`AmdTuning.directUploadMemory` is true when the largest
`DEVICE_LOCAL | HOST_VISIBLE` heap is >= 512 MiB (or any size on an APU).

### The legacy 256 MiB BAR

Without ReBAR, AMD exposes a small `DEVICE_LOCAL | HOST_VISIBLE` heap
(often 256 MiB) for the "BAR" plus a big `DEVICE_LOCAL`-only heap for VRAM.
VMA handles this by staging: allocate in `DEVICE_LOCAL`, copy from a
`HOST_VISIBLE` staging buffer. Our `MemoryManager.uploadNow` does the same
when the ReBAR heap is absent or exhausted.

### APUs

APUs have **no VRAM and no BAR split** — there is one big
`DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT` heap (the system RAM carve-out).
Persistent mapping is the only sensible strategy. See
`02-gcn-and-apu.md`.

## What we do NOT use VMA for

We deliberately do **not** depend on the VMA native library. v0.1's buffer
count is low (one pair per region + a handful of globals), so one
`VkDeviceMemory` per `VkBuffer` is fine and keeps the mod a single jar with
no native dependency. The *policy* (type selection, budget tracking,
persistent mapping) is what we took from VMA; the allocator itself is ~80
lines in `MemoryManager`.

If buffer count grows (per-section buffers, texture arrays), we will either
grow `MemoryManager` into a simple sub-allocator or vendor VMA — the
`MemoryManager` interface is shaped so that swap is localised.

## Budget tracking

`AmdTuning.directUploadBudget` caps how much of the ReBAR heap we claim:

- dGPU: `min(VRAM, rebarHeap) * 0.6`
- APU: `min(rebarHeap / 2, 1.5 GiB)`

`MemoryManager.directUploadUsed` tracks the running total; once the budget is
exhausted, `DEVICE_FAST` falls back to plain `DEVICE_LOCAL` + staging.

## `VK_EXT_memory_budget`

When the extension is present (RADV and AMDVLK both expose it), we enable
it and the overlay can show live VRAM usage. This is the AMD-specific
counterpart of NVIDIA's `NVX_gpu_memory_info` / `VK_NV_device_diagnostic_checkpoints`
style readouts.
