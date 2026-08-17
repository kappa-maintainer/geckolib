package software.bernie.geckolib3.renderers.geo;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;

import org.lwjgl.opengl.GL11C;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.util.MatrixStack;
import software.bernie.geckolib3.util.LegacyGL;
import software.bernie.geckolib3.util.TextureAlphaDetector;

public interface IGeoRenderer<T> {
	MatrixStack MATRIX_STACK = new MatrixStack();

	// Reusable temporaries for the render pipeline (single-threaded main-thread
	// rendering). Do not retain them across calls.
	Vector3f TEMP_NORMAL = new Vector3f();
	Vector4f TEMP_VERTEX = new Vector4f();
	Vector3f TEMP_BONE_POS = new Vector3f();
	Vector4f CAMERA_POSITION = new Vector4f();

	default void render(GeoModel model, T animatable, float partialTicks, float red, float green, float blue,
			float alpha) {
		//GlStateManager.enableCull();
		GlStateManager.enableRescaleNormal();
		renderEarly(animatable, partialTicks, red, green, blue, alpha);

		renderLate(animatable, partialTicks, red, green, blue, alpha);

		// Alpha-channel textures are cutout-rendered: alpha-tested so transparent pixels don't write depth
		boolean textureHasAlpha = TextureAlphaDetector.hasAlpha(getTextureLocation(animatable));

		// Optional per-renderer lighting overrides (see isUnlit/isEmissive)
		boolean unlit = isUnlit();
		boolean emissive = isEmissive();
		boolean prevLighting = unlit && GL11C.glIsEnabled(LegacyGL.GL_LIGHTING);
		if (unlit) {
			GlStateManager.disableLighting();
		}
		if (emissive) {
			GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
			GlStateManager.disableTexture2D();
			GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
		}

		try {
			if (textureHasAlpha) {
				GlStateManager.enableAlpha();
				GlStateManager.alphaFunc(GL11C.GL_GREATER, 0.1F);
			}
			BufferBuilder builder = Tessellator.getInstance().getBuffer();

			// Single traversal: opaque bones are submitted immediately, transparent bones are deferred
			List<TransparentBone> transparentBones = null;
			GlStateManager.depthMask(true);
			builder.begin(GL11C.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
			for (GeoBone group : model.topLevelBones) {
				transparentBones = renderRecursively(builder, group, red, green, blue, alpha, transparentBones);
			}
			Tessellator.getInstance().draw();

			// Pass 2: deferred transparent bones, no depth write, sorted far-to-near
			if (transparentBones != null) {
				GlStateManager.enableBlend();
				GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
						GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
				Vector4f cameraLocal = getCameraLocalPosition();
				for (TransparentBone transparentBone : transparentBones) {
					float dx = transparentBone.boneX - cameraLocal.x;
					float dy = transparentBone.boneY - cameraLocal.y;
					float dz = transparentBone.boneZ - cameraLocal.z;
					transparentBone.distanceSq = dx * dx + dy * dy + dz * dz;
				}
				transparentBones.sort((a, b) -> Float.compare(b.distanceSq, a.distanceSq));

				GlStateManager.depthMask(false);
				builder.begin(GL11C.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
				for (TransparentBone transparentBone : transparentBones) {
					submitTransparentBone(builder, transparentBone, red, green, blue);
				}
				Tessellator.getInstance().draw();
				GlStateManager.depthMask(true);
				GlStateManager.disableBlend();

				// Return deferred entries (and their matrix snapshots) to the pool
				for (TransparentBone transparentBone : transparentBones) {
					TransparentBone.release(transparentBone);
				}
			}

			if (textureHasAlpha) {
				GlStateManager.disableAlpha();
			}
		} finally {
			if (unlit && prevLighting) {
				GlStateManager.enableLighting();
			}
			if (emissive) {
				GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
				GlStateManager.enableTexture2D();
				GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
			}
		}

		renderAfter(animatable, partialTicks, red, green, blue, alpha);
		GlStateManager.disableRescaleNormal();
	}

	/** Renders a bone subtree immediately (manual invocation only). */
	default void renderRecursively(BufferBuilder builder, GeoBone bone, float red, float green, float blue,
			float alpha) {
		renderRecursively(builder, bone, red, green, blue, alpha, null);
	}

	/** @deprecated Superseded; opaquePass is ignored, subtree submitted immediately. */
	@Deprecated
	default void renderRecursively(BufferBuilder builder, GeoBone bone, float red, float green, float blue,
			float alpha, boolean opaquePass) {
		renderRecursively(builder, bone, red, green, blue, alpha, null);
	}

	/** Single traversal: submits opaque bones, collects transparent ones (matrix snapshot) for deferred pass. */
	default List<TransparentBone> renderRecursively(BufferBuilder builder, GeoBone bone, float red, float green,
			float blue, float alpha, @Nullable List<TransparentBone> transparentBones) {
		float boneAlpha = alpha * bone.getAlpha();
		if (boneAlpha <= 0) {
			return transparentBones;
		}

		MATRIX_STACK.push();

		// Snapshot parent-chain matrices (for deferred submission) before applying this bone's transform
		TransparentBone entry = boneAlpha < 1
				? TransparentBone.acquire(bone, MATRIX_STACK.getModelMatrix(), MATRIX_STACK.getNormalMatrix(), alpha)
				: null;

		MATRIX_STACK.translate(bone);
		MATRIX_STACK.moveToPivot(bone);
		MATRIX_STACK.rotate(bone);
		MATRIX_STACK.scale(bone);
		MATRIX_STACK.moveBackFromPivot(bone);

		if (boneAlpha < 1) {
			// The whole subtree of this bone is transparent: defer it as a single unit
			if (transparentBones == null) {
				transparentBones = new ArrayList<>(4);
			}
			// Model-space position of the bone's pivot, used as the sort reference point
			TEMP_BONE_POS.set(bone.rotationPointX / 16, bone.rotationPointY / 16, bone.rotationPointZ / 16);
			MATRIX_STACK.getModelMatrix().transform(TEMP_BONE_POS);
			entry.boneX = TEMP_BONE_POS.x;
			entry.boneY = TEMP_BONE_POS.y;
			entry.boneZ = TEMP_BONE_POS.z;
			transparentBones.add(entry);
		} else {
			if (!bone.isHidden()) {
				for (GeoCube cube : bone.childCubes) {
					MATRIX_STACK.push();
					GlStateManager.pushMatrix();
					renderCube(builder, cube, red, green, blue, boneAlpha);
					GlStateManager.popMatrix();
					MATRIX_STACK.pop();
				}
			}
			if (!bone.childBonesAreHiddenToo()) {
				for (GeoBone childBone : bone.childBones) {
					transparentBones = renderRecursively(builder, childBone, red, green, blue, boneAlpha,
							transparentBones);
				}
			}
		}

		MATRIX_STACK.pop();
		return transparentBones;
	}

	/**
	 * Camera position in model space, used for transparent-bone distance sorting.
	 * The default is the model origin; renderers override with a model-specific
	 * value. (GL matrix queries are unavailable under Cleanroom's GL context, so
	 * this must be computed from known render data instead of glGetFloatv.)
	 */
	default Vector4f getCameraLocalPosition() {
		CAMERA_POSITION.set(0, 0, 0, 1);
		return CAMERA_POSITION;
	}

	/** Restores a deferred bone's matrix snapshot and submits its subtree. */
	private void submitTransparentBone(BufferBuilder builder, TransparentBone entry, float red, float green,
			float blue) {
		MATRIX_STACK.push();
		MATRIX_STACK.getModelMatrix().set(entry.model);
		MATRIX_STACK.getNormalMatrix().set(entry.normal);
		submitBoneTree(builder, entry.bone, red, green, blue, entry.alpha);
		MATRIX_STACK.pop();
	}

	private void submitBoneTree(BufferBuilder builder, GeoBone bone, float red, float green, float blue,
			float alpha) {
		float boneAlpha = alpha * bone.getAlpha();
		if (boneAlpha <= 0) {
			return;
		}

		MATRIX_STACK.push();

		MATRIX_STACK.translate(bone);
		MATRIX_STACK.moveToPivot(bone);
		MATRIX_STACK.rotate(bone);
		MATRIX_STACK.scale(bone);
		MATRIX_STACK.moveBackFromPivot(bone);

		if (!bone.isHidden()) {
			for (GeoCube cube : bone.childCubes) {
				MATRIX_STACK.push();
				GlStateManager.pushMatrix();
				renderCube(builder, cube, red, green, blue, boneAlpha);
				GlStateManager.popMatrix();
				MATRIX_STACK.pop();
			}
		}
		if (!bone.childBonesAreHiddenToo()) {
			for (GeoBone childBone : bone.childBones) {
				submitBoneTree(builder, childBone, red, green, blue, boneAlpha);
			}
		}

		MATRIX_STACK.pop();
	}

	/** Deferred transparent bone: bone, matrix snapshot, parent alpha, pivot position. */
	final class TransparentBone {
		private static final List<TransparentBone> POOL = new ArrayList<>(16);
		private static final int MAX_POOL_SIZE = 64;

		GeoBone bone;
		Matrix4f model;
		Matrix3f normal;
		float alpha;
		float boneX;
		float boneY;
		float boneZ;
		float distanceSq;

		private TransparentBone() {
			this.model = new Matrix4f();
			this.normal = new Matrix3f();
		}

		/** Copies the parent-chain matrices into a (possibly pooled) entry. */
		static TransparentBone acquire(GeoBone bone, Matrix4f parentModel, Matrix3f parentNormal, float alpha) {
			TransparentBone entry = POOL.isEmpty() ? new TransparentBone() : POOL.remove(POOL.size() - 1);
			entry.bone = bone;
			entry.model.set(parentModel);
			entry.normal.set(parentNormal);
			entry.alpha = alpha;
			return entry;
		}

		/** Returns the entry (and its matrix snapshots) to the pool. */
		static void release(TransparentBone entry) {
			if (POOL.size() < MAX_POOL_SIZE) {
				POOL.add(entry);
			}
		}
	}

	default void renderCube(BufferBuilder builder, GeoCube cube, float red, float green, float blue, float alpha) {
		MATRIX_STACK.moveToPivot(cube);
		MATRIX_STACK.rotate(cube);
		MATRIX_STACK.moveBackFromPivot(cube);

		float[] baked = cube.baked;
		if (baked != null) {
			renderBakedCube(builder, baked, MATRIX_STACK.getModelMatrix(), MATRIX_STACK.getNormalMatrix(), red, green,
					blue, alpha);
			return;
		}

		// Legacy path: cubes not baked (e.g. created outside GeoBuilder)
		for (GeoQuad quad : cube.quads) {
			TEMP_NORMAL.set(quad.normal.getX(), quad.normal.getY(), quad.normal.getZ());
			MATRIX_STACK.getNormalMatrix().transform(TEMP_NORMAL);

			// Fix flat-cube dark shading + Optifine shader compatibility
			if ((cube.size.y == 0 || cube.size.z == 0) && TEMP_NORMAL.getX() < 0) {
				TEMP_NORMAL.x *= -1;
			}
			if ((cube.size.x == 0 || cube.size.z == 0) && TEMP_NORMAL.getY() < 0) {
				TEMP_NORMAL.y *= -1;
			}
			if ((cube.size.x == 0 || cube.size.y == 0) && TEMP_NORMAL.getZ() < 0) {
				TEMP_NORMAL.z *= -1;
			}

			for (GeoVertex vertex : quad.vertices) {
				TEMP_VERTEX.set(vertex.position.getX(), vertex.position.getY(), vertex.position.getZ(), 1.0F);
				MATRIX_STACK.getModelMatrix().transform(TEMP_VERTEX);

				builder.pos(TEMP_VERTEX.getX(), TEMP_VERTEX.getY(), TEMP_VERTEX.getZ())
						.tex(vertex.textureU, vertex.textureV).color(red, green, blue, alpha)
						.normal(TEMP_NORMAL.getX(), TEMP_NORMAL.getY(), TEMP_NORMAL.getZ()).endVertex();
			}
		}
	}

	/**
	 * Fast path: submits a baked cube's interleaved vertex stream
	 * ([x,y,z,u,v,nx,ny,nz] per vertex, 4 vertices per quad) with hand-inlined
	 * matrix transforms. Normals are already flat-cube-fixed at bake time.
	 */
	default void renderBakedCube(BufferBuilder builder, float[] baked, Matrix4f model, Matrix3f normal, float red,
			float green, float blue, float alpha) {
		float m00 = model.m00, m01 = model.m01, m02 = model.m02, m03 = model.m03;
		float m10 = model.m10, m11 = model.m11, m12 = model.m12, m13 = model.m13;
		float m20 = model.m20, m21 = model.m21, m22 = model.m22, m23 = model.m23;
		float n00 = normal.m00, n01 = normal.m01, n02 = normal.m02;
		float n10 = normal.m10, n11 = normal.m11, n12 = normal.m12;
		float n20 = normal.m20, n21 = normal.m21, n22 = normal.m22;

		for (int i = 0; i < baked.length; i += 32) {
			// Quad normal (already flat-cube-fixed) transformed by the 3x3 normal matrix
			float nx = baked[i + 5];
			float ny = baked[i + 6];
			float nz = baked[i + 7];
			float tnx = n00 * nx + n01 * ny + n02 * nz;
			float tny = n10 * nx + n11 * ny + n12 * nz;
			float tnz = n20 * nx + n21 * ny + n22 * nz;

			for (int v = 0; v < 4; v++) {
				int o = i + v * 8;
				float x = baked[o];
				float y = baked[o + 1];
				float z = baked[o + 2];
				builder.pos(m00 * x + m01 * y + m02 * z + m03, m10 * x + m11 * y + m12 * z + m13,
						m20 * x + m21 * y + m22 * z + m23).tex(baked[o + 3], baked[o + 4])
						.color(red, green, blue, alpha).normal(tnx, tny, tnz).endVertex();
			}
		}
	}

	@SuppressWarnings("rawtypes")
	GeoModelProvider getGeoModelProvider();

	ResourceLocation getTextureLocation(T instance);

	/** Whether to render without directional lighting (lightmap still applied). NOTE: no effect under shader mods (OptiFine). */
	default boolean isUnlit() {
		return false;
	}

	/** Whether to render fully bright (lightmap disabled); combine with isUnlit for fully unlit rendering. */
	default boolean isEmissive() {
		return false;
	}

	default void renderEarly(T animatable, float ticks, float red, float green, float blue, float partialTicks) {
	}

	default void renderLate(T animatable, float ticks, float red, float green, float blue, float partialTicks) {
	}

	default void renderAfter(T animatable, float ticks, float red, float green, float blue, float partialTicks) {
	}

	default Color getRenderColor(T animatable, float partialTicks) {
		return Color.ofRGBA(255, 255, 255, 255);
	}

	default Integer getUniqueID(T animatable) {
		return animatable.hashCode();
	}
}
