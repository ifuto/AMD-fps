package net.amdfaster.render;

import net.amdfaster.mesh.Meshlet;
import net.amdfaster.platform.AmdArchitecture;

/**
 * Decides whether a mesh-shader backend would beat the compute-cull-plus-indirect-draw one, and on the
 * hardware this mod targets the answer is usually no.
 *
 * <p>That conclusion is worth stating plainly because it is the opposite of what the feature's name
 * suggests. Mesh shaders are the newer path and the one that gets the demos, but three separate
 * measurements say they lose on AMD for a workload shaped like Minecraft's.
 *
 * <p>First, a task shader on AMD is not a distinct hardware stage. It compiles to a plain compute shader
 * whose payload is written to a VRAM ring buffer that the command processor feeds the mesh dispatch from.
 * That ring is fixed size and does not scale with the payload count, so when it fills the pipeline stalls
 * -- and the cliff is large enough that not using a task shader at all was measured as significantly faster
 * than a 32K item configuration on a 7900 GRE, where manual compute culling won outright. Minecraft at a
 * twelve chunk render distance has 13,824 sections, which is the regime the cliff lives in.
 *
 * <p>Second, a direct comparison on AMD found multi-draw indirect faster than mesh shaders once the
 * primitive count passed 128, and reported mesh shader configurations reducing effective hardware
 * capability threefold. A voxel renderer is never below 128 primitives.
 *
 * <p>Third, the vendor's own best-practice list for the API says not to mix task and mesh shaders with
 * compute culling -- which is precisely what this mod already has. Adding a task shader on top of the
 * existing cull pass would be doing the same culling twice, in two places, with the second one paying the
 * ring buffer for the privilege.
 *
 * <p>So what is the mesh path actually for here. Per-triangle culling. The indirect draw path's floor is
 * one meshlet: the cull shader either emits a meshlet's draw command or it does not, and 62 quads is the
 * granularity. A mesh shader can reject individual primitives inside a meshlet, which matters for geometry
 * with a lot of interior facing surfaces -- and voxel geometry has exactly that, since a merged run of
 * faces still contains quads that face away. That is the case where the mesh path earns its cost, and the
 * selector looks for it rather than assuming the newer stage is the better one.
 *
 * <p>One more thing the selector refuses: doing per-meshlet culling inside a mesh shader. That runs the
 * cull at the wrong rate -- once per threadgroup instead of once per batch -- and the vendor guidance
 * names it explicitly. If the culling is per meshlet it belongs in the compute pass that already does it.
 *
 * @see <a href="https://timur.hu/blog/2022/how-task-shaders-are-implemented">Task shader driver
 *      implementation on AMD HW -- a task shader compiles to a compute shader, the payload lives in VRAM,
 *      and it dispatches on an async compute queue alongside the mesh work</a>
 * @see <a href="https://github.com/zeux/niagara/discussions/41">measured on a 7900 GRE -- not using task
 *      shaders is significantly faster than a 32K item configuration; the ring does not scale with the
 *      payload count and the cliff is too large for task shader culling to be practical</a>
 * @see <a href="https://tellusim.com/mesh-shader/">Mesh Shader versus MultiDrawIndirect -- on AMD, mesh
 *      shader configurations reduce hardware capability threefold and MultiDrawIndirect is faster above
 *      128 primitives</a>
 * @see <a href="https://vulkan.org/user/pages/09.events/vulkanised-2023/vulkanised_mesh_best_practices_2023.02.09-1.pdf">
 *      Vulkanised 2023 mesh shader best practices -- do not mix task and mesh shaders with compute
 *      culling, do not do per-meshlet culling in a mesh shader, do not overuse the task payload</a>
 * @see <a href="https://gpuopen.com/learn/mesh_shaders/mesh_shaders-optimization_and_best_practices/">AMD
 *      GPUOpen -- one amplification thread group should launch at least 32 mesh thread groups, and process
 *      32 to 64 meshlets per group to hide the added latency</a>
 */
public final class MeshShaderBackendSelector {

    /** Triangles in one meshlet: 62 quads of two triangles. */
    public static final int TRIANGLES_PER_MESHLET = Meshlet.MAX_QUADS * 2;

    /** Vertices in one meshlet. Above the 64 to 128 hardware guidance, which quad geometry requires. */
    public static final int VERTICES_PER_MESHLET = 248;

    /**
     * The primitive count above which multi-draw indirect measured faster than mesh shaders on AMD.
     *
     * <p>A voxel renderer is never below it. A single section is 1,536 quads, so even one section's worth
     * of geometry is twelve times past the crossover.
     */
    public static final int MDI_CROSSOVER_PRIMITIVES = 128;

    /** The smallest amplification group AMD recommends, in meshlets. */
    public static final int MESHLETS_PER_AMP_GROUP = 32;

    /**
     * Meshlets above which the task payload ring is measured to cost more than it saves.
     *
     * <p>Conservative against the reported figure: a 32K item task configuration was already losing to
     * manual compute culling on a 7900 GRE, and the ring does not scale with the count. Setting the
     * threshold at 32K rather than at the measured failure point would mean recommending a configuration
     * that was measured to lose.
     */
    public static final int TASK_RING_SAFE_MESHLETS = 1024;

    private MeshShaderBackendSelector() {
    }

    /** Which backend to render through. */
    public enum Backend {
        /** The existing compute cull writing dense draw commands, drawn with an indirect count. */
        COMPUTE_CULL_MDI,
        /** Mesh shaders with no task stage, so no ring buffer and no amplification. */
        MESH_SHADER,
        /** Mesh shaders driven by a task shader, which on AMD means a compute shader and a VRAM ring. */
        MESH_SHADER_WITH_TASK
    }

    /**
     * What the workload looks like.
     *
     * @param meshletCount        meshlets in the frame's draw set
     * @param perTriangleCulling  whether primitives inside a meshlet need rejecting, which is the one thing
     *                            the indirect path cannot do
     * @param computeCullAlready  whether a compute cull pass is already in place. Mixing a task shader
     *                            with one does the same culling twice.
     * @param meshShaderAvailable whether {@code VK_EXT_mesh_shader} is exposed
     * @param architecture        the generation
     */
    public record Inputs(int meshletCount, boolean perTriangleCulling, boolean computeCullAlready,
                         boolean meshShaderAvailable, AmdArchitecture architecture) {
    }

    /** The chosen backend and the measurement or constraint that decided it. */
    public record Decision(Backend backend, String reason) {
    }

    /**
     * Chooses the backend.
     *
     * <p>The order matters. Availability and generation come first because nothing else can be decided
     * without them. Then the existing compute cull, because mixing is doing the work twice. Then the
     * primitive count, because below the crossover there is nothing for the indirect path to win with. And
     * only then whether per-triangle culling is actually wanted, since that is the sole thing that pays
     * for the mesh stage.
     */
    public static Decision decide(Inputs in) {
        require(in != null, "inputs are needed");
        require(in.meshletCount() >= 0, "a meshlet count must not be negative");
        require(in.architecture() != null, "an architecture is needed");

        if (!in.meshShaderAvailable()) {
            return new Decision(Backend.COMPUTE_CULL_MDI,
                    "VK_EXT_mesh_shader is not exposed; the compute cull and indirect draw stand on their own");
        }
        if (!in.architecture().isRdna() || in.architecture() == AmdArchitecture.RDNA1) {
            return new Decision(Backend.COMPUTE_CULL_MDI,
                    in.architecture().displayName() + " has no mesh shader hardware; keeping the indirect path");
        }
        if (in.computeCullAlready() && !in.perTriangleCulling()) {
            return new Decision(Backend.COMPUTE_CULL_MDI,
                    "a compute cull is already running and nothing needs per-triangle rejection, so a task "
                            + "shader would cull the same meshlets a second time and pay the ring buffer for it");
        }
        long primitives = (long) in.meshletCount() * TRIANGLES_PER_MESHLET;
        if (!in.perTriangleCulling() && primitives > MDI_CROSSOVER_PRIMITIVES) {
            return new Decision(Backend.COMPUTE_CULL_MDI,
                    primitives + " primitives is past the " + MDI_CROSSOVER_PRIMITIVES
                            + " where multi-draw indirect measured faster than mesh shaders on AMD");
        }
        if (in.perTriangleCulling() && !in.computeCullAlready()) {
            return new Decision(Backend.MESH_SHADER,
                    "per-triangle rejection is wanted and no compute cull is in place, so the mesh stage "
                            + "does it without a second pass; no task stage, because on AMD that is a "
                            + "compute shader feeding a fixed VRAM ring");
        }
        if (in.perTriangleCulling()) {
            // Per-triangle rejection is wanted but a compute cull is already in place, and the vendor
            // guidance is explicit about not mixing the two. Keeping the existing pass costs the
            // per-triangle rejection; adding a mesh stage on top would cull every meshlet twice and the
            // second one would pay the ring buffer. Neither is free, so say which was given up.
            return new Decision(Backend.COMPUTE_CULL_MDI,
                    "per-triangle rejection is wanted but a compute cull already runs, and mixing a task "
                            + "or mesh stage with it culls every meshlet twice; the per-triangle pass is "
                            + "given up rather than duplicating the meshlet one");
        }
        return new Decision(Backend.COMPUTE_CULL_MDI,
                "the culling wanted is per meshlet, which is the rate the existing compute pass already "
                        + "runs at; the vendor guidance says not to move it into a mesh shader");
    }

    /**
     * Whether a task stage is viable at all for this many meshlets.
     *
     * <p>Checked separately from {@link #decide} because it is a hardware constraint rather than a
     * preference, and a caller that has its own reason to want amplification should be able to ask.
     */
    public static boolean taskStageViable(int meshletCount, int payloadBytesPerMeshlet) {
        require(meshletCount >= 0, "a meshlet count must not be negative");
        require(payloadBytesPerMeshlet >= 0, "a payload size must not be negative");
        // The payload is written to VRAM by the task shader and read back by the mesh shader, so both the
        // count and the per-meshlet size matter -- a small count with a large payload fills the same ring.
        return meshletCount <= TASK_RING_SAFE_MESHLETS && payloadBytesPerMeshlet <= 32;
    }

    /**
     * Amplification groups for a meshlet count, at the size AMD recommends.
     *
     * <p>Each group should launch at least 32 mesh thread groups to hide the latency the task stage adds.
     * Fewer than that per group means more commands back to the command processor, which is the same ring
     * that makes the task stage expensive in the first place.
     */
    public static int amplificationGroups(int meshletCount) {
        require(meshletCount >= 0, "a meshlet count must not be negative");
        return (meshletCount + MESHLETS_PER_AMP_GROUP - 1) / MESHLETS_PER_AMP_GROUP;
    }

    /**
     * Whether a meshlet's shape is within what the mesh stage exports well.
     *
     * <p>248 vertices is above the 64 to 128 the hardware guidance prefers, and quad geometry requires it:
     * each corner of a block face carries its own ambient occlusion and light, so the four vertices of a
     * quad cannot be shared the way a smooth mesh's can. Worth reporting rather than hiding, because it is
     * the reason the mesh path's win here would be smaller than a demo's.
     */
    public static boolean meshletWithinGuidance() {
        return TRIANGLES_PER_MESHLET <= 126 && VERTICES_PER_MESHLET <= 128;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
