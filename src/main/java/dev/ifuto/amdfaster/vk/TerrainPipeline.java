package dev.ifuto.amdfaster.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayout;
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
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import java.nio.LongBuffer;

import static dev.ifuto.amdfaster.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * The terrain pipeline: region mesh vertex format + UBO/push-constant layout +
 * dynamic rendering (no VkRenderPass — RADV/AMDVLK build dynamic render passes
 * cheaply, and it removes the last fixed-function state block).
 *
 * <p>Vertex format (region-relative, see docs/design/vertex-format.md):
 * <pre>
 *   location 0  vec3   position    12 bytes
 *   location 1  vec2   texcoord    8 bytes
 *   location 2  u8vec4 color        4 bytes  (tint * AO * face shade, UNORM)
 *   location 3  u16vec2 light       4 bytes  (sky 0..15, block 0..15)
 * </pre>
 * Total 28 bytes/vertex. 28 is not 4-aligned-friendly, but Vulkan does not
 * require alignment — attribute offsets just need to match the binding.
 *
 * <p>Descriptors: set 0 = per-frame FrameData UBO (binding 0, vertex+fragment),
 * set 1 = block atlas sampler (binding 0, fragment). UBO is std140, 192 bytes,
 * written once per frame through the persistently mapped frame-constants buffer.
 */
public final class TerrainPipeline implements AutoCloseable {
	public static final int VERTEX_STRIDE = 28;

	private final VkDevice device;

	public long descriptorSetLayout;   // set 0 (UBO)
	public long atlasSetLayout;        // set 1 (sampler)
	public long pipelineLayout;
	public long pipeline;

	public TerrainPipeline(Device device, PipelineCache cache, int colorFormat, int depthFormat) {
		this.device = device.handle;
		try (MemoryStack stack = stackPush()) {
			LongBuffer pObj = stack.mallocLong(1);

			// ---- set layouts ----
			VkDescriptorSetLayoutBinding uboBinding = VkDescriptorSetLayoutBinding.calloc(stack)
					.binding(0)
					.descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
					.descriptorCount(1)
					.stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
			VkDescriptorSetLayoutCreateInfo dsl0 = VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
					.pBindings(uboBinding);
			check(VK10.vkCreateDescriptorSetLayout(device.handle, dsl0, null, pObj), "vkCreateDescriptorSetLayout(ubo)");
			descriptorSetLayout = pObj.get(0);

			VkDescriptorSetLayoutBinding samplerBinding = VkDescriptorSetLayoutBinding.calloc(stack)
					.binding(0)
					.descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
					.descriptorCount(1)
					.stageFlags(VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
			VkDescriptorSetLayoutCreateInfo dsl1 = VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
					.pBindings(samplerBinding);
			check(VK10.vkCreateDescriptorSetLayout(device.handle, dsl1, null, pObj), "vkCreateDescriptorSetLayout(atlas)");
			atlasSetLayout = pObj.get(0);

			// ---- pipeline layout ----
			VkPushConstantRange pushRange = VkPushConstantRange.calloc(stack)
					.stageFlags(VK10.VK_SHADER_STAGE_VERTEX_BIT)
					.offset(0)
					.size(16); // vec4 region origin
			VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
					.pSetLayouts(stack.longs(descriptorSetLayout, atlasSetLayout))
					.pPushConstantRanges(pushRange);
			check(VK10.vkCreatePipelineLayout(device.handle, plci, null, pObj), "vkCreatePipelineLayout");
			pipelineLayout = pObj.get(0);

			// ---- shader stages ----
			try (ShaderModule vert = new ShaderModule(device, "assets/amdfaster/spirv/terrain.vert.spv");
				 ShaderModule frag = new ShaderModule(device, "assets/amdfaster/spirv/terrain.frag.spv")) {
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

				// ---- vertex input ----
				VkPipelineVertexInputBindingDescription.Buffer binding = VkPipelineVertexInputBindingDescription.calloc(1, stack)
						.binding(0)
						.stride(VERTEX_STRIDE)
						.inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
				VkPipelineVertexInputAttributeDescription.Buffer attrs = VkPipelineVertexInputAttributeDescription.calloc(4, stack);
				attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
				attrs.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(12);
				attrs.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(20);
				attrs.get(3).location(3).binding(0).format(VK10.VK_FORMAT_R16G16_UNORM).offset(24);
				VkPipelineVertexInputStateCreateInfo vis = VkPipelineVertexInputStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
						.pVertexBindingDescriptions(binding)
						.pVertexAttributeDescriptions(attrs);

				// ---- fixed state ----
				VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
						.topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

				VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
						.viewportCount(1)
						.scissorCount(1);

				VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
						.polygonMode(VK10.VK_POLYGON_MODE_FILL)
						.cullMode(VK10.VK_CULL_MODE_BACK_BIT)
						.frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
						.lineWidth(1.0f);

				VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
						.rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);

				VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
						.depthTestEnable(true)
						.depthWriteEnable(true)
						.depthCompareOp(VK10.VK_COMPARE_OP_LESS_OR_EQUAL);

				VkPipelineColorBlendAttachmentState.Buffer blendAttach = VkPipelineColorBlendAttachmentState.calloc(1, stack)
						.blendEnable(false)
						.colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
								| VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT);
				VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
						.pAttachments(blendAttach);

				VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack)
						.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
						.pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));

				// ---- dynamic rendering (no render pass) ----
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
							gci, null, pObj), "vkCreateGraphicsPipelines(terrain)");
				pipeline = pObj.get(0);
			}
		}
	}

	/** Begins a dynamic render pass into the current swapchain image (clear sky-blue + depth). */
	public static void beginDynamicRendering(VkCommandBuffer cmd, int width, int height,
	                                         long colorView, long depthView) {
		try (MemoryStack stack = stackPush()) {
			VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack)
					.sType(VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
					.imageView(colorView)
					.imageLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
					.loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
					.storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
					.clearValue(v -> v.color().float32(0, 0.53f, 0.81f, 1.0f));
			VkRenderingAttachmentInfo.Buffer depth = VkRenderingAttachmentInfo.calloc(1, stack)
					.sType(VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
					.imageView(depthView)
					.imageLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
					.loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
					.storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
					.clearValue(v -> v.depthStencil().depth(1.0f).stencil(0));

			VkRenderingInfo ri = VkRenderingInfo.calloc(stack)
					.sType(VK13.VK_STRUCTURE_TYPE_RENDERING_INFO)
					.renderArea(a -> a.extent(e -> e.set(width, height)))
					.layerCount(1)
					.pColorAttachments(color)
					.pDepthAttachment(depth);
			VK13.vkCmdBeginRendering(cmd, ri);

			VkViewport.Buffer vp = VkViewport.calloc(1, stack)
					.x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
			VK10.vkCmdSetViewport(cmd, 0, vp);
			VkRect2D.Buffer sc = VkRect2D.calloc(1, stack)
					.extent(e -> e.set(width, height));
			VK10.vkCmdSetScissor(cmd, 0, sc);
		}
	}

	public static void endDynamicRendering(VkCommandBuffer cmd) {
		VK13.vkCmdEndRendering(cmd);
	}

	@Override
	public void close() {
		if (pipeline != 0L) {
			VK10.vkDestroyPipeline(device, pipeline, null);
		}
		if (pipelineLayout != 0L) {
			VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
		}
		if (descriptorSetLayout != 0L) {
			VK10.vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
		}
		if (atlasSetLayout != 0L) {
			VK10.vkDestroyDescriptorSetLayout(device, atlasSetLayout, null);
		}
	}
}
