package software.bernie.geckolib3.renderers.geo;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;

import javax.vecmath.Vector4f;

import com.eliotlash.mclib.utils.Interpolations;
import com.google.common.collect.Lists;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.opengl.GL11C;
import software.bernie.geckolib3.util.LegacyGL;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.AnimatedGeoModel;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.model.provider.data.EntityModelData;
import software.bernie.geckolib3.util.AnimationUtils;

@SuppressWarnings({ "rawtypes", "unchecked" })
public abstract class GeoEntityRenderer<T extends EntityLivingBase & IAnimatable> extends Render<T>
		implements IGeoRenderer<T> {
	static {
		AnimationController.addModelFetcher((IAnimatable object) -> {
			if (object instanceof Entity entity) {
				return (IAnimatableModel<Object>) AnimationUtils.getGeoModelForEntity(entity);
			}
			return null;
		});
	}

	protected final AnimatedGeoModel<T> modelProvider;
	protected final List<GeoLayerRenderer<T>> layerRenderers = Lists.newArrayList();
	private final FloatBuffer brightnessBuffer = GLAllocation.createDirectFloatBuffer(4);
	private static final DynamicTexture TEXTURE_BRIGHTNESS = new DynamicTexture(16, 16);

	public GeoEntityRenderer(RenderManager renderManager, AnimatedGeoModel<T> modelProvider) {
		super(renderManager);
		this.modelProvider = modelProvider;
	}

	// Last render's translation (relative to the camera), for transparent-bone sorting
	private double lastX, lastY, lastZ;

	/**
	 * Camera position in model space for transparent-bone sorting. Translation
	 * only (entity yaw/pitch rotation is ignored); close enough for distance
	 * sorting since the error stays within the model's own scale.
	 */
	@Override
	public Vector4f getCameraLocalPosition() {
		CAMERA_POSITION.set((float) -this.lastX, (float) -this.lastY, (float) -this.lastZ, 1);
		return CAMERA_POSITION;
	}

	public void doRender(T entity, double x, double y, double z, float entityYaw, float partialTicks) {
		this.lastX = x;
		this.lastY = y;
		this.lastZ = z;
		GlStateManager.pushMatrix();
		GlStateManager.translate(x, y, z);
		// TODO: entity.isPassenger() looks redundant here
		boolean shouldSit = /* entity.isPassenger() && */ (entity.getRidingEntity() != null
				&& entity.getRidingEntity().shouldRiderSit());
		EntityModelData entityModelData = new EntityModelData();
		entityModelData.isSitting = shouldSit;
		entityModelData.isChild = entity.isChild();

		float f = Interpolations.lerpYaw(entity.prevRenderYawOffset, entity.renderYawOffset, partialTicks);
		float f1 = Interpolations.lerpYaw(entity.prevRotationYawHead, entity.rotationYawHead, partialTicks);
		float netHeadYaw = f1 - f;
		if (shouldSit && entity.getRidingEntity() instanceof EntityLivingBase livingentity) {
            f = Interpolations.lerpYaw(livingentity.prevRenderYawOffset, livingentity.renderYawOffset, partialTicks);
			netHeadYaw = f1 - f;
			float f3 = MathHelper.wrapDegrees(netHeadYaw);
			if (f3 < -85.0F) {
				f3 = -85.0F;
			}

			if (f3 >= 85.0F) {
				f3 = 85.0F;
			}

			f = f1 - f3;
			if (f3 * f3 > 2500.0F) {
				f += f3 * 0.2F;
			}

			netHeadYaw = f1 - f;
		}

		float headPitch = Interpolations.lerp(entity.prevRotationPitch, entity.rotationPitch, partialTicks);
		/*
		 * TODO: vanilla mobs can't sleep in beds in 1.12.2 and below if
		 * (entity.getPose() == Pose.SLEEPING) { Direction direction =
		 * entity.getBedDirection(); if (direction != null) { float f4 =
		 * entity.getEyeHeight(Pose.STANDING) - 0.1F; stack.translate((double) ((float)
		 * (-direction.getXOffset()) * f4), 0.0D, (double) ((float)
		 * (-direction.getZOffset()) * f4)); } }
		 */
		float f7 = this.handleRotationFloat(entity, partialTicks);
		this.applyRotations(entity, f7, f, partialTicks);

		float limbSwingAmount = 0.0F;
		float limbSwing = 0.0F;
		if (!shouldSit && entity.isEntityAlive()) {
			limbSwingAmount = Interpolations.lerp(entity.prevLimbSwingAmount, entity.limbSwingAmount, partialTicks);
			limbSwing = entity.limbSwing - entity.limbSwingAmount * (1.0F - partialTicks);
			if (entity.isChild()) {
				limbSwing *= 3.0F;
			}

			if (limbSwingAmount > 1.0F) {
				limbSwingAmount = 1.0F;
			}
		}
		entityModelData.headPitch = -headPitch;
		entityModelData.netHeadYaw = -netHeadYaw;

		AnimationEvent predicate = new AnimationEvent(entity, limbSwing, limbSwingAmount, partialTicks,
				!(limbSwingAmount > -0.15F && limbSwingAmount < 0.15F), Collections.singletonList(entityModelData));
		GeoModel model = modelProvider.getModel(modelProvider.getModelLocation(entity));
        modelProvider.setLivingAnimations(entity, this.getUniqueID(entity), predicate);

        GlStateManager.pushMatrix();
		GlStateManager.translate(0, 0.01f, 0);
		Minecraft.getMinecraft().renderEngine.bindTexture(getEntityTexture(entity));
		Color renderColor = getRenderColor(entity, partialTicks);
		
		boolean flag = setBrightness(entity, partialTicks, true);

		if (!entity.isInvisibleToPlayer(Minecraft.getMinecraft().player))
			render(model, entity, partialTicks, (float) renderColor.getRed() / 255f,
					(float) renderColor.getBlue() / 255f, (float) renderColor.getGreen() / 255f,
					(float) renderColor.getAlpha() / 255);

		if (!(entity instanceof EntityPlayer) || !((EntityPlayer) entity).isSpectator()) {
			for (GeoLayerRenderer<T> layerRenderer : this.layerRenderers) {
				layerRenderer.render(entity, limbSwing, limbSwingAmount, partialTicks, limbSwing, netHeadYaw, headPitch,
						renderColor);
			}
		}
		if (entity instanceof EntityLiving) {
			Entity leashHolder = ((EntityLiving) entity).getLeashHolder();
			if (leashHolder != null) {
				this.renderLeash((EntityLiving) entity, x, y, z, entityYaw, partialTicks);
			}
		}

		if (flag) {
			unsetBrightness();
		}

		GlStateManager.popMatrix();
		GlStateManager.popMatrix();
	}

	@Override
	public ResourceLocation getEntityTexture(T entity) {
		return getTextureLocation(entity);
	}

	@Override
	public GeoModelProvider getGeoModelProvider() {
		return this.modelProvider;
	}

	protected void applyRotations(T entityLiving, float ageInTicks, float rotationYaw, float partialTicks) {
		if (!entityLiving.isPlayerSleeping()) {
			GlStateManager.rotate(180.0F - rotationYaw, 0, 1, 0);
		}

		if (entityLiving.deathTime > 0) {
			float f = ((float) entityLiving.deathTime + partialTicks - 1.0F) / 20.0F * 1.6F;
			f = MathHelper.sqrt(f);
			if (f > 1.0F) {
				f = 1.0F;
			}

			GlStateManager.rotate(f * this.getDeathMaxRotation(entityLiving), 0, 0, 1);
		}
		/*
		 * TODO: probably doesn't exist in 1.12.2 as well else if
		 * (entityLiving.isSpinAttacking()) {
		 * matrixStackIn.rotate(Vector3f.XP.rotationDegrees(-90.0F -
		 * entityLiving.rotationPitch));
		 * matrixStackIn.rotate(Vector3f.YP.rotationDegrees(((float)
		 * entityLiving.ticksExisted + partialTicks) * -75.0F)); } else if (pose ==
		 * Pose.SLEEPING) { Direction direction = entityLiving.getBedDirection(); float
		 * f1 = direction != null ? getFacingAngle(direction) : rotationYaw;
		 * matrixStackIn.rotate(Vector3f.YP.rotationDegrees(f1));
		 * matrixStackIn.rotate(Vector3f.ZP.rotationDegrees(this.getDeathMaxRotation(
		 * entityLiving))); matrixStackIn.rotate(Vector3f.YP.rotationDegrees(270.0F)); }
		 */
		else if (entityLiving.hasCustomName() || entityLiving instanceof EntityPlayer) {
			String s = TextFormatting.getTextWithoutFormattingCodes(entityLiving.getName());
			if (("Dinnerbone".equals(s) || "Grumm".equals(s)) && (!(entityLiving instanceof EntityPlayer)
					|| ((EntityPlayer) entityLiving).isWearing(EnumPlayerModelParts.CAPE))) {
				GlStateManager.translate(0.0D, (double) (entityLiving.height + 0.1F), 0.0D);
				GlStateManager.rotate(180, 0, 0, 1);
			}
		}
	}

	protected boolean isVisible(T livingEntityIn) {
		return !livingEntityIn.isInvisible();
	}

	@SuppressWarnings("unused")
	private static float getFacingAngle(EnumFacing facingIn) {
		switch (facingIn) {
		case SOUTH:
			return 90.0F;
		case WEST:
			return 0.0F;
		case NORTH:
			return 270.0F;
		case EAST:
			return 180.0F;
		default:
			return 0.0F;
		}
	}

	@Override
	public Integer getUniqueID(T animatable) {
		return animatable.getUniqueID().hashCode();
	}

	protected float getDeathMaxRotation(T entityLivingBaseIn) {
		return 90.0F;
	}

	/**
	 * Returns where in the swing animation the living entity is (from 0 to 1). Args
	 * : entity, partialTickTime
	 */
	protected float getSwingProgress(T livingBase, float partialTickTime) {
		return livingBase.getSwingProgress(partialTickTime);
	}

	/**
	 * Defines what float the third param in setRotationAngles of ModelBase is
	 */
	protected float handleRotationFloat(T livingBase, float partialTicks) {
		return (float) livingBase.ticksExisted + partialTicks;
	}

	@Override
	public ResourceLocation getTextureLocation(T instance) {
		return this.modelProvider.getTextureLocation(instance);
	}

	public final boolean addLayer(GeoLayerRenderer<T> layer) {
		return this.layerRenderers.add(layer);
	}

    @Deprecated
    protected boolean setDoRenderBrightness(T entityLivingBaseIn, float partialTicks) {
        return RenderHurtColor.set(entityLivingBaseIn, partialTicks);
    }

	protected void renderLeash(EntityLiving entityLivingIn, double x, double y, double z, float entityYaw,
			float partialTicks) {
		Entity entity = entityLivingIn.getLeashHolder();

		if (entity != null) {
			y = y - (1.6D - (double) entityLivingIn.height) * 0.5D;
			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder bufferbuilder = tessellator.getBuffer();
			double d0 = this.interpolateValue(entity.prevRotationYaw, entity.rotationYaw,
                partialTicks * 0.5F) * Math.PI / 180.0;
			double d1 = this.interpolateValue(entity.prevRotationPitch, entity.rotationPitch,
                partialTicks * 0.5F) * Math.PI / 180.0;
			double d2 = Math.cos(d0);
			double d3 = Math.sin(d0);
			double d4 = Math.sin(d1);

			if (entity instanceof EntityHanging) {
				d2 = 0.0D;
				d3 = 0.0D;
				d4 = -1.0D;
			}

			double d5 = Math.cos(d1);
			double d6 = this.interpolateValue(entity.prevPosX, entity.posX, partialTicks) - d2 * 0.7D
					- d3 * 0.5D * d5;
			double d7 = this.interpolateValue(entity.prevPosY + (double) entity.getEyeHeight() * 0.7D,
					entity.posY + (double) entity.getEyeHeight() * 0.7D, partialTicks) - d4 * 0.5D - 0.25D;
			double d8 = this.interpolateValue(entity.prevPosZ, entity.posZ, partialTicks) - d3 * 0.7D
					+ d2 * 0.5D * d5;
			double d9 = this.interpolateValue(entityLivingIn.prevRenderYawOffset,
					entityLivingIn.renderYawOffset, partialTicks) * Math.PI / 180.0
					+ (Math.PI / 2D);
			d2 = Math.cos(d9) * (double) entityLivingIn.width * 0.4D;
			d3 = Math.sin(d9) * (double) entityLivingIn.width * 0.4D;
			double d10 = this.interpolateValue(entityLivingIn.prevPosX, entityLivingIn.posX, partialTicks)
					+ d2;
			double d11 = this.interpolateValue(entityLivingIn.prevPosY, entityLivingIn.posY, partialTicks);
			double d12 = this.interpolateValue(entityLivingIn.prevPosZ, entityLivingIn.posZ, partialTicks)
					+ d3;
			x = x + d2;
			z = z + d3;
			double d13 = (float) (d6 - d10);
			double d14 = (float) (d7 - d11);
			double d15 = (float) (d8 - d12);
			GlStateManager.disableTexture2D();
			GlStateManager.disableLighting();
			GlStateManager.disableCull();
			bufferbuilder.begin(5, DefaultVertexFormats.POSITION_COLOR);

			for (int j = 0; j <= 24; ++j) {
				float f = 0.5F;
				float f1 = 0.4F;
				float f2 = 0.3F;

				if (j % 2 == 0) {
					f *= 0.7F;
					f1 *= 0.7F;
					f2 *= 0.7F;
				}

				float f3 = (float) j / 24.0F;
				bufferbuilder
						.pos(x + d13 * (double) f3 + 0.0D,
								y + d14 * (double) (f3 * f3 + f3) * 0.5D
										+ (double) ((24.0F - (float) j) / 18.0F + 0.125F),
								z + d15 * (double) f3)
						.color(f, f1, f2, 1.0F).endVertex();
				bufferbuilder
						.pos(x + d13 * (double) f3 + 0.025D,
								y + d14 * (double) (f3 * f3 + f3) * 0.5D
										+ (double) ((24.0F - (float) j) / 18.0F + 0.125F) + 0.025D,
								z + d15 * (double) f3)
						.color(f, f1, f2, 1.0F).endVertex();
			}

			tessellator.draw();
			bufferbuilder.begin(5, DefaultVertexFormats.POSITION_COLOR);

			for (int k = 0; k <= 24; ++k) {
				float f4 = 0.5F;
				float f5 = 0.4F;
				float f6 = 0.3F;

				if (k % 2 == 0) {
					f4 *= 0.7F;
					f5 *= 0.7F;
					f6 *= 0.7F;
				}

				float f7 = (float) k / 24.0F;
				bufferbuilder
						.pos(x + d13 * (double) f7 + 0.0D,
								y + d14 * (double) (f7 * f7 + f7) * 0.5D
										+ (double) ((24.0F - (float) k) / 18.0F + 0.125F) + 0.025D,
								z + d15 * (double) f7)
						.color(f4, f5, f6, 1.0F).endVertex();
				bufferbuilder.pos(x + d13 * (double) f7 + 0.025D,
						y + d14 * (double) (f7 * f7 + f7) * 0.5D + (double) ((24.0F - (float) k) / 18.0F + 0.125F),
						z + d15 * (double) f7 + 0.025D).color(f4, f5, f6, 1.0F).endVertex();
			}

			tessellator.draw();
			GlStateManager.enableLighting();
			GlStateManager.enableTexture2D();
			GlStateManager.enableCull();
		}
	}

	private double interpolateValue(double start, double end, double pct) {
		return start + (end - start) * pct;
	}

	public boolean setBrightness(T entity, float partialTicks, boolean combineTextures)
	{
        boolean flag1 = entity.hurtTime > 0 || entity.deathTime > 0;

		if (!flag1)
		{
			return false;
		}
		else if (!combineTextures)
		{
			return false;
		}
		else
		{
			GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
			GlStateManager.enableTexture2D();
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, LegacyGL.GL_TEXTURE_ENV_MODE, OpenGlHelper.GL_COMBINE);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, LegacyGL.GL_MODULATE);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, OpenGlHelper.defaultTexUnit);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB, OpenGlHelper.GL_PRIMARY_COLOR);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, GL11C.GL_SRC_COLOR);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB, GL11C.GL_SRC_COLOR);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, GL11C.GL_REPLACE);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, OpenGlHelper.defaultTexUnit);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, GL11C.GL_SRC_ALPHA);
			GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
			GlStateManager.enableTexture2D();
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, LegacyGL.GL_TEXTURE_ENV_MODE, OpenGlHelper.GL_COMBINE);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, OpenGlHelper.GL_INTERPOLATE);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, OpenGlHelper.GL_CONSTANT);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB, OpenGlHelper.GL_PREVIOUS);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE2_RGB, OpenGlHelper.GL_CONSTANT);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, GL11C.GL_SRC_COLOR);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB, GL11C.GL_SRC_COLOR);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND2_RGB, GL11C.GL_SRC_ALPHA);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, GL11C.GL_REPLACE);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, OpenGlHelper.GL_PREVIOUS);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, GL11C.GL_SRC_ALPHA);
			((Buffer)this.brightnessBuffer).position(0);

            this.brightnessBuffer.put(1.0F);
            this.brightnessBuffer.put(0.0F);
            this.brightnessBuffer.put(0.0F);
            this.brightnessBuffer.put(0.3F);

            ((Buffer)this.brightnessBuffer).flip();
			GlStateManager.glTexEnv(LegacyGL.GL_TEXTURE_ENV, LegacyGL.GL_TEXTURE_ENV_COLOR, this.brightnessBuffer);
			GlStateManager.setActiveTexture(OpenGlHelper.GL_TEXTURE2);
			GlStateManager.enableTexture2D();
			GlStateManager.bindTexture(TEXTURE_BRIGHTNESS.getGlTextureId());
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, LegacyGL.GL_TEXTURE_ENV_MODE, OpenGlHelper.GL_COMBINE);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, LegacyGL.GL_MODULATE);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, OpenGlHelper.GL_PREVIOUS);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB, OpenGlHelper.lightmapTexUnit);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, GL11C.GL_SRC_COLOR);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB, GL11C.GL_SRC_COLOR);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, GL11C.GL_REPLACE);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, OpenGlHelper.GL_PREVIOUS);
			GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, GL11C.GL_SRC_ALPHA);
			GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
			return true;
		}
	}

	public void unsetBrightness()
	{
		GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
		GlStateManager.enableTexture2D();
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, LegacyGL.GL_TEXTURE_ENV_MODE, OpenGlHelper.GL_COMBINE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, LegacyGL.GL_MODULATE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, OpenGlHelper.defaultTexUnit);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB, OpenGlHelper.GL_PRIMARY_COLOR);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, GL11C.GL_SRC_COLOR);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB, GL11C.GL_SRC_COLOR);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, LegacyGL.GL_MODULATE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, OpenGlHelper.defaultTexUnit);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_ALPHA, OpenGlHelper.GL_PRIMARY_COLOR);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, GL11C.GL_SRC_ALPHA);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_ALPHA, GL11C.GL_SRC_ALPHA);
		GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, LegacyGL.GL_TEXTURE_ENV_MODE, OpenGlHelper.GL_COMBINE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, LegacyGL.GL_MODULATE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, GL11C.GL_SRC_COLOR);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB, GL11C.GL_SRC_COLOR);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, GL11C.GL_TEXTURE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB, OpenGlHelper.GL_PREVIOUS);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, LegacyGL.GL_MODULATE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, GL11C.GL_SRC_ALPHA);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, GL11C.GL_TEXTURE);
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		GlStateManager.setActiveTexture(OpenGlHelper.GL_TEXTURE2);
		GlStateManager.disableTexture2D();
		GlStateManager.bindTexture(0);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, LegacyGL.GL_TEXTURE_ENV_MODE, OpenGlHelper.GL_COMBINE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, LegacyGL.GL_MODULATE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, GL11C.GL_SRC_COLOR);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB, GL11C.GL_SRC_COLOR);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, GL11C.GL_TEXTURE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB, OpenGlHelper.GL_PREVIOUS);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, LegacyGL.GL_MODULATE);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, GL11C.GL_SRC_ALPHA);
		GlStateManager.glTexEnvi(LegacyGL.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, GL11C.GL_TEXTURE);
		GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
	}
}
