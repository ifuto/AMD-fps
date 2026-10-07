package dev.ifuto.amdfaster.overlay;

import dev.ifuto.amdfaster.vk.Backend;
import dev.ifuto.amdfaster.vk.LinePipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Immediate-mode line overlay: region wireframes, a frame-time graph and the
 * bounding box of the meshed area. Drawn with {@link LinePipeline} after the
 * terrain pass.
 *
 * <p>Vertex format: {@code vec3 position, vec4 color (UNORM8)} — 16 bytes.
 * Lines are world-space (the push constant region origin is 0 for the overlay
 * pass because the line buffer is uploaded with absolute coordinates).
 */
public final class LineOverlay {
	private static final int MAX_VERTS = 1 << 16;
	private static final int VERTEX_SIZE = 16;

	private ByteBuffer vertices;
	private int vertexCount;

	public LineOverlay() {
		vertices = ByteBuffer.allocateDirect(MAX_VERTS * VERTEX_SIZE).order(ByteOrder.nativeOrder());
	}

	public void begin() {
		vertexCount = 0;
	}

	/** Adds a line segment (two endpoints, RGBA color 0..255 each). */
	public void line(float x0, float y0, float z0, float x1, float y1, float z1,
	                 int r, int g, int b, int a) {
		if (vertexCount + 2 > MAX_VERTS) {
			return;
		}
		putVertex(x0, y0, z0, r, g, b, a);
		putVertex(x1, y1, z1, r, g, b, a);
	}

	private void putVertex(float x, float y, float z, int r, int g, int b, int a) {
		int i = vertexCount * VERTEX_SIZE;
		vertices.putFloat(i, x);
		vertices.putFloat(i + 4, y);
		vertices.putFloat(i + 8, z);
		vertices.put(i + 12, (byte) r);
		vertices.put(i + 13, (byte) g);
		vertices.put(i + 14, (byte) b);
		vertices.put(i + 15, (byte) a);
		vertexCount++;
	}

	/** Draws a wire box (12 lines) around a region of {@code size} blocks at {@code origin}. */
	public void box(int originX, int originY, int originZ, int size, int r, int g, int b) {
		float x0 = originX, y0 = originY, z0 = originZ;
		float x1 = originX + size, y1 = originY + size, z1 = originZ + size;
		line(x0, y0, z0, x1, y0, z0, r, g, b, 255);
		line(x1, y0, z0, x1, y0, z1, r, g, b, 255);
		line(x1, y0, z1, x0, y0, z1, r, g, b, 255);
		line(x0, y0, z1, x0, y0, z0, r, g, b, 255);
		line(x0, y1, z0, x1, y1, z0, r, g, b, 255);
		line(x1, y1, z0, x1, y1, z1, r, g, b, 255);
		line(x1, y1, z1, x0, y1, z1, r, g, b, 255);
		line(x0, y1, z1, x0, y1, z0, r, g, b, 255);
		line(x0, y0, z0, x0, y1, z0, r, g, b, 255);
		line(x1, y0, z0, x1, y1, z0, r, g, b, 255);
		line(x1, y0, z1, x1, y1, z1, r, g, b, 255);
		line(x0, y0, z1, x0, y1, z1, r, g, b, 255);
	}

	public int vertexCount() {
		return vertexCount;
	}

	/** Uploads the line vertices into {@code buffer} and draws them. */
	public void draw(VkCommandBuffer cmd, Backend backend, dev.ifuto.amdfaster.vk.GpuBuffer buffer) {
		if (vertexCount == 0 || buffer == null) {
			return;
		}
		int bytes = vertexCount * VERTEX_SIZE;
		ByteBuffer src = vertices.duplicate();
		src.clear();
		src.limit(bytes);
		backend.memory.uploadNow(buffer, 0, src);

		VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, backend.linePipeline.pipeline);
		try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
			VK10.vkCmdPushConstants(cmd, backend.linePipeline.pipelineLayout,
					VK10.VK_SHADER_STAGE_VERTEX_BIT, 0, stack.floats(0, 0, 0, 0));
		}
		VK10.vkCmdBindVertexBuffers(cmd, 0, new long[]{buffer.handle}, new long[]{0});
		VK10.vkCmdDraw(cmd, vertexCount, 1, 0, 0);
	}
}
