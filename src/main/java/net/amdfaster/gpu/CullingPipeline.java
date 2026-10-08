package net.amdfaster.gpu;

import net.amdfaster.vk.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

/**
 * The two compute pipelines that do the culling, and the layout they share.
 *
 * <p>The SPIR-V comes from the jar, compiled by the {@code compileShaders} build task, so no player
 * needs a GLSL toolchain and no shader is compiled at load time. Loading a several-megabyte GLSL
 * compiler into a game process to run it twice would be the wrong trade.
 *
 * <p>Both passes use the same descriptor set layout apart from the pyramid binding, which is what
 * lets them share a descriptor pool and, more importantly, lets the frame's uniform and meshlet
 * buffers be written once for both.
 */
public final class CullingPipeline implements AutoCloseable {

    private final VkContext context;
    private final long frustumSetLayout;
    private final long frustumPipelineLayout;
    private final long frustumPipeline;
    private final long occlusionSetLayout;
    private final long occlusionPipelineLayout;
    private final long occlusionPipeline;

    private CullingPipeline(VkContext context, long frustumSetLayout, long frustumPipelineLayout,
                            long frustumPipeline, long occlusionSetLayout, long occlusionPipelineLayout,
                            long occlusionPipeline) {
        this.context = context;
        this.frustumSetLayout = frustumSetLayout;
        this.frustumPipelineLayout = frustumPipelineLayout;
        this.frustumPipeline = frustumPipeline;
        this.occlusionSetLayout = occlusionSetLayout;
        this.occlusionPipelineLayout = occlusionPipelineLayout;
        this.occlusionPipeline = occlusionPipeline;
    }

    public static CullingPipeline create(VkContext context) {
        try (MemoryStack stack = stackPush()) {
            long frustumSetLayout = 0;
            long frustumLayout = 0;
            long frustumPipeline = 0;
            long occlusionSetLayout = 0;
            long occlusionLayout = 0;
            long occlusionPipeline = 0;
            try {
                frustumSetLayout = setLayout(context, CullBindings.descriptorBindings(), stack);
                frustumLayout = pipelineLayout(context, frustumSetLayout, stack);
                frustumPipeline = computePipeline(context, CullBindings.CULL_SPIRV_PATH, frustumLayout, stack);

                occlusionSetLayout = setLayout(context, CullBindings.occlusionDescriptorBindings(), stack);
                occlusionLayout = pipelineLayout(context, occlusionSetLayout, stack);
                occlusionPipeline = computePipeline(context, CullBindings.OCCLUSION_SPIRV_PATH,
                        occlusionLayout, stack);

                return new CullingPipeline(context, frustumSetLayout, frustumLayout, frustumPipeline,
                        occlusionSetLayout, occlusionLayout, occlusionPipeline);
            } catch (RuntimeException e) {
                destroy(context, frustumSetLayout, frustumLayout, frustumPipeline,
                        occlusionSetLayout, occlusionLayout, occlusionPipeline);
                throw e;
            }
        }
    }

    private static long setLayout(VkContext context, List<CullBindings.Binding> bindings,
                                  MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer buffer = VkDescriptorSetLayoutBinding.malloc(bindings.size(), stack);
        for (int i = 0; i < bindings.size(); i++) {
            CullBindings.Binding b = bindings.get(i);
            buffer.get(i)
                    .binding(b.binding())
                    .descriptorType(b.descriptorType())
                    .descriptorCount(b.count())
                    .stageFlags(b.stageFlags())
                    .pImmutableSamplers(null);
        }
        VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(buffer);
        LongBuffer pLayout = stack.mallocLong(1);
        int result = vkCreateDescriptorSetLayout(context.device(), createInfo, null, pLayout);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vkCreateDescriptorSetLayout failed with VkResult " + result);
        }
        return pLayout.get(0);
    }

    private static long pipelineLayout(VkContext context, long setLayout, MemoryStack stack) {
        VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.malloc(1, stack)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                .offset(0)
                .size(CullBindings.PUSH_CONSTANT_BYTES);

        VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pSetLayouts(stack.longs(setLayout))
                .pPushConstantRanges(pushConstants);

        LongBuffer pLayout = stack.mallocLong(1);
        int result = vkCreatePipelineLayout(context.device(), createInfo, null, pLayout);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vkCreatePipelineLayout failed with VkResult " + result);
        }
        return pLayout.get(0);
    }

    private static long computePipeline(VkContext context, String spirvPath, long layout,
                                        MemoryStack stack) {
        byte[] code = readResource(spirvPath);
        // pCode has to be 4-byte aligned; MemoryUtil.memAlloc returns memory aligned for any use.
        // The stack is not guaranteed to be, and a misaligned module is a validation error that
        // some drivers turn into a crash.
        ByteBuffer pCode = MemoryUtil.memAlloc(code.length);
        long module = 0L;
        try {
            pCode.put(code).flip();
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(pCode);
            LongBuffer pModule = stack.mallocLong(1);
            int result = vkCreateShaderModule(context.device(), moduleInfo, null, pModule);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateShaderModule failed with VkResult " + result
                        + " for " + spirvPath);
            }
            module = pModule.get(0);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module)
                    .pName(stack.UTF8("main"));

            // calloc(int, MemoryStack) returns a Buffer, and its setters keep returning Buffer,
            // unlike the single-struct calloc(MemoryStack) form. vkCreateComputePipelines wants the
            // Buffer anyway.
            VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .stage(stage)
                    .layout(layout);

            LongBuffer pPipeline = stack.mallocLong(1);
            result = vkCreateComputePipelines(context.device(), 0L, createInfo, null, pPipeline);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateComputePipelines failed with VkResult " + result
                        + " for " + spirvPath);
            }
            return pPipeline.get(0);
        } finally {
            MemoryUtil.memFree(pCode);
            if (module != 0L) {
                // The pipeline holds its own reference, so the module can go as soon as creation
                // returns.
                vkDestroyShaderModule(context.device(), module, null);
            }
        }
    }

    private static byte[] readResource(String path) {
        try (InputStream in = CullingPipeline.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing shader module " + path
                        + "; the compileShaders build task did not run");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    private static void destroy(VkContext context, long frustumSetLayout, long frustumLayout,
                                long frustumPipeline, long occlusionSetLayout, long occlusionLayout,
                                long occlusionPipeline) {
        if (frustumPipeline != 0L) {
            vkDestroyPipeline(context.device(), frustumPipeline, null);
        }
        if (frustumLayout != 0L) {
            vkDestroyPipelineLayout(context.device(), frustumLayout, null);
        }
        if (frustumSetLayout != 0L) {
            vkDestroyDescriptorSetLayout(context.device(), frustumSetLayout, null);
        }
        if (occlusionPipeline != 0L) {
            vkDestroyPipeline(context.device(), occlusionPipeline, null);
        }
        if (occlusionLayout != 0L) {
            vkDestroyPipelineLayout(context.device(), occlusionLayout, null);
        }
        if (occlusionSetLayout != 0L) {
            vkDestroyDescriptorSetLayout(context.device(), occlusionSetLayout, null);
        }
    }

    /** Bind point the pipelines are recorded against. */
    public static int bindPoint() {
        return VK_PIPELINE_BIND_POINT_COMPUTE;
    }

    public long frustumPipeline() {
        return this.frustumPipeline;
    }

    public long frustumPipelineLayout() {
        return this.frustumPipelineLayout;
    }

    public long frustumSetLayout() {
        return this.frustumSetLayout;
    }

    public long occlusionPipeline() {
        return this.occlusionPipeline;
    }

    public long occlusionPipelineLayout() {
        return this.occlusionPipelineLayout;
    }

    public long occlusionSetLayout() {
        return this.occlusionSetLayout;
    }

    @Override
    public void close() {
        destroy(this.context, this.frustumSetLayout, this.frustumPipelineLayout, this.frustumPipeline,
                this.occlusionSetLayout, this.occlusionPipelineLayout, this.occlusionPipeline);
    }
}
