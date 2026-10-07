package dev.amdfaster.core.backend;

import java.util.List;

/**
 * What the renderer actually engaged this session, as opposed to what the
 * {@link dev.amdfaster.core.plan.TuningPlan} intended.
 *
 * <p>The distinction is the whole point: a plan is a set of decisions taken from static information,
 * while this record is written by the renderer as it brings each path up. If a technique is planned
 * but inactive, {@link #notes()} says why - "no ARB_buffer_storage at runtime", "shader compilation
 * failed", "device lost, fell back to vanilla" and so on.
 */
public record ActiveFeatures(
        BackendKind backend,
        boolean persistentStaging,
        boolean multiDrawIndirect,
        boolean regionBfsCulling,
        boolean depthPyramidOcclusion,
        boolean reversedZ,
        boolean vertexFormatPacked,
        List<String> notes) {

    public ActiveFeatures {
        notes = List.copyOf(notes);
    }

    /** Nothing engaged yet: the safe state before the render device exists. */
    public static ActiveFeatures inactive() {
        return new ActiveFeatures(BackendKind.VANILLA, false, false, false, false, false, false,
                List.of("renderer not initialised yet"));
    }

    public ActiveFeatures with(BackendKind newBackend, boolean staging, boolean indirect,
                               boolean culling, boolean pyramid, boolean revZ, boolean packed,
                               String... newNotes) {
        return new ActiveFeatures(newBackend, staging, indirect, culling, pyramid, revZ, packed,
                List.of(newNotes));
    }

    public String summary() {
        return backend.label()
                + " | staging " + onOff(persistentStaging)
                + ", indirect " + onOff(multiDrawIndirect)
                + ", region BFS " + onOff(regionBfsCulling)
                + ", depth pyramid " + onOff(depthPyramidOcclusion)
                + ", reversed-Z " + onOff(reversedZ)
                + ", packed vertices " + onOff(vertexFormatPacked);
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
