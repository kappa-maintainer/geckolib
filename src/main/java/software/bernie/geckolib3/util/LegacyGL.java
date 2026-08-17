package software.bernie.geckolib3.util;

/**
 * Fixed-function GL constants that were removed from LWJGL3's GL11C
 * (deprecated/removed in core-profile OpenGL along with the fixed-function
 * pipeline). Values are the standard gl.h enumerants. Compile-time constants,
 * so referencing them has zero runtime cost.
 */
public final class LegacyGL {
	/** GL_LIGHTING 0x0B50 */
	public static final int GL_LIGHTING = 0x0B50;
	/** GL_TEXTURE_ENV 0x2300 */
	public static final int GL_TEXTURE_ENV = 0x2300;
	/** GL_TEXTURE_ENV_MODE 0x2200 */
	public static final int GL_TEXTURE_ENV_MODE = 0x2200;
	/** GL_TEXTURE_ENV_COLOR 0x2201 */
	public static final int GL_TEXTURE_ENV_COLOR = 0x2201;
	/** GL_MODULATE 0x2100 */
	public static final int GL_MODULATE = 0x2100;

	private LegacyGL() {
	}
}
