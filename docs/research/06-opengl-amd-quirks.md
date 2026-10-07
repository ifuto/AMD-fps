# OpenGL on AMD — why we left it (the "why Vulkan" document)

Sources studied: OpenGL 4.6 Core Spec, GLSL 4.60 Spec, the Khronos
`GL_AMD_*` / `GL_ARB_*` extension registry entries, and AMD's driver
behaviour notes.

## The extensions we *would* have used

| Extension | Purpose | AMD status |
|---|---|---|
| `GL_ARB_buffer_storage` | Persistent-mapped buffers (`GL_MAP_PERSISTENT_BIT \| GL_MAP_COHERENT_BIT`) | Supported. **This is the big one** — Sodium's whole design rests on it. |
| `GL_ARB_multi_draw_indirect` | One draw call, many indirect draws | Supported. |
| `GL_ARB_shader_draw_parameters` | `gl_DrawID` in the shader | Supported. |
| `GL_ARB_bindless_texture` | Bindless texture handles | Supported (64-bit handles). |
| `GL_AMD_pinned_memory` | Page-locked host memory for uploads | Supported (deprecated in favour of buffer_storage). |

So the *API surface* is there. The problem is the **driver**.

## Where AMD's GL driver loses to a Vulkan path

This is the part the brief asked us to understand by reading Sodium's design:

1. **Frequent small `glBufferSubData` calls are slow.**
   Minecraft's vanilla renderer (and many mods) update buffers with many
   small sub-uploads per frame. On AMD's GL driver each one can trigger a
   copy into a driver-internal staging area. Sodium's fix — persistent
   mapped `ARB_buffer_storage` + one big buffer per region — is exactly the
   workaround. **A Vulkan backend with persistent mapping gets the same win
   without depending on GL driver internals.**

2. **Uniform updates are expensive.**
   `glUniform*` / uniform buffer sub-updates on AMD GL go through a
   validated path. Vulkan push constants and `vkCmdUpdateBuffer` are cheaper
   and predictable.

3. **Shader program switching has a cost.**
   GL has a global "current program"; switching between the dozens of
   vanilla programs (block, entity, sky, particle, …) per frame is a
   driver-side pipeline rebind. Vulkan pipelines are objects you bind
   explicitly and the driver can pre-compile them; with the pipeline cache
   the cost is near-zero after the first frame.

4. **RGP does not profile OpenGL.**
   The brief notes this explicitly: *Radeon GPU Profiler supports DX12,
   Vulkan and OpenCL — **not OpenGL**.* If we stay on GL we cannot use the
   primary AMD profiling tool. Vulkan unlocks RGP, RGA, RMV and the whole
   RDTS suite.

5. **State validation overhead.**
   GL is a giant state machine with implicit dependencies; the driver has
   to validate and serialise a lot. Vulkan makes dependencies explicit
   (render passes / dynamic rendering, barriers, pipeline barriers), which
   lets RADV/ACO schedule work aggressively.

## The Sodium lesson, restated

Sodium's `persistent-mapped + indirect draw` design exists *because* of the
AMD/NVIDIA GL driver quirks above. We adopt the same design but on Vulkan,
which removes the GL-specific fragility (driver heuristics, state machine,
program switching) while keeping the win (persistent mapping, batched
indirect draws).

## What we keep from the GL world

- The **atlas** (a single big texture atlas for blocks) — same idea, now a
  `VK_IMAGE_2D` + `VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER`.
- The **lightmap** concept — but in v0.1 we compute the light response
  analytically in the fragment shader instead of sampling the 16×16
  lightmap texture (one less texture fetch; see `terrain.frag`).
- The **vertex format philosophy** — pack everything into one tight vertex,
  no per-vertex attributes we don't need.
