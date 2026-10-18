package net.amdfaster.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses the rendering path from the extensions a device actually exposes, and says which one it chose
 * and why.
 *
 * <p>Detecting the features and scoring the devices were both already done -- {@link GpuReport} probes the
 * instance and records which of the extensions of interest are present and which are missing, and
 * {@link net.amdfaster.vk.DeviceScorer} ranks the candidates. What was missing is the step that turns
 * "this device has {@code VK_EXT_mesh_shader}" into "so the mesh path runs instead of the compute cull",
 * and without it every capability the probe found had to be acted on by hand at each call site, which
 * means each call site has its own idea of what the fallback is.
 *
 * <p>The shape of the decision is a preference order with a fallback that always works, not a feature
 * check that throws. Every path here has a version that runs on a device with none of the interesting
 * extensions, because a mod that refuses to start on an old iGPU is worse than one that runs slowly on it.
 * The recorded reason matters as much as the choice: a user reporting low frame rates needs to be able to
 * see that the mesh path was skipped for a missing extension rather than guessing which of a dozen
 * conditions it was.
 *
 * <p>One thing this is careful about is that an exposed extension is not a promise the hardware is good at
 * it. A driver can expose {@code VK_EXT_mesh_shader} and the implementation can be slower than the compute
 * path it replaces, so the generation is weighed alongside the extension rather than the extension alone
 * deciding. That is the same reasoning that keeps the mesh-shader flag in {@link VendorProfile.Shape} from
 * being trusted directly.
 */
public final class FeaturePaths {

    /** The extension that makes the mesh-shader path possible at all. */
    public static final String EXT_MESH_SHADER = "VK_EXT_mesh_shader";

    /** Indirect draws with a device-written count, which the multi-draw path needs. */
    public static final String EXT_MULTI_DRAW = "VK_EXT_multi_draw";

    /** Subgroup size selection, which is what lets a wave size be requested rather than assumed. */
    public static final String EXT_SUBGROUP_SIZE_CONTROL = "VK_EXT_subgroup_size_control";

    /** Descriptor buffers, which remove the per-draw descriptor set updates. */
    public static final String EXT_DESCRIPTOR_BUFFER = "VK_EXT_descriptor_buffer";

    /** Draw without a render pass, which removes a pass transition per frame. */
    public static final String EXT_DYNAMIC_RENDERING = "VK_KHR_dynamic_rendering";

    /** Reversed-Z, which the depth buffer format and the projection both assume. */
    public static final String EXT_DEPTH_CLIP_CONTROL = "VK_EXT_depth_clip_control";

    private FeaturePaths() {
    }

    /**
     * A chosen path and the reason it was chosen.
     *
     * @param enabled whether the accelerated path runs
     * @param reason  a sentence a user can act on, naming the missing extension when there is one
     */
    public record Choice(String path, boolean enabled, String reason) {
    }

    /** Every path decision for one device. */
    public record Selection(List<Choice> choices) {

        /** How many accelerated paths this device gets. */
        public int enabledCount() {
            int count = 0;
            for (Choice choice : this.choices) {
                if (choice.enabled()) {
                    count++;
                }
            }
            return count;
        }

        /** One path's choice, or null when no such path was decided. */
        public Choice path(String path) {
            for (Choice choice : this.choices) {
                if (choice.path().equals(path)) {
                    return choice;
                }
            }
            return null;
        }

        /** The paths that were skipped, which is what a performance report should lead with. */
        public List<String> skipped() {
            List<String> out = new ArrayList<>();
            for (Choice choice : this.choices) {
                if (!choice.enabled()) {
                    out.add(choice.path());
                }
            }
            return out;
        }
    }

    /**
     * Whether an extension is present.
     *
     * <p>Checked against the present list rather than the missing one because a probe that failed part way
     * can leave both incomplete, and treating an unrecorded extension as present would enable a path the
     * device cannot run.
     */
    public static boolean has(GpuInfo device, String extension) {
        require(device != null, "a device is needed");
        require(extension != null, "an extension name is needed");
        List<String> present = device.extensionsPresent();
        return present != null && present.contains(extension);
    }

    /**
     * Decides every path for a device.
     *
     * <p>The generation is weighed alongside the extension where the two can disagree. Mesh shaders are
     * the case in point: the extension being exposed says the API is there, and RDNA2 being the first
     * generation with the hardware says whether it is worth using.
     */
    public static Selection select(GpuInfo device) {
        require(device != null, "a device is needed");
        AmdArchitecture architecture = device.architecture();
        List<Choice> choices = new ArrayList<>(6);

        boolean mesh = has(device, EXT_MESH_SHADER);
        boolean meshGeneration = architecture != null && architecture.isRdna()
                && architecture != AmdArchitecture.RDNA1;
        if (mesh && meshGeneration) {
            choices.add(new Choice("mesh", true, "VK_EXT_mesh_shader present and the generation has the hardware"));
        } else if (mesh) {
            choices.add(new Choice("mesh", false,
                    "VK_EXT_mesh_shader is exposed but " + name(architecture)
                            + " is not a generation the mesh path is faster on; using the compute cull"));
        } else {
            choices.add(new Choice("mesh", false,
                    "VK_EXT_mesh_shader missing; using the compute cull"));
        }

        choices.add(decide(device, "multiDraw", EXT_MULTI_DRAW,
                "indirect draws with a device-written count",
                "drawing each meshlet separately"));
        choices.add(decide(device, "subgroupSizeControl", EXT_SUBGROUP_SIZE_CONTROL,
                "requesting the wave size the shader was planned for",
                "dispatching at the driver's default subgroup size"));
        choices.add(decide(device, "descriptorBuffer", EXT_DESCRIPTOR_BUFFER,
                "descriptor buffers, so no per-draw descriptor set updates",
                "updating descriptor sets per draw"));
        choices.add(decide(device, "dynamicRendering", EXT_DYNAMIC_RENDERING,
                "drawing without a render pass, so no pass transition per frame",
                "using a render pass"));

        // Reversed-Z is assumed by the depth format and the projection, so its absence is not a slower
        // path but a different one, and it is reported as such rather than as a performance choice.
        if (has(device, EXT_DEPTH_CLIP_CONTROL)) {
            choices.add(new Choice("reversedZ", true, "VK_EXT_depth_clip_control present"));
        } else {
            choices.add(new Choice("reversedZ", false,
                    "VK_EXT_depth_clip_control missing; the depth buffer stays forward-Z and the "
                            + "projection has to match it or precision at distance is lost"));
        }

        return new Selection(choices);
    }

    private static Choice decide(GpuInfo device, String path, String extension, String gains,
                                 String fallback) {
        if (has(device, extension)) {
            return new Choice(path, true, extension + " present: " + gains);
        }
        return new Choice(path, false, extension + " missing; " + fallback);
    }

    private static String name(AmdArchitecture architecture) {
        return architecture == null ? "an unrecognised device" : architecture.displayName();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
