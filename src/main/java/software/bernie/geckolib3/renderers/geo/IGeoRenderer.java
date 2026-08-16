package software.bernie.geckolib3.renderers.geo;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
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

public interface IGeoRenderer<T> {
	MatrixStack MATRIX_STACK = new MatrixStack();

	default void render(GeoModel model, T animatable, float partialTicks, float red, float green, float blue,
			float alpha) {
		//GlStateManager.enableCull();
		GlStateManager.enableRescaleNormal();
		renderEarly(animatable, partialTicks, red, green, blue, alpha);

		renderLate(animatable, partialTicks, red, green, blue, alpha);

		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
		BufferBuilder builder = Tessellator.getInstance().getBuffer();

		// Single traversal: opaque bones are submitted immediately, transparent bones are deferred
		List<TransparentBone> transparentBones = null;
		GlStateManager.depthMask(true);
		builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
		for (GeoBone group : model.topLevelBones) {
			transparentBones = renderRecursively(builder, group, red, green, blue, alpha, transparentBones);
		}
		Tessellator.getInstance().draw();

		// Pass 2: submit deferred transparent bones without depth writing so they don't occlude
		if (transparentBones != null) {
			GlStateManager.depthMask(false);
			builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
			for (TransparentBone transparentBone : transparentBones) {
				submitTransparentBone(builder, transparentBone, red, green, blue);
			}
			Tessellator.getInstance().draw();
			GlStateManager.depthMask(true);
		}

		GlStateManager.disableBlend();
		renderAfter(animatable, partialTicks, red, green, blue, alpha);
		GlStateManager.disableRescaleNormal();
	}

	/**
	 * Renders a bone subtree immediately, including transparent bones. Only intended
	 * for external/manual invocation; the standard render pipeline uses the
	 * collector overload.
	 */
	default void renderRecursively(BufferBuilder builder, GeoBone bone, float red, float green, float blue,
			float alpha) {
		renderRecursively(builder, bone, red, green, blue, alpha, null);
	}

	/**
	 * @deprecated Superseded by the single-traversal render pipeline. The
	 *             {@code opaquePass} argument is ignored; the bone subtree is
	 *             submitted immediately with the given alpha.
	 */
	@Deprecated
	default void renderRecursively(BufferBuilder builder, GeoBone bone, float red, float green, float blue,
			float alpha, boolean opaquePass) {
		renderRecursively(builder, bone, red, green, blue, alpha, null);
	}

	/**
	 * Traverses the bone tree once. Opaque bones (alpha == 1) are submitted to the
	 * builder immediately; transparent bones (alpha &lt; 1) are collected with a
	 * matrix snapshot for deferred submission (see {@link #submitTransparentBone}).
	 *
	 * @param transparentBones the collector list, or {@code null} to submit everything immediately
	 * @return the (possibly newly created) collector list
	 */
	default List<TransparentBone> renderRecursively(BufferBuilder builder, GeoBone bone, float red, float green,
			float blue, float alpha, @Nullable List<TransparentBone> transparentBones) {
		float boneAlpha = alpha * bone.getAlpha();
		if (boneAlpha <= 0) {
			return transparentBones;
		}

		MATRIX_STACK.push();

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
			transparentBones.add(new TransparentBone(bone, new Matrix4f(MATRIX_STACK.getModelMatrix()),
					new Matrix3f(MATRIX_STACK.getNormalMatrix()), alpha));
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
	 * Submits a deferred transparent bone: restores its matrix snapshot and draws
	 * its entire subtree with the accumulated alpha.
	 */
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
	}

	/**
	 * A deferred transparent bone: bone reference, matrix snapshot at collection
	 * time, and the parent alpha to multiply down the subtree.
	 */
	final class TransparentBone {
		final GeoBone bone;
		final Matrix4f model;
		final Matrix3f normal;
		final float alpha;

		TransparentBone(GeoBone bone, Matrix4f model, Matrix3f normal, float alpha) {
			this.bone = bone;
			this.model = model;
			this.normal = normal;
			this.alpha = alpha;
		}
	}

	default void renderCube(BufferBuilder builder, GeoCube cube, float red, float green, float blue, float alpha) {
		MATRIX_STACK.moveToPivot(cube);
		MATRIX_STACK.rotate(cube);
		MATRIX_STACK.moveBackFromPivot(cube);

		for (GeoQuad quad : cube.quads) {
			Vector3f normal = new Vector3f(quad.normal.getX(), quad.normal.getY(), quad.normal.getZ());

			MATRIX_STACK.getNormalMatrix().transform(normal);

			/*
			 * Fix shading dark shading for flat cubes + compatibility wish Optifine shaders
			 */
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
