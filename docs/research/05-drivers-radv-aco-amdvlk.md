# Drivers: RADV, ACO, AMDVLK, PAL, LLPC, amdgpu, AGS

Sources studied: Mesa 3D source (radeonsi, RADV, ACO), GPUOpen-Drivers
(AMDVLK, PAL, LLPC), amdgpu kernel docs (kernel.org), AGS.

## The two Vulkan drivers that matter on AMD

| Driver | Where | Shader compiler | Notes |
|---|---|---|---|
| **RADV** (Mesa) | Linux, default on every distro, **Steam Deck / SteamOS** | **ACO** | Open-source, fast shader compile, great Vulkan 1.3 support, exposes `VK_EXT_memory_budget`, `VK_AMD_*`, `VK_EXT_*`. |
| **AMDVLK** (GPUOpen) | Linux alternative, **Windows (in Adrenalin)** | LLPC + its own backend | Proprietary, sometimes faster in specific games, slower shader compile, different extension set. |
| **radeonsi** (Mesa) | Linux OpenGL | TGSI→ACO or LLVM | The GL driver. See `06-opengl-amd-quirks.md` for why we bypass it. |
| **Pro Windows GL** | Windows OpenGL | — | Not RGP-profileable, slow for Minecraft-style workloads. |

### How we tell them apart at runtime

`VkPhysicalDeviceDriverProperties.driverName` (Vulkan 1.2):

- RADV reports `driverName = "radv"`, `driverInfo = "Mesa 24.x"`
- AMDVLK reports `driverName = "AMDVLK"`, `driverInfo = "2024.Qx.x"`

We read this in `Device` and store it in `GpuProbe.vkDriverName`.
`GpuProbe.isRadv()` gates RADV-only knobs.

## ACO — the shader compiler we care about

ACO (AMD **C**ompiler **O**ption, Mesa's RADV shader compiler) is the
reason a Vulkan path on AMD is so much better than the GL path for
Minecraft:

- **Fast compile.** ACO compiles the terrain shader in single-digit
  milliseconds; the legacy LLVM path can take 100ms+. This is why the
  first world load stutters less.
- **Good VGPR allocation.** ACO's register allocator is significantly better
  than the GL path's for our shader shape.
- **Pipeline cache works.** `vkCreatePipelineCache` blobs are stable
  across runs on ACO — we persist ours to `config/amdfaster-pipeline-cache.bin`.

## Environment variables we document for users

These are the knobs a user (or our own diagnostics) can set. We do **not**
set them from inside the mod (they must be set before the JVM starts), but
we log them in the overlay's "driver" line so a bug report is self-contained.

| Variable | Effect |
|---|---|
| `RADV_PERFTEST=aco` | Force ACO (default on RDNA). |
| `RADV_PERFTEST=llvm` | Force the legacy LLVM compiler (for comparison). |
| `RADV_DEBUG=sync` | Synchronise every submit (debug). |
| `RADV_DEBUG=nocache` | Disable the driver pipeline cache (debug). |
| `AMD_VULKAN_ICD=RADV` / `AMDVLK` | Pick the ICD on Linux. |
| `MESA_VK_WSI_PRESENT_MODE=mailbox` | Force mailbox present. |
| `AMD_DEBUG=nodma` etc. | radeonsi/amdgpu debug flags. |

## drirc / driconf app profiles

Mesa reads per-app XML profiles (`/usr/share/drirc.d/*.conf`,
`~/.drirc`). This is how we can **ship recommended settings** alongside the
mod: a `.conf` snippet that sets `radeonsi`/`radv` options for the
Minecraft java process. v0.1 doesn't ship one (the mod is Vulkan-native so
most drirc options don't apply), but the docs mention it as the place to
put GL workarounds if we ever need them.

## amdgpu kernel driver

- Source of truth: kernel.org `amdgpu` documentation.
- Controls: GTT vs VRAM placement, power management (DPM), clock gating.
- Relevant to us: **VRAM eviction** under memory pressure. If the system
  is tight, the kernel evicts our `DEVICE_LOCAL` buffers to GTT (system
  RAM), which halves bandwidth. The overlay's memory-budget readout lets
  the user see when this is happening.

## AGS (AMD GPU Services)

AGS is a **DirectX-centric** library (DX12/DX11). For a Java/Vulkan mod it is
low priority — we list it for completeness and explicitly do **not** depend
on it. The Vulkan-native equivalents (VMA, the `VK_AMD_*` extensions) cover
everything AGS would give us.

## What this means for AMD-Faster

1. **Target RADV first.** It is the default on Linux and the only driver on
   Steam Deck. ACO + RADV is the fastest path for our shader shape.
2. **Support AMDVLK.** Same Vulkan 1.3 surface, slightly different
   extension availability. Our feature probing (`Device.deviceExtensions`)
   handles the differences.
3. **Persist the pipeline cache.** ACO benefits; the file lives next to the
   config.
4. **Log the driver.** Every overlay screenshot / bug report should say
   `radv Mesa 24.3` or `AMDVLK 2024.Q4` so we know which compiler to blame.
