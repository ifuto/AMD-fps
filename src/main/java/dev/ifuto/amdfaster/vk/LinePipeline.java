package dev.ifuto.amdfaster.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkPipelineVertexInputBindingDescription;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.nio.LongBuffer;

import static dev.ifuto.amdfaster.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Debug/wireframe pipeline used by the overlay: region outlines, frustum edges,
 * the frame-time graph and the small text-batch stand-in lines. Shares the
 * terrain UBO (set 0) so a single descriptor set layout covers both.
 *
 * Vertex format: {@code vec3 position, vec4 color (UNORM8)} — 16 bytes/vertex.
 */
public final class LinePipeline implements AutoCloseable {
	public static final int VERTEX_STRIDE = 16;

	private final VkDevice device;

	public long setLayout; // set 0, shared shape with TerrainPipeline's UBO layout
	public long pipelineLayout;
	public long pipeline;

	public LinePipeline(Device device, PipelineCache cache, int colorFormat, int depthFormat) {
		this.device = device.handle;
		try (MemoryStack stack = stackPush()) {
			LongBuffer pObj = stack.mallocLong(1);

			VkDescriptorSetLayoutBinding uboBinding = VkDescriptorSetLayoutBinding.calloc(stack)
					.binding(0)
					.descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
					.descriptorCount(1)
					.stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT);
			VkDescriptorSetLayoutCreateInfo dsl = VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
					.pBindings(uboBinding);
			check(VK10.vkCreateDescriptorSetLayout(device.handle, dsl, null, pObj), "vkCreateDescriptorSetLayout(lines)");
			setLayout = pObj.get(0);

			VkPushConstantRange pushRange = VkPushConstantRange.calloc(stack)
					.stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT)
					.offset(0)
					.size(16);
			VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
					.pSetLayouts(stack.longs(setLayout))
					.pPushConstantRanges(pushRange);
			check(VK10.vkCreatePipelineLayout(device.handle, plci, null, pObj), "vkCreatePipelineLayout(lines)");
			pipelineLayout = pObj.get(0);

			try (ShaderModule vert = new ShaderModule(device, "assets/amdfaster/spirv/lines.vert.spv");
				 ShaderModule frag = new ShaderModule(device, "assets/amdfaster/spirv/lines.frag.spv")) {
				VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
				stages.get(0)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
						.stage(VK10.VK_SHADER_STAGE_VERTEX_BIT)
						.module(vert.handle)
						.pName(stack.UTF8("main"));
				stages.get(1)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
						.stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
						.module(frag.handle)
						.pName(stack.UTF8("main"));

				VkPipelineVertexInputBindingDescription.Buffer binding = VkPipelineVertexInputBindingDescription.calloc(1, stack)
						.binding(0)
						.stride(VERTEX_STRIDE)
						.inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
				VkPipelineVertexInputAttributeDescription.Buffer attrs = VkPipelineVertexInputAttributeDescription.calloc(2, stack);
				attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
				attrs.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
				VkPipelineVertexInputStateCreateInfo vis = VkPipelineVertexInputStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
						.pVertexBindingDescriptions(binding)
						.pVertexAttributeDescriptions(attrs);

				VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
						.topology(VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST);

				VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
						.viewportCount(1).scissorCount(1);

				VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
						.polygonMode(VK10.VK_POLYGON_MODE_FILL)
						.cullMode(VK10.VK_CULL_MODE_NONE)
						.frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
						.lineWidth(1.0f);

				VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
						.rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

				VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
						.depthTestEnable(true)
						.depthWriteEnable(false)
						.depthCompareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL);

				VkPipelineColorBlendAttachmentState.Buffer blendAttach = VkPipelineColorBlendAttachmentState.calloc(1, stack)
						.blendEnable(true)
						.srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
						.dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
						.colorBlendOp(VK10.VK_BLEND_OP_ADD)
						.srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
						.dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
						.alphaBlendOp(VK10.VK_BLEND_OP_ADD)
						.colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
								| VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT);
				VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
						.pAttachments(blendAttach);

				VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
						.pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));

				VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
						.sType(device.has13
								? VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO
								: KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)
						.pColorAttachmentFormats(stack.ints(colorFormat))
						.depthAttachmentFormat(depthFormat);

				VkGraphicsPipelineCreateInfo gci = VkGraphicsPipelineCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
						.pStages(stages)
						.pVertexInputState(vis)
						.pInputAssemblyState(ia)
						.pViewportState(vp)
						.pRasterizationState(rs)
						.pMultisampleState(ms)
						.pDepthStencilState(ds)
						.pColorBlendState(cb)
						.pDynamicState(dyn)
						.layout(pipelineLayout)
						.pNext(rendering.address())
						.renderPass(VK10.VK_NULL_HANDLE)
						.subpass(0);

				check(VK10.vkCreateGraphicsPipelines(device.handle, cache == null ? 0L : cache.handle,
							gci, null, pObj), "vkCreateGraphicsPipelines(lines)");
				pipeline = pObj.get(0);
			}
		}
	}

	@Override
	public void close() {
		if (pipeline != 0L) {
			VK10.vkDestroyPipeline(device, pipeline, null);
		}
		if (pipelineLayout != 0L) {
			VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
		}
		if (setLayout != 0L) {
			VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
		}
	}
}
