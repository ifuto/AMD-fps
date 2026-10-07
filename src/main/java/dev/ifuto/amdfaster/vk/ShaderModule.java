package dev.ifuto.amdfaster.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * A SPIR-V shader module loaded from the jar.
 *
 * <p>AMD-Faster ships <b>precompiled SPIR-V</b> ({@code assets/amdfaster/spirv/*.spv},
 * built by {@code tools/compile_shaders.sh} with glslangValidator). No runtime
 * shader compiler dependency, no native downloads — the Modrinth App install is
 * a single jar. The GLSL sources live next to the SPIR-V in the resources and
 * are also the input for offline Radeon GPU Analyzer runs (docs/research/10-toolchain-rdts-gpa.md).
 */
public final class ShaderModule implements AutoCloseable {
	public final long handle;

	private final VkDevice device;

	public ShaderModule(VkDevice device, String resourcePath) {
		this.device = device;
		ByteBuffer spirv = VkUtil.loadResourceDirect(resourcePath);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
					.pCode(spirv);
			LongBuffer pModule = stack.mallocLong(1);
			VkUtil.check(VK10.vkCreateShaderModule(device, ci, null, pModule), "vkCreateShaderModule(" + resourcePath + ")");
			this.handle = pModule.get(0);
		} finally {
			MemoryUtil.memFree(spirv);
		}
	}

	@Override
	public void close() {
		if (handle != 0L) {
			VK10.vkDestroyShaderModule(device, handle, null);
		}
	}
}
