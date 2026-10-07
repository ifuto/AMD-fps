# AMD-Faster

> An **AMD-first** rendering backend for Minecraft — a self-contained Vulkan
> engine with RDNA / GCN / APU-aware memory, queue and draw-call tuning.
> The red team's answer to Nvidium. 💪

- **Minecraft 1.21.11 · Fabric · client-side**
- **One jar.** `lwjgl-vulkan` is bundled jar-in-jar; the Vulkan loader comes
  from your OS / GPU driver. No manual installs — installs straight from the
  **Modrinth App**, Prism, MultiMC, or `mods/amdfaster.jar`.
- **AMD-first, not AMD-only.** Deep tuning for RDNA / RDNA2 / RDNA3 / RDNA4,
  GCN (Polaris / Vega), and the APU line — **Vega 8, 680M, 780M, Steam Deck**.
  Works on Intel / NVIDIA too, just with quieter tuning tables.

## What it does today (v0.1)

- A complete **Vulkan backend**: instance → device → swapchain → pipelines →
  frame sync, with AMD-specific memory policy (persistent-mapped
  Resizable-BAR / unified-memory uploads, conservative APU budgets).
- An **F6 diagnostics / benchmark overlay**: meshes a cube of sections
  around you with the vanilla model pipeline and draws them with the
  terrain shader, plus a frame-time graph and a VRAM/GPU readout.
- **GPU detection & tuning**: PCI id + radeonsi codename + Vulkan driver
  (RADV vs AMDVLK) + wave size → architecture enum → tuning table.

## Install

1. Install **Fabric Loader** for 1.21.11 (>= 0.16).
2. Drop `amdfaster-<version>.jar` into `mods/`.
3. Launch. Press **F6** in-game to open the Vulkan diagnostics overlay.

Config lives at `config/amdfaster.json` (created on first launch).

## Build

```bash
./gradlew build          # produces build/libs/amdfaster-<version>.jar
./tools/compile_shaders.sh   # regenerate SPIR-V (needs glslangValidator)
```

Requires JDK 21.

## Compatibility

| Mod | Stance |
|---|---|
| Sodium / Embeddium | **Pick one rendering path.** AMD-Faster does not mixin into Sodium; if both are installed they will fight over the frame. |
| Iris / Oculus | Same — pick one. |
| VulkanMod | Mutually exclusive (both replace the renderer). |
| Nvidium | Mutually exclusive (NVIDIA-only, Sodium-coupled). |
| Lithium, FerriteCore, Krypton, etc. | Fine — we only touch the frame lifecycle. |

## Research & design docs

The mod is built on a written research base. Start at
[`docs/research/00-index.md`](docs/research/00-index.md). Highlights:

- [RDNA ISA notes](docs/research/01-rdna-isa.md) — wave32, VGPR/LDS budgets, occupancy
- [GCN & APUs (Vega 8 / 680M / 780M / Steam Deck)](docs/research/02-gcn-and-apu.md)
- [GPUOpen performance guides, applied](docs/research/03-gpuopen-performance.md)
- [VMA & AMD memory topology (ReBAR / APU)](docs/research/04-vma-memory.md)
- [RADV / ACO / AMDVLK / PAL / LLPC](docs/research/05-drivers-radv-aco-amdvlk.md)
- [Why Vulkan and not "optimised OpenGL"](docs/research/06-opengl-amd-quirks.md)
- [Sodium's architecture](docs/research/07-sodium-architecture.md)
- [Nvidium & VulkanMod](docs/research/08-nvidium-and-vulkanmod.md)
- [JVM / Minecraft side](docs/research/09-jvm-and-minecraft.md)
- [RDTS / RGA / RGP / GPA toolchain](docs/research/10-toolchain-rdts-gpa.md)
- [Architecture](docs/design/architecture.md) · [Vertex format](docs/design/vertex-format.md)

## Roadmap

- **v0.2** — mesh-shader terrain (`VK_EXT_mesh_shader`, RDNA2+), BC7 atlas
- **v0.3** — GPU-driven culling (compute → indirect draw buffer)
- **v0.4** — compute translucency sorting (wave32 bitonic sort in LDS)
- **v0.5** — full world-render replacement (entities, sky, particles, GUI)

## License

LGPL-3.0-only. See [LICENSE](LICENSE). Ideas from Sodium (PolyForm Shield /
LGPL) and VulkanMod (LGPL-3.0) are documented in `docs/research/`; no code
is copied from either.
