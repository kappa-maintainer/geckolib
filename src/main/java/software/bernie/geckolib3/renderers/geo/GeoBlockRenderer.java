package software.bernie.geckolib3.renderers.geo;


import javax.vecmath.Vector4f;

import net.minecraft.block.BlockDirectional;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.AnimatedGeoModel;

@SuppressWarnings({ "unchecked" })
public abstract class GeoBlockRenderer<T extends TileEntity & IAnimatable> extends TileEntitySpecialRenderer<T>
		implements IGeoRenderer<T> {
	static {
		AnimationController.addModelFetcher((IAnimatable object) -> {
			if (object instanceof TileEntity tile) {
                TileEntitySpecialRenderer<TileEntity> renderer = TileEntityRendererDispatcher.instance
						.getRenderer(tile);
				if (renderer instanceof GeoBlockRenderer) {
					return (IAnimatableModel<Object>) ((GeoBlockRenderer<?>) renderer).getGeoModelProvider();
				}
			}
			return null;
		});
	}

	private final AnimatedGeoModel<T> modelProvider;

	// Last render's translation (relative to the camera) and facing; used to
	// compute the camera position in model space for transparent-bone sorting
	private double lastX, lastY, lastZ;
	private EnumFacing lastFacing;

	public GeoBlockRenderer(AnimatedGeoModel<T> modelProvider) {
		this.modelProvider = modelProvider;
	}

	@Override
	public void render(T te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
		this.render(te, x, y, z, partialTicks, destroyStage);
	}

	public void render(T tile, double x, double y, double z, float partialTicks, int destroyStage) {
		GeoModel model = modelProvider.getModel(modelProvider.getModelLocation(tile));
		modelProvider.setLivingAnimations(tile, this.getUniqueID(tile));

		int light = tile.getWorld().getCombinedLight(tile.getPos(), 0);
		int lx = light % 65536;
		int ly = light / 65536;

		GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
		// NOTE: the target must be the lightmap texture unit (GL_TEXTURE1 = 33985), not
		// GL_TEXTURE_2D — glMultiTexCoord treats the first argument as a texture unit
		// enum, so passing 3553 makes the call a no-op (GL_INVALID_ENUM) and the
		// lightmap coords stay at their previous value, freezing the model's brightness
		OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lx, ly);
		GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);

		this.lastX = x;
		this.lastY = y;
		this.lastZ = z;
		this.lastFacing = getFacing(tile);

		GlStateManager.pushMatrix();
		GlStateManager.translate(x, y, z);
		GlStateManager.translate(0, 0.01f, 0);
		GlStateManager.translate(0.5, 0, 0.5);

		rotateBlock(getFacing(tile));

		Minecraft.getMinecraft().renderEngine.bindTexture(getTextureLocation(tile));
		Color renderColor = getRenderColor(tile, partialTicks);
		render(model, tile, partialTicks, (float) renderColor.getRed() / 255f, (float) renderColor.getGreen() / 255f,
				(float) renderColor.getBlue() / 255f, (float) renderColor.getAlpha() / 255);
		GlStateManager.popMatrix();
	}

	@Override
	public Vector4f getCameraLocalPosition() {
		// M_model = T(x+0.5, y+0.01, z+0.5) * R(facing); the camera sits at the view
		// origin, so camera-in-model-space = R^-1 * (-(x+0.5), -(y+0.01), -(z+0.5)),
		// matching the translate/rotateBlock sequence in render()
		float dx = (float) -(this.lastX + 0.5);
		float dy = (float) -(this.lastY + 0.01);
		float dz = (float) -(this.lastZ + 0.5);
		float radY = 0.0F;
		float radX = 0.0F;
		switch (this.lastFacing) {
		case SOUTH:
			radY = (float) Math.PI;
			break;
		case WEST:
			radY = (float) (Math.PI / 2);
			break;
		case EAST:
			radY = (float) (3 * Math.PI / 2);
			break;
		case UP:
			radX = (float) (Math.PI / 2);
			break;
		case DOWN:
			radX = (float) (-Math.PI / 2);
			break;
		default:
			break; // NORTH: no rotation
		}
		if (radY != 0.0F) {
			float cos = (float) Math.cos(radY);
			float sin = (float) Math.sin(radY);
			float x2 = dx * cos - dz * sin;
			dz = dx * sin + dz * cos;
			dx = x2;
		} else if (radX != 0.0F) {
			float cos = (float) Math.cos(radX);
			float sin = (float) Math.sin(radX);
			float y2 = dy * cos + dz * sin;
			dz = -dy * sin + dz * cos;
			dy = y2;
		}
		CAMERA_POSITION.set(dx, dy, dz, 1);
		return CAMERA_POSITION;
	}

	@Override
	public AnimatedGeoModel<T> getGeoModelProvider() {
		return this.modelProvider;
	}

	protected void rotateBlock(EnumFacing facing) {
		switch (facing) {
		case SOUTH:
			GlStateManager.rotate(180, 0, 1, 0);
			break;
		case WEST:
			GlStateManager.rotate(90, 0, 1, 0);
			break;
		case NORTH:
			/* There is no need to rotate by 0 */
			break;
		case EAST:
			GlStateManager.rotate(270, 0, 1, 0);
			break;
		case UP:
			GlStateManager.rotate(90, 1, 0, 0);
			break;
		case DOWN:
			GlStateManager.rotate(90, -1, 0, 0);
			break;
		}
	}

	private EnumFacing getFacing(T tile) {
		IBlockState blockState = tile.getWorld().getBlockState(tile.getPos());

		if (blockState.getPropertyKeys().contains(BlockHorizontal.FACING)) {
			return blockState.getValue(BlockHorizontal.FACING);
		} else if (blockState.getPropertyKeys().contains(BlockDirectional.FACING)) {
			return blockState.getValue(BlockDirectional.FACING);
		} else {
			return EnumFacing.NORTH;
		}
	}

	@Override
	public ResourceLocation getTextureLocation(T instance) {
		return this.modelProvider.getTextureLocation(instance);
	}
}
