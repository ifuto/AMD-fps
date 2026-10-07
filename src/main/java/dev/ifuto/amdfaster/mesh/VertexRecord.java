package dev.ifuto.amdfaster.mesh;

/**
 * The packed 28-byte AMD-Faster terrain vertex, written straight into a
 * persistently mapped VkBuffer. Layout (little-endian) matches the
 * {@code TerrainPipeline} vertex input and {@code terrain.vert}:
 *
 * <pre>
 *   offset  0  float x   position, region-relative (0..regionSize)
 *   offset  4  float y
 *   offset  8  float z
 *   offset 12  float u   atlas texcoord
 *   offset 16  float v
 *   offset 20  u8  r     tint * AO * face shade, red
 *   offset 21  u8  g
 *   offset 22  u8  b
 *   offset 23  u8  a     (255 unless translucent layer)
 *   offset 24  u16 sky    lightmap sky  (0..15 -> 0..1 in shader)
 *   offset 26  u16 block  lightmap block (0..15 -> 0..1 in shader)
 * </pre>
 *
 * <p>Packing color into a single UNORM8 attribute keeps the vertex at 28 bytes —
 * small enough that a 16 KiB region slice (~590 vertices) fits in ~16 KiB, which
 * is the size sweet-spot for L2-resident vertex fetches on RDNA.
 */
public final class VertexRecord {
	public static final int SIZE = 28;
	public static final int OFF_X = 0;
	public static final int OFF_Y = 4;
	public static final int OFF_Z = 8;
	public static final int OFF_U = 12;
	public static final int OFF_V = 16;
	public static final int OFF_COLOR = 20;
	public static final int OFF_SKY = 24;
	public static final int OFF_BLOCK = 26;

	private VertexRecord() {
	}

	public static void write(java.nio.ByteBuffer buf, int offset,
	                         float x, float y, float z,
	                         float u, float v,
	                         int argb,
	                         int sky, int block) {
		buf.putFloat(offset + OFF_X, x);
		buf.putFloat(offset + OFF_Y, y);
		buf.putFloat(offset + OFF_Z, z);
		buf.putFloat(offset + OFF_U, u);
		buf.putFloat(offset + OFF_V, v);
		buf.put(offset + OFF_COLOR, (byte) ((argb >>> 16) & 0xFF));
		buf.put(offset + OFF_COLOR + 1, (byte) ((argb >>> 8) & 0xFF));
		buf.put(offset + OFF_COLOR + 2, (byte) (argb & 0xFF));
		buf.put(offset + OFF_COLOR + 3, (byte) ((argb >>> 24) & 0xFF));
		buf.putShort(offset + OFF_SKY, (short) sky);
		buf.putShort(offset + OFF_BLOCK, (short) block);
	}
}
