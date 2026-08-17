package software.bernie.geckolib3.renderers.geo;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

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
import software.bernie.geckolib3.util.TextureAlphaDetector;

public interface IGeoRenderer<T> {
	MatrixStack MATRIX_STACK = new MatrixStack();

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
		boolean prevLighting = unlit && GL11.glIsEnabled(GL11.GL_LIGHTING);
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
				GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
			}
			BufferBuilder builder = Tessellator.getInstance().getBuffer();

			// Single traversal: opaque bones are submitted immediately, transparent bones are deferred
			List<TransparentBone> transparentBones = null;
			GlStateManager.depthMask(true);
			builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
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
				builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
				for (TransparentBone transparentBone : transparentBones) {
					submitTransparentBone(builder, transparentBone, red, green, blue);
				}
				Tessellator.getInstance().draw();
				GlStateManager.depthMask(true);
				GlStateManager.disableBlend();
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

		// Snapshot parent-chain matrices so deferred submission re-applies the bone transform
		Matrix4f parentModel = boneAlpha < 1 ? new Matrix4f(MATRIX_STACK.getModelMatrix()) : null;
		Matrix3f parentNormal = boneAlpha < 1 ? new Matrix3f(MATRIX_STACK.getNormalMatrix()) : null;

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
			Vector3f bonePos = new Vector3f(bone.rotationPointX / 16, bone.rotationPointY / 16,
					bone.rotationPointZ / 16);
			MATRIX_STACK.getModelMatrix().transform(bonePos);
			transparentBones.add(new TransparentBone(bone, parentModel, parentNormal, alpha, bonePos.x, bonePos.y,
					bonePos.z));
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

	/** Camera position in model space, from the inverted GL_MODELVIEW matrix. */
	private Vector4f getCameraLocalPosition() {
		FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, buffer);
		float[] gl = new float[16];
		buffer.get(gl);

		// GL matrices are column-major; javax.vecmath expects row-major
		Matrix4f modelview = new Matrix4f();
		modelview.set(new float[] { gl[0], gl[4], gl[8], gl[12], gl[1], gl[5], gl[9], gl[13], gl[2], gl[6], gl[10],
				gl[14], gl[3], gl[7], gl[11], gl[15] });
		modelview.invert();

		Vector4f cameraLocal = new Vector4f(0, 0, 0, 1);
		modelview.transform(cameraLocal);
		return cameraLocal;
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
		final GeoBone bone;
		final Matrix4f model;
		final Matrix3f normal;
		final float alpha;
		final float boneX;
		final float boneY;
		final float boneZ;
		float distanceSq;

		TransparentBone(GeoBone bone, Matrix4f model, Matrix3f normal, float alpha, float boneX, float boneY,
				float boneZ) {
			this.bone = bone;
			this.model = model;
			this.normal = normal;
			this.alpha = alpha;
			this.boneX = boneX;
			this.boneY = boneY;
			this.boneZ = boneZ;
		}
	}

	default void renderCube(BufferBuilder builder, GeoCube cube, float red, float green, float blue, float alpha) {
		MATRIX_STACK.moveToPivot(cube);
		MATRIX_STACK.rotate(cube);
		MATRIX_STACK.moveBackFromPivot(cube);

		for (GeoQuad quad : cube.quads) {
			Vector3f normal = new Vector3f(quad.normal.getX(), quad.normal.getY(), quad.normal.getZ());

			MATRIX_STACK.getNormalMatrix().transform(normal);

			// Fix flat-cube dark shading + Optifine shader compatibility
			if ((cube.size.y == 0 || cube.size.z == 0) && normal.getX() < 0) {
				normal.x *= -1;
			}
			if ((cube.size.x == 0 || cube.size.z == 0) && normal.getY() < 0) {
				normal.y *= -1;
			}
			if ((cube.size.x == 0 || cube.size.y == 0) && normal.getZ() < 0) {
				normal.z *= -1;
			}

			for (GeoVertex vertex : quad.vertices) {
				Vector4f vector4f = new Vector4f(vertex.position.getX(), vertex.position.getY(), vertex.position.getZ(),
						1.0F);

				MATRIX_STACK.getModelMatrix().transform(vector4f);

				builder.pos(vector4f.getX(), vector4f.getY(), vector4f.getZ()).tex(vertex.textureU, vertex.textureV)
						.color(red, green, blue, alpha).normal(normal.getX(), normal.getY(), normal.getZ()).endVertex();
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
