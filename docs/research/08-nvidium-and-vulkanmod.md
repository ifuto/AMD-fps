# Nvidium and VulkanMod — the two reference points

## Nvidium (MCRcortex/nvidium)

**What it is:** a rendering backend for Sodium, NVIDIA-only. Replaces
Sodium's terrain rasterizer with:

- **GPU-driven terrain**: compute shaders do frustum culling, occlusion and
  command-buffer building on the GPU (`VK_NV_command_buffer` /
  `GL_NV_command_list` style, ported to GL via NV extensions).
- **Mesh shaders** for terrain (`VK_EXT_mesh_shader` /
  `GL_NV_mesh_shader`): sections become task/mesh shader dispatches, the
  vertex pass disappears.
- **Persistent sparse buffers** (`GL_NV_shader_buffer_load` +
  `GL_NV_vertex_buffer_unified_memory`) for zero-copy vertex data.
- **Compute-based translucency sorting** (bitonic sort networks in shared
  memory).
- **Temporal reprojection** of the terrain to hide chunk-build latency.

**Why it's NVIDIA-only:** every one of those features is an `NV` extension.
The AMD equivalents exist but are different extensions (`VK_EXT_mesh_shader`
is cross-vendor; sparse residency is `VK_EXT_pageable_device_memory`-adjacent
on RADV; command-buffer building is plain `vkCmdDrawIndexedIndirectCount`).

**What AMD-Faster takes from Nvidium:**

- The *goal*: make AMD hardware render Minecraft as fast as Nvidium makes
  NVIDIA hardware render it.
- The *techniques*, ported to AMD extensions:
  - mesh-shader terrain → `VK_EXT_mesh_shader` (v0.2, RDNA2+),
  - GPU-driven culling → compute shader + `vkCmdDrawIndexedIndirectCount`
    (v0.3),
  - compute translucency sorting → wave32-aware bitonic sort in LDS (v0.4),
  - persistent mapped buffers → already in v0.1.
- The *diagnostics overlay* idea — Nvidium ships stats; we ship a benchmark
  overlay (F6) so the user can see the win themselves.

**What we explicitly do NOT copy:** Nvidium is LGPL and Sodium-coupled
(mixins into Sodium internals). AMD-Faster is original code, LGPL-3.0, and
does not mixin into Sodium — it can run alongside it or replace the vanilla
renderer.

## VulkanMod (xCollateral/VulkanMod)

**What it is:** a *complete* Vulkan reimplementation of Minecraft's renderer
— the GL renderer is replaced wholesale, every pass (terrain, entities, sky,
particles, GUI) is reimplemented on Vulkan with its own VMA-based memory
manager, descriptor system, and shader set. LGPL-3.0.

**What it teaches us (we studied its source):**

- **Backend lifecycle.** VulkanMod hooks `Minecraft.runTick` (HEAD) for
  `beginFrame`, `RenderSystem.flipFrame` (redirect `glfwSwapBuffers`) for
  `endFrame`, and `Minecraft.close` for shutdown. Our `Backend` follows the
  same three hooks (see `mixin/MinecraftMixin.java`,
  `mixin/RenderSystemMixin.java`).
- **Instance/device creation.** GLFW's
  `glfwGetRequiredInstanceExtensions` +
  `glfwCreateWindowSurface` (we do the same in `vk/Instance.java`).
- **Memory.** VulkanMod uses VMA; we use a slim policy layer (`MemoryManager`)
  — see `04-vma-memory.md` for why.
- **LWJGL Vulkan bindings.** VulkanMod bundles `lwjgl-vulkan` 3.3.3; we do
  the same so the Modrinth App install is one jar.
- **Present mode.** VulkanMod picks mailbox when available; so does our
  `Swapchain`.
- **Frame sync.** VulkanMod uses per-frame fences; we use a single timeline
  semaphore (`FrameSync`) — fewer objects, same effect, and timeline
  semaphores are the AMD-recommended primitive.

**Why we are not just "VulkanMod but AMD":**

- VulkanMod is a *full* renderer replacement — huge surface, slow to port
  to a new MC version. AMD-Faster v0.1 is a **backend + overlay**: smaller,
  installable, demonstrable, and it leaves room to grow into a full
  renderer (the roadmap) without breaking users on day one.
- VulkanMod targets GL→Vulkan *equivalence*; AMD-Faster targets
  *AMD-first performance* — the tuning tables (`AmdTuning`), the APU path,
  and the AMD-specific extensions are what make it different.

## Compatibility stance

- **With Sodium:** AMD-Faster does not mixin into Sodium. If both are
  installed, Sodium's GL renderer and our Vulkan backend would conflict —
  the README tells users to pick one. (A future "Sodium backend" mode,
  like Nvidium, is on the roadmap.)
- **With Iris:** same — pick one rendering path.
- **With VulkanMod:** mutually exclusive (both replace the renderer).
- **With everything else (Lithium, FerriteCore, etc.):** fine, we only
  touch the frame lifecycle.
