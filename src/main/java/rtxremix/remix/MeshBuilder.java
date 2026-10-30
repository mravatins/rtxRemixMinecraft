package rtxremix.remix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Accumulates quads as remixapi_HardcodedVertex data; {@link #build} turns them into a triangle-list layer. */
final class MeshBuilder {
	private ByteBuffer vertices = ByteBuffer.allocate(64 * RemixApi.VERTEX_STRIDE).order(ByteOrder.LITTLE_ENDIAN);
	private int vertexCount;

	/** sRGB byte to linear byte. */
	private static final int[] SRGB_TO_LINEAR = new int[256];

	static {
		for (int i = 0; i < 256; i++) {
			float c = i / 255f;
			float l = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
			SRGB_TO_LINEAR[i] = Math.round(l * 255f);
		}
	}

	/**
	 * @param argb Minecraft's vertex color, 0xAARRGGBB with sRGB-encoded RGB. Remix multiplies the vertex color as it
	 *             is with the linear texture color, so RGB is decoded here; left encoded, tints read far paler (grass
	 *             and leaves turn a washed-out yellow-grey). Alpha is coverage and stays linear.
	 */
	void vertex(float x, float y, float z, float nx, float ny, float nz, float u, float v, int argb) {
		if (vertices.capacity() < (vertexCount + 1) * RemixApi.VERTEX_STRIDE) {
			vertices = ByteBuffer.wrap(Arrays.copyOf(vertices.array(), vertices.capacity() * 2)).order(ByteOrder.LITTLE_ENDIAN);
		}
		int d = vertexCount++ * RemixApi.VERTEX_STRIDE;
		vertices.putFloat(d, x);
		vertices.putFloat(d + 4, y);
		vertices.putFloat(d + 8, z);
		vertices.putFloat(d + 12, nx);
		vertices.putFloat(d + 16, ny);
		vertices.putFloat(d + 20, nz);
		vertices.putFloat(d + 24, u);
		vertices.putFloat(d + 28, v);
		vertices.putInt(d + 32, argb & 0xFF000000 | SRGB_TO_LINEAR[argb >> 16 & 0xFF] << 16
				| SRGB_TO_LINEAR[argb >> 8 & 0xFF] << 8 | SRGB_TO_LINEAR[argb & 0xFF]);
	}

	boolean isEmpty() {
		return vertexCount < 4;
	}

	/** Two triangles per quad, in Minecraft's winding. Vertices past the last whole quad are dropped. */
	SectionCapture.Layer build() {
		int quads = vertexCount / 4;
		int[] indices = new int[quads * 6];
		for (int q = 0; q < quads; q++) {
			int i = q * 6;
			int v0 = q * 4;
			indices[i] = v0;
			indices[i + 1] = v0 + 1;
			indices[i + 2] = v0 + 2;
			indices[i + 3] = v0;
			indices[i + 4] = v0 + 2;
			indices[i + 5] = v0 + 3;
		}
		return new SectionCapture.Layer(Arrays.copyOf(vertices.array(), quads * 4 * RemixApi.VERTEX_STRIDE), quads * 4, indices);
	}
}
