package net.amdfaster.vk;

import java.util.ArrayList;
import java.util.List;

/**
 * Accounts the cost of a draw's parameter binding against AMD's 13-DWORD root signature budget.
 *
 * <p>The RDNA performance guide is explicit that a root signature above 13 DWORDs costs
 * performance on every draw, because the whole thing is copied into the command buffer and read
 * back by the shader. It is the one limit that is easy to violate by accident -- one more
 * descriptor set here, a few more push constants there -- and invisible in a frame capture.
 *
 * <p>Costs are the D3D12 root signature ones the guide is written against, which map onto Vulkan
 * as follows:
 * <table border="1">
 *   <caption>D3D12 to Vulkan</caption>
 *   <tr><th>D3D12</th><th>Vulkan</th><th>DWORDs</th></tr>
 *   <tr><td>root constant</td><td>push constant, per 4 bytes</td><td>1</td></tr>
 *   <tr><td>descriptor table</td><td>one bound descriptor set</td><td>1</td></tr>
 *   <tr><td>root CBV/SRV/UAV</td><td>one push descriptor</td><td>2</td></tr>
 *   <tr><td>&mdash;</td><td>dynamic buffer, robust buffer access off</td><td>2</td></tr>
 *   <tr><td>&mdash;</td><td>dynamic buffer, robust buffer access on</td><td>4</td></tr>
 * </table>
 *
 * <p>The dynamic buffer rows are the Vulkan side of the guide's table with no D3D12 equivalent, and they
 * are the expensive ones: four DWORDs is a third of the whole 13 DWORD budget for a single binding. A
 * pipeline that binds its per-frame uniform buffer dynamically under robust access spends that before it
 * has bound anything else, so the choice between the two is worth making deliberately rather than
 * inheriting from whatever the device features happened to enable.
 */
public final class RootSignatureBudget {

    public enum Kind {
        /** 4 bytes of push constant. */
        PUSH_CONSTANT_DWORD(1),
        /** One descriptor set bound at draw time. */
        DESCRIPTOR_SET(1),
        /** One descriptor pushed inline at draw time, without a set. */
        PUSH_DESCRIPTOR(2),
        /**
         * One descriptor bound with a dynamic offset, with robust buffer access off.
         *
         * <p>Twice a plain descriptor set. The offset has to live somewhere the shader can reach it, and
         * that is what the extra DWORD is.
         */
        DYNAMIC_BUFFER(2),
        /**
         * One descriptor bound with a dynamic offset, with robust buffer access on.
         *
         * <p>Twice again. Robust access adds the bounds the driver checks each access against, so a
         * dynamic buffer under it costs four DWORDs against a plain set's one -- which is the whole
         * argument for keeping the per-frame data in one set with a dynamic offset rather than spreading
         * it across several, and for knowing which of the two a pipeline is actually using.
         */
        DYNAMIC_BUFFER_ROBUST(4);

        private final int dwords;

        Kind(int dwords) {
            this.dwords = dwords;
        }

        public int dwords() {
            return this.dwords;
        }
    }

    /** One entry of a pipeline's parameter layout. */
    public record Parameter(Kind kind, int count, String name) {

        public int dwords() {
            return this.kind.dwords() * this.count;
        }
    }

    /**
     * @param fits     whether the total is within the budget AMD recommends
     * @param overBy   DWORDs over the budget, or 0
     */
    public record Verdict(int dwords, boolean fits, int overBy, String detail) {
    }

    private RootSignatureBudget() {
    }

    public static Verdict check(List<Parameter> parameters) {
        int total = 0;
        List<String> parts = new ArrayList<>(parameters.size());
        for (Parameter p : parameters) {
            total += p.dwords();
            parts.add(p.name() + "=" + p.dwords());
        }
        int budget = net.amdfaster.platform.AmdArchitecture.ROOT_SIGNATURE_DWORD_BUDGET;
        boolean fits = total <= budget;
        return new Verdict(total, fits, fits ? 0 : total - budget,
                String.join("+", parts) + " = " + total + " DWORDs (budget " + budget + ")");
    }

    /**
     * Convenience: the shape most of this mod's pipelines have.
     *
     * @param pushConstantBytes bytes in the push constant range
     * @param descriptorSets    sets bound without a dynamic offset
     * @param dynamicBuffers    sets bound with one, which is where the per-frame data lives
     * @param robustBufferAccess whether the device feature is enabled, which doubles a dynamic buffer's
     *                           cost from two DWORDs to four
     */
    public static Verdict check(int pushConstantBytes, int descriptorSets, int dynamicBuffers,
                                boolean robustBufferAccess) {
        List<Parameter> parameters = new ArrayList<>(3);
        if (pushConstantBytes > 0) {
            parameters.add(new Parameter(Kind.PUSH_CONSTANT_DWORD,
                    (pushConstantBytes + 3) / 4, "pushConstants"));
        }
        if (descriptorSets > 0) {
            parameters.add(new Parameter(Kind.DESCRIPTOR_SET, descriptorSets, "descriptorSets"));
        }
        if (dynamicBuffers > 0) {
            parameters.add(new Parameter(
                    robustBufferAccess ? Kind.DYNAMIC_BUFFER_ROBUST : Kind.DYNAMIC_BUFFER,
                    dynamicBuffers, robustBufferAccess ? "dynamicRobust" : "dynamic"));
        }
        return check(parameters);
    }

    /** Convenience: push constants in bytes plus bound descriptor sets, the common shape. */
    public static Verdict check(int pushConstantBytes, int descriptorSets) {
        List<Parameter> parameters = new ArrayList<>(2);
        if (pushConstantBytes > 0) {
            // Round up: a partial DWORD still costs a whole one in the root signature.
            parameters.add(new Parameter(Kind.PUSH_CONSTANT_DWORD,
                    (pushConstantBytes + 3) / 4, "pushConstants"));
        }
        if (descriptorSets > 0) {
            parameters.add(new Parameter(Kind.DESCRIPTOR_SET, descriptorSets, "descriptorSets"));
        }
        return check(parameters);
    }
}
