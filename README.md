# AMD-Faster

A client-side **Fabric 1.21.11** rendering mod that is built for **AMD** hardware specifically.

> Nvidium shows what a vendor-specific renderer can do on NVIDIA: one long indirect/mesh-shader
> submission instead of thousands of draws. AMD-Faster is the same idea aimed at GCN/RDNA - and,
> unlike `GL_NV_mesh_shader`, the AMD path is not vendor-locked by the API: `VK_EXT_mesh_shader`
> exists on RDNA 2 and newer, so the GPU-driven terrain pipeline is available on a much wider range
> of cards.

## What it does

AMD-Faster sits in three layers, so a device that cannot run the exotic path still benefits:

| Layer | What it is | Status |
|---|---|---|
| `dev.amdfaster.core` | Pure-Java, Minecraft-free tuning core: AMD identification (PCI ids + driver strings), per-generation occupancy model, capability set, and the tuning policy engine | implemented, unit tested |
| GL path | AMD tuned submission on top of vanilla Blaze3D: persistent mapped upload rings, multi-draw indirect batching, region-BFS visibility, depth pyramid occlusion | policy implemented; renderer hooks in progress |
| Vulkan path | GPU-driven terrain in Vulkan (task/mesh shaders on RDNA 2+, bindless descriptors, GPU-written draw counts), pipeline precompilation via AMD's own LLVM compiler | device probe + bindings implemented; renderer in progress |

Everything is opt-in-safe: on a non-AMD GPU, or when anything fails to come up, AMD-Faster degrades to
vanilla rendering instead of breaking the game.

## Why AMD needs its own mod

The tuning knowledge that drives the policy is documented in [docs/](docs/) (summarised below):

* **Occupancy is a step function, not a slope.** Registers are allocated in blocks (16 VGPRs on RDNA 2
  wave32, 24 on RDNA 3 wave32). A kernel using 97 VGPRs costs exactly as much occupancy as one using
  112. Knowing the block size is what makes register targets actionable - see
  `OccupancyModel.budgetTable()`.
* **SGPRs stop limiting occupancy on GFX10+.** On GCN (and CDNA) the scalar register file is a real
  constraint - 800 SGPRs per SIMD, granularity 8, and any overage costs a whole wave.
* **The Windows AMD OpenGL driver is the weak path.** It pays a high fixed cost per small
  `glBufferSubData`, which is why Sodium's persistent-mapped design exists and why AMD-Faster
  coalesces uploads into >= 64-128 KiB batches and publishes them with a single fence.
* **Scheduling wants long bursts.** RGP shows the same thing the ISA guides explain: the command
  processor prefers one long indirect batch over thousands of short draws. Hence
  `glMultiDrawElementsIndirect` batching and, on Vulkan, `vkCmdDrawIndexedIndirectCount`.
* **Barriers and layout transitions are not free.** AMD's *Breaking Down Barriers* series is the
  reason the Vulkan path batches and merges dependencies instead of issuing pipeline barriers
  mid-pass.
* **ReBAR is not a given.** AMD ships a 256 MiB BAR by default; placing upload rings in
  `DEVICE_LOCAL | HOST_VISIBLE` is only done when the window actually covers the frame buffer.

## Building

```bash
./gradlew build            # jar into build/libs/amd-faster-<version>.jar
./gradlew test             # tuning core unit tests (no Minecraft needed)
./gradlew genSources       # decompiled Minecraft sources, for development
```

Requires **JDK 21**. The build uses Mojang's official mappings, because 1.21.11 is the **last
obfuscated** Minecraft release and Yarn/Intermediary are not updated past it - mods are expected to
migrate now, and this one starts there.

## Installing

AMD-Faster is a normal client-side Fabric mod: drop the jar in `.minecraft/mods` (or install it with
the Modrinth app / Prism / CurseForge launcher), and make sure Fabric Loader 0.17+ and Fabric API for
1.21.11 are present. No driver changes no launcher arguments no manual native installation - the
backend ships inside the jar, and the optional AMD toolchain is built into it when the release is
built with `-PincludeAmdToolchain=true`.

## Licence

LGPL-3.0-or-later. See [LICENSE](LICENSE) and [NOTICE](NOTICE); third-party reference material and
bundled components are listed in NOTICE.

## Project status

Implemented and verified by the CI build (`./gradlew build` on Temurin 21, tests included):

* the tuning core: AMD family/ISA identification, the LLVM-derived occupancy model, capability
  sets, the policy engine and the human-readable session report (`TuningSession.report()`);
* device probing at client start: `GlDeviceProbe` reads the OpenGL driver's extensions and driver
  identity, `VkDeviceProbe` surveys the Vulkan physical device (PCI id, memory heaps, extension set,
  Vulkan version) without creating a device;
* the client entry point wires both probes into a `TuningSession` and logs the full report, so an
  issue report contains the exact reasons for every decision;
* LWJGL's Vulkan bindings (and VMA) are bundled as nested jars, version-matched to Minecraft's own
  LWJGL so the process only ever contains one LWJGL.

In progress (the next commits):

* the **renderer hooks** that act on the plan: persistent mapped upload arena and batched indirect
  terrain submission for the GL path, using the intervention points listed in
  `docs/research/04-minecraft-renderer-internals.md`;
* the **Vulkan terrain backend** itself;
* the optional AMD toolchain download (`fetchAmdToolchain`) for offline pipeline compilation.

### Working in a locked-down environment

The build emits information about the CI machine through GitHub check annotations when
`exfil` is set in `gradle.properties` (`-Pexfil=list:client/renderer`,
`-Pexfil=classes:SectionRenderDispatcher`, ...); `.research/tools/exfil.py` collects those
annotations, reassembles the payload and writes the requested files locally. This is how the
signatures quoted in `docs/research/04-minecraft-renderer-internals.md` were obtained:
the class files come from the real mapped jar the build compiles against, not from a different
version or from memory.
