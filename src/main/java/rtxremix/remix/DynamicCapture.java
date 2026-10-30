package rtxremix.remix;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import rtxremix.mixin.RenderSetupAccessor;
import rtxremix.mixin.RenderTypeAccessor;
import rtxremix.mixin.TextureBindingAccessor;

import java.util.HashMap;
import java.util.Map;

/**
 * Collects the world's items, entity models and particles while Minecraft prepares the level's features each frame. Their positions
 * are relative to the camera, as Minecraft builds them; {@link RemixRenderer} turns each bucket into a mesh for the
 * frame. Render thread only.
 */
public final class DynamicCapture {
	public enum Kind {
		/** Alpha-tested and lit. */
		CUTOUT,
		/** Alpha-blended and lit. */
		BLEND,
		/** Alpha-blended and emissive: particles drawn at full block light, such as flames. */
		GLOW
	}

	/** @param entity whether {@code atlas} is an entity texture, which {@link RemixRenderer} loads on first use */
	public record Key(Identifier atlas, Kind kind, boolean particle, boolean entity) {
	}

	/** Packed block light (the low half of light coords) at level 15, the brightest a particle can be lit. */
	private static final int FULL_BLOCK_LIGHT = 15 << 4;
	private static boolean capturing;
	private static final Map<Key, MeshBuilder> buckets = new HashMap<>();
	private static final ParticleSink particleSink = new ParticleSink();
	private static final ModelSink modelSink = new ModelSink();
	private static final PoseStack modelPose = new PoseStack();

	private DynamicCapture() {
	}

	/** Brackets the level's feature preparation, so hands and items drawn in the HUD are not captured. */
	public static void begin() {
		buckets.clear();
		capturing = RemixRenderer.active();
	}

	public static void end() {
		capturing = false;
	}

	/** Hands over this frame's geometry; the next {@link #begin} starts empty. */
	static Map<Key, MeshBuilder> take() {
		Map<Key, MeshBuilder> out = Map.copyOf(buckets);
		buckets.clear();
		return out;
	}

	private static MeshBuilder bucket(Identifier atlas, Kind kind, boolean particle) {
		return buckets.computeIfAbsent(new Key(atlas, kind, particle, false), k -> new MeshBuilder());
	}

	public static void item(ItemFeatureRenderer.Submit submit) {
		if (!capturing || submit.displayContext().firstPerson()) {
			return;
		}
		PoseStack.Pose pose = submit.pose();
		Matrix4f matrix = pose.pose();
		Vector3f pos = new Vector3f();
		Vector3f normal = new Vector3f();
		for (BakedQuad quad : submit.quads()) {
			BakedQuad.MaterialInfo material = quad.materialInfo();
			int tint = material.isTinted() && material.tintIndex() < submit.tintLayers().length
					? submit.tintLayers()[material.tintIndex()] : -1;
			int argb = 0xFF000000 | tint;
			MeshBuilder out = bucket(material.sprite().atlasLocation(), material.layer().translucent() ? Kind.BLEND : Kind.CUTOUT,
					false);
			pose.transformNormal(quad.direction().getUnitVec3f(), normal);
			for (int v = 0; v < 4; v++) {
				Vector3fc p = quad.position(v);
				matrix.transformPosition(p, pos);
				long uv = quad.packedUV(v);
				out.vertex(pos.x, pos.y, pos.z, normal.x, normal.y, normal.z, UVPair.unpackU(uv), UVPair.unpackV(uv), argb);
			}
		}
	}

	/** Entity models: the model is posed and built here as well as by Minecraft right after. */
	public static <S> void model(ModelFeatureRenderer.Submit<S> submit) {
		if (!capturing || submit.sheetedDecalPose() != null) {
			return; // block-breaking overlays have no texture of their own
		}
		RenderType type = submit.renderType();
		Kind kind = modelKind(type);
		Identifier texture = kind != null ? modelTexture(type) : null;
		if (texture == null) {
			return;
		}
		modelSink.out = buckets.computeIfAbsent(new Key(texture, kind, false, true), k -> new MeshBuilder());
		VertexConsumer buffer = submit.uvMapping() != null ? submit.uvMapping().wrap(modelSink) : modelSink;
		modelPose.last().set(submit.pose());
		submit.model().setupAnim(submit.state());
		submit.model().renderToBuffer(modelPose, buffer, submit.lightCoords(), submit.overlayCoords(), submit.tintedColor());
		modelSink.flush();
	}

	/** Null for render types Remix gets nothing useful from: glints, shadows, beams, scrolling energy layers. */
	private static Kind modelKind(RenderType type) {
		String name = ((RenderTypeAccessor) type).rtxremix$name();
		if (name.contains("glint") || name.equals("entity_shadow")) {
			return null;
		}
		if (name.equals("eyes") || name.equals("entity_translucent_emissive")) {
			return Kind.GLOW;
		}
		if (name.startsWith("entity_") || name.equals("armor_cutout_no_cull")) {
			return type.hasBlending() ? Kind.BLEND : Kind.CUTOUT;
		}
		return null;
	}

	private static Identifier modelTexture(RenderType type) {
		Object binding = ((RenderSetupAccessor) (Object) ((RenderTypeAccessor) type).rtxremix$state()).rtxremix$textures()
				.get("Sampler0");
		return binding != null ? ((TextureBindingAccessor) binding).rtxremix$location() : null;
	}

	public static void particles(QuadParticleFeatureRenderer.Submit submit) {
		if (!capturing) {
			return;
		}
		QuadParticleRenderState particles = submit.particles();
		for (SingleQuadParticle.Layer layer : particles.layers()) {
			if (layer.translucent() == submit.translucent()) {
				particleSink.atlas = layer.textureAtlasLocation();
				particleSink.translucent = layer.translucent();
				particles.buildLayer(layer, particleSink);
				particleSink.flush();
			}
		}
	}

	/** Receives entity model vertices, which already carry their posed normals, and files them into one bucket. */
	private static final class ModelSink implements VertexConsumer {
		private MeshBuilder out;
		private boolean pending;
		private float x, y, z, u, v, nx, ny = 1, nz;
		private int color = -1;

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			flush();
			pending = true;
			this.x = x;
			this.y = y;
			this.z = z;
			color = -1;
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			color = a << 24 | r << 16 | g << 8 | b;
			return this;
		}

		@Override
		public VertexConsumer setColor(int argb) {
			color = argb;
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			this.u = u;
			this.v = v;
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			nx = x;
			ny = y;
			nz = z;
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}

		/** Emits the vertex once it is complete: when the next one starts, or the model ends. */
		private void flush() {
			if (pending) {
				out.vertex(x, y, z, nx, ny, nz, u, v, color);
				pending = false;
			}
		}
	}

	/** Receives particle vertices four at a time and files each quad by atlas, blending and brightness. */
	private static final class ParticleSink implements VertexConsumer {
		private final float[] xyz = new float[12];
		private final float[] uv = new float[8];
		private final int[] color = new int[4];
		private int vertex = -1;
		private int blockLight;
		private Identifier atlas;
		private boolean translucent;

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			flush();
			vertex++;
			xyz[vertex * 3] = x;
			xyz[vertex * 3 + 1] = y;
			xyz[vertex * 3 + 2] = z;
			color[vertex] = -1;
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			color[vertex] = a << 24 | r << 16 | g << 8 | b;
			return this;
		}

		@Override
		public VertexConsumer setColor(int argb) {
			color[vertex] = argb;
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			uv[vertex * 2] = u;
			uv[vertex * 2 + 1] = v;
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			blockLight = Math.max(blockLight, u);
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}

		/** Emits the quad once its fourth vertex is complete: when the next one starts, or the layer ends. */
		private void flush() {
			if (vertex < 3) {
				return;
			}
			Kind kind = blockLight >= FULL_BLOCK_LIGHT ? Kind.GLOW : translucent ? Kind.BLEND : Kind.CUTOUT;
			MeshBuilder out = bucket(atlas, kind, true);
			float ax = xyz[3] - xyz[0], ay = xyz[4] - xyz[1], az = xyz[5] - xyz[2];
			float bx = xyz[6] - xyz[0], by = xyz[7] - xyz[1], bz = xyz[8] - xyz[2];
			Vector3f n = new Vector3f(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx);
			if (n.lengthSquared() > 1e-12f) {
				n.normalize();
			} else {
				n.set(0, 1, 0);
			}
			for (int v = 0; v < 4; v++) {
				out.vertex(xyz[v * 3], xyz[v * 3 + 1], xyz[v * 3 + 2], n.x, n.y, n.z, uv[v * 2], uv[v * 2 + 1], color[v]);
			}
			vertex = -1;
			blockLight = 0;
		}
	}
}
