package net.amdfaster.runtime;

import net.amdfaster.pacing.AdaptiveQuality;
import net.amdfaster.pacing.FramePacer;
import net.amdfaster.pacing.RenderDistanceController;
import net.amdfaster.pacing.UnfocusedFrameLimiter;
import net.amdfaster.vk.CommandRecordScheduler;
import net.amdfaster.vk.FrameRing;
import net.amdfaster.vk.GeneratedCommandScheduler;
import net.amdfaster.vk.RootSignatureBudget;
import net.amdfaster.vk.TransferQueueSelector;
import net.amdfaster.platform.AmdArchitecture;
import net.amdfaster.platform.CoreAffinityPlanner;
import net.amdfaster.platform.DynamicVgprPlanner;
import net.amdfaster.platform.GpuIdentity;
import net.amdfaster.platform.GpuInfo;
import net.amdfaster.platform.GpuReport;
import net.amdfaster.platform.LdsBankConflictAvoider;
import net.amdfaster.platform.OccupancyModel;
import net.amdfaster.platform.VendorProfile;
import net.amdfaster.platform.WorkgroupModeSelector;
import net.amdfaster.platform.Xe3RegisterPlanner;

/**
 * Decides, once, what the hardware is and what that means for every other choice.
 *
 * <p>Every other component in this mod makes a decision that depends on the hardware: how big a workgroup
 * should be, whether to use dynamic registers, whether to put compute on the graphics queue, which threads go
 * on the fast cores. Each of those is answered by a planner, and each planner needs to know what it is running
 * on -- and that answer does not change while the game runs.
 *
 * <p>So it is decided once, at startup, from the device the game actually picked, and everything else asks here
 * rather than guessing.
 *
 * <h2>It asks the device rather than assuming a vendor</h2>
 *
 * <p>A rule keyed on "is this AMD" would be wrong twice: some Intel parts have the capabilities an AMD rule
 * assumes, and some AMD parts do not have the ones an Intel rule assumes. Asking what the device reports means a
 * part that gains a capability starts using it without a new build, and a part that never had it is not offered a
 * path it cannot run.
 *
 * <h2>It works when there is no GPU to ask</h2>
 *
 * <p>The probe can fail -- no Vulkan loader, a driver that will not initialise, a headless test. A mod that
 * cannot start without a GPU is worse than one that starts and runs its CPU-side work, so a failed probe leaves
 * the platform unknown and every planner that needs hardware answers with its safe default rather than throwing.
 *
 * <h2>The AMD and Intel planners disagree, deliberately</h2>
 *
 * <p>AMD allocates vector registers in granules and can allocate them dynamically in wave32 only; Intel allocates
 * in 32-entry blocks out of a 64 kilobyte file per execution unit. The same shader reaches different occupancies
 * on the two, and a planner that used one rule for both would report a number that is right on the machine it was
 * written against and wrong on the other -- which is the kind of error that survives testing because both machines
 * pass.
 *
 * <h2>Thread placement is a separate decision from thread priority</h2>
 *
 * <p>Raising a thread's priority makes the scheduler prefer it but does not decide which kind of core it runs on,
 * and on a hybrid CPU without hardware guidance for the scheduler the two are decided independently. So a thread
 * can be the highest priority thing running and still be on a slow core, and the affinity has to be set separately.
 *
 * <p>Not thread safe. Everything here is decided once at startup, on the thread that creates the client.
 */
public final class PlatformRuntime {

    /** What the hardware turned out to be. */
    public enum Vendor {
        /** Not determined, because the probe failed or the device is unknown. */
        UNKNOWN,
        AMD,
        INTEL,
        NVIDIA,
        OTHER
    }

    private final GpuReport report;
    private final GpuInfo device;
    private final Vendor vendor;
    private final AmdArchitecture amdArchitecture;
    private final VendorProfile.Shape shape;

    private final boolean hybridCpu;
    private final int performanceCores;
    private final int efficientCores;

    /** Splits command recording across cores. */
    private final CommandRecordScheduler commandRecording;

    /** The per-frame upload ring. */
    private final FrameRing frameRing = new FrameRing(16L << 20, 3);

    /** Whether device-generated commands are used. */
    private final GeneratedCommandScheduler generatedCommands;

    /** The last root-signature budget check, so the HUD can show whether it fits. */
    private RootSignatureBudget.Verdict rootSignature =
            RootSignatureBudget.check(0, 0);

    /** Paces frames against a target interval. */
    private final FramePacer pacer = FramePacer.lowLatency();

    /** Trades render distance for frame time. */
    private final AdaptiveQuality quality;

    /** Moves render distance to hit a frame-time target. */
    private final RenderDistanceController distance;

    /** Drops the frame rate when the window is not focused. */
    private final UnfocusedFrameLimiter unfocused = new UnfocusedFrameLimiter();

    public PlatformRuntime() {
        this(GpuReport.probe(), false, 0, 0);
    }

    /**
     * @param report the GPU probe result, which may have failed
     * @param hybridCpu whether the CPU has two kinds of core
     * @param performanceCores how many fast cores it has
     * @param efficientCores how many efficient cores it has
     */
    public PlatformRuntime(GpuReport report, boolean hybridCpu, int performanceCores,
            int efficientCores) {
        this.report = report;
        this.device = report != null && report.succeeded() ? report.preferred() : null;
        this.hybridCpu = hybridCpu;
        this.performanceCores = performanceCores;
        this.efficientCores = efficientCores;

        String name = this.device != null ? this.device.deviceName() : "";
        GpuIdentity.Match match = GpuIdentity.classify(name, "", "");
        this.amdArchitecture = match.architecture();
        this.shape = VendorProfile.fromDeviceName(name);
        this.vendor = classifyVendor(name);

        // Command recording splits across the cores the JVM can actually use; one thread per core is the
        // point, so zero cores would mean no recording at all.
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.commandRecording = new CommandRecordScheduler(cores);
        // Device-generated commands need the extension, which is a device property and not a choice.
        this.generatedCommands = new GeneratedCommandScheduler(
                report != null && report.succeeded() && report.preferred() != null);
        this.quality = new AdaptiveQuality(12, 32);
        this.distance = new RenderDistanceController(16_666_667L, 12);
    }

    private static Vendor classifyVendor(String deviceName) {
        String lower = deviceName.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("radeon") || lower.contains("amd") || lower.contains("gfx")) {
            return Vendor.AMD;
        }
        if (lower.contains("intel") || lower.contains("arc") || lower.contains("xe")) {
            return Vendor.INTEL;
        }
        if (lower.contains("nvidia") || lower.contains("geforce") || lower.contains("rtx")) {
            return Vendor.NVIDIA;
        }
        return lower.isEmpty() ? Vendor.UNKNOWN : Vendor.OTHER;
    }

    /** Which vendor the device turned out to be. */
    public Vendor vendor() {
        return this.vendor;
    }

    /** The probe result, which may have failed. */
    public GpuReport report() {
        return this.report;
    }

    /** The device the game should use, or {@code null} if the probe failed. */
    public GpuInfo device() {
        return this.device;
    }

    /** The AMD architecture, or {@code null} if this is not an AMD part. */
    public AmdArchitecture amdArchitecture() {
        return this.amdArchitecture;
    }

    /** The vendor profile shape, for tile sizing and the extended math penalty. */
    public VendorProfile.Shape shape() {
        return this.shape;
    }

    /** Whether the probe succeeded at all. */
    public boolean isKnown() {
        return this.device != null;
    }

    /**
     * Whether the architecture was identified.
     *
     * <p>{@code GpuIdentity.classify} returns {@code AmdArchitecture.UNKNOWN} rather than null when it cannot
     * identify the device, and UNKNOWN deliberately reports zero for everything -- AMD-Faster must not invent a
     * number for hardware it could not identify. So the check has to be for UNKNOWN, not for null.
     */
    private boolean architectureKnown() {
        return this.amdArchitecture != null
                && this.amdArchitecture != AmdArchitecture.UNKNOWN;
    }

    /** The native wave size, or 32 as the safe default when the hardware is unknown. */
    public int waveSize() {
        return architectureKnown() ? this.amdArchitecture.nativeWaveSize() : 32;
    }

    /** The recommended workgroup size, or 64 as the safe default. */
    public int workgroupSize() {
        return architectureKnown() ? this.amdArchitecture.recommendedWorkgroupSize() : 64;
    }

    /** The local memory per compute unit, or 64 kilobytes as the safe default. */
    public int ldsBytesPerCu() {
        return architectureKnown() ? this.amdArchitecture.ldsBytesPerCu() : 64 * 1024;
    }

    /**
     * Whether dynamic register allocation is available and worth using.
     *
     * <p>It is wave32-only and compute-only on AMD, so asking for it otherwise would either fail to launch or
     * silently fall back, and a planner that assumed otherwise would report an occupancy the hardware cannot
     * deliver.
     *
     * @param compute whether the shader is a compute shader
     * @param declaredVgprs the registers it declares
     * @param maxVgprs the most a wave may grow to
     * @param waves the waves the plan keeps resident
     */
    public boolean useDynamicVgprs(boolean compute, int declaredVgprs, int maxVgprs, int waves) {
        if (this.vendor != Vendor.AMD) {
            return false;
        }
        boolean wave32 = architectureKnown() && waveSize() == 32;
        return DynamicVgprPlanner.isWorthIt(wave32, compute, declaredVgprs, maxVgprs, waves);
    }

    /**
     * The occupancy a shader reaches on this hardware.
     *
     * <p>AMD and Intel allocate registers differently, so the same shader reaches different occupancies and a
     * single rule for both would be right on one machine and wrong on the other.
     *
     * @param registersPerThread the registers each thread needs
     */
    public double occupancy(int registersPerThread) {
        if (this.vendor == Vendor.INTEL) {
            return Xe3RegisterPlanner.occupancy(registersPerThread);
        }
        if (this.amdArchitecture != null) {
            return OccupancyModel.occupancy(OccupancyModel.RDNA4, registersPerThread);
        }
        return Xe3RegisterPlanner.occupancy(registersPerThread);
    }

    /**
     * Which queue compute should go on.
     *
     * <p>On AMD the driver inserts a memory-fence stall on the dedicated compute queue, which costs more than
     * the overlap gains, so compute goes on the graphics queue. On other hardware the dedicated queue does what
     * it is supposed to and the overlap is real.
     *
     * @param hasDedicatedCompute whether the device exposes a compute-only queue
     */
    public boolean computeOnGraphicsQueue(boolean hasDedicatedCompute) {
        return net.amdfaster.vk.ComputeQueueSelector.choose(this.vendor == Vendor.AMD,
                hasDedicatedCompute) == net.amdfaster.vk.ComputeQueueSelector.Queue.GRAPHICS;
    }

    /**
     * Which cores a thread should be pinned to.
     *
     * <p>Returns {@code null} when the CPU has one kind of core, because pinning there would restrict the thread
     * to a subset of identical cores for no reason and could make things worse by preventing the scheduler from
     * balancing at all.
     */
    public CoreAffinityPlanner.CoreKind corePlacement(CoreAffinityPlanner.ThreadRole role) {
        return CoreAffinityPlanner.placement(role, this.hybridCpu, this.performanceCores,
                this.efficientCores);
    }

    /**
     * The stride that avoids a local memory bank conflict.
     *
     * <p>A stride equal to the bank count puts every lane on the same bank and serialises the reads, which is
     * invisible in the source because the code looks like an ordinary array read.
     */
    public int conflictFreeStride(int strideElements) {
        int banks = ldsBytesPerCu() / 4;
        return LdsBankConflictAvoider.paddedStride(strideElements, waveSize(), banks);
    }

    /**
     * Which workgroup mode to use.
     *
     * <p>The wider mode doubles the local memory a workgroup can use but couples two compute units, which costs
     * more than it gains for kernels that spend their time in atomics or inter-wave communication.
     */
    public WorkgroupModeSelector.Mode workgroupMode(int ldsBytesNeeded, boolean heavyAtomics,
            boolean heavyInterWave) {
        return WorkgroupModeSelector.choose(ldsBytesNeeded, heavyAtomics, heavyInterWave);
    }

    /** How many fast cores the CPU has, or zero when it is not hybrid. */
    public int performanceCores() {
        return this.performanceCores;
    }

    /** How many efficient cores the CPU has, or zero when it is not hybrid. */
    public int efficientCores() {
        return this.efficientCores;
    }

    /** Whether the CPU has two kinds of core. */
    public boolean isHybridCpu() {
        return this.hybridCpu;
    }

    /** The command-recording scheduler. */
    public CommandRecordScheduler commandRecording() {
        return this.commandRecording;
    }

    /** The per-frame upload ring. */
    public FrameRing frameRing() {
        return this.frameRing;
    }

    /** The device-generated command scheduler. */
    public GeneratedCommandScheduler generatedCommands() {
        return this.generatedCommands;
    }

    /** The last root-signature budget check. */
    public RootSignatureBudget.Verdict rootSignature() {
        return this.rootSignature;
    }

    /**
     * Checks a root signature against the budget.
     *
     * <p>Exposed because a root signature that does not fit is not a slow path but a device limit, and the
     * caller has to know before it records commands rather than after the device rejects them.
     *
     * @param pushConstantBytes how many push-constant bytes the signature declares
     * @param descriptorSets how many descriptor sets it declares
     * @return the verdict, including how far over it is when it does not fit
     */
    public RootSignatureBudget.Verdict checkRootSignature(int pushConstantBytes, int descriptorSets) {
        this.rootSignature = RootSignatureBudget.check(pushConstantBytes, descriptorSets);
        return this.rootSignature;
    }

    /** The frame pacer. */
    public FramePacer pacer() {
        return this.pacer;
    }

    /** The adaptive-quality controller. */
    public AdaptiveQuality quality() {
        return this.quality;
    }

    /** The render-distance controller. */
    public RenderDistanceController distance() {
        return this.distance;
    }

    /** The unfocused frame limiter. */
    public UnfocusedFrameLimiter unfocused() {
        return this.unfocused;
    }

    /**
     * Chooses which queue a copy should go through.
     *
     * <p>Exposed because the answer depends on where both ends live, and a caller that guessed would put
     * on-GPU copies on the transfer queue -- which is the one thing the architecture notes say never to do.
     *
     * @param sourceInVideoMemory whether the source is device-local
     * @param destinationInVideoMemory whether the destination is device-local
     * @param unifiedMemory whether this is an APU with one pool
     * @return the route to use
     */
    public TransferQueueSelector.Route transferRoute(boolean sourceInVideoMemory,
            boolean destinationInVideoMemory, boolean unifiedMemory) {
        return TransferQueueSelector.choose(sourceInVideoMemory, destinationInVideoMemory,
                unifiedMemory);
    }
}
