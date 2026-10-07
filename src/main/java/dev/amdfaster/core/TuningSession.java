package dev.amdfaster.core;

import dev.amdfaster.core.arch.GpuIdentity;
import dev.amdfaster.core.backend.ActiveFeatures;
import dev.amdfaster.core.backend.GpuCapabilities;
import dev.amdfaster.core.plan.TuningPlan;

/**
 * Everything AMD-Faster knows and decided about this session, in one immutable object.
 *
 * <p>This is what the debug HUD reads, what {@code /amdfaster report} prints, and what a bug report
 * should contain: the device identification, the probed capabilities, the planned techniques and the
 * ones that actually engaged. Keeping them together is what makes "why is my FPS different from
 * theirs" answerable without guessing.
 */
public record TuningSession(
        GpuIdentity identity,
        GpuCapabilities capabilities,
        TuningPlan plan,
        ActiveFeatures active) {

    /** Full multi-line report used by logs, the HUD and issue reports. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("AMD-Faster session\n");
        sb.append("  device      : ").append(identity.describe()).append('\n');
        sb.append("  driver      : ").append(capabilities.driver().label())
                .append(" / ").append(capabilities.apiVersion()).append('\n');
        sb.append("  memory      : ")
                .append(capabilities.dedicatedVideoMemoryMiB() > 0
                        ? capabilities.dedicatedVideoMemoryMiB() + " MiB VRAM" : "VRAM unknown")
                .append(", BAR ").append(capabilities.barSizeMiB() > 0
                        ? capabilities.barSizeMiB() + " MiB" : "unknown")
                .append('\n');
        sb.append("  plan        : ").append(plan.summary()).append('\n');
        sb.append("  active      : ").append(active.summary()).append('\n');
        sb.append("  reasoning   :\n");
        for (String reason : plan.rationale()) {
            sb.append("    - ").append(reason).append('\n');
        }
        for (String note : active.notes()) {
            sb.append("    ! ").append(note).append('\n');
        }
        return sb.toString();
    }
}
