package software.bernie.geckolib3.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

/**
 * Detects whether a texture contains an alpha channel by inspecting the PNG header,
 * caching the result per {@link ResourceLocation}.
 * <p>
 * The check is conservative: any parse failure, non-PNG format, or unknown color type
 * reports {@code true} (may be transparent), prioritising correct blending over performance.
 * <p>
 * PNG color types: 0 = grayscale, 2 = RGB, 3 = palette, 4 = grayscale+alpha, 6 = RGBA.
 * Palette images additionally need a {@code tRNS} chunk to carry transparency.
 */
public final class TextureAlphaDetector {
	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
	private static final Map<ResourceLocation, Boolean> CACHE = new ConcurrentHashMap<>();

	private TextureAlphaDetector() {
	}

	public static boolean hasAlpha(ResourceLocation location) {
		return CACHE.computeIfAbsent(location, TextureAlphaDetector::detect);
	}

	private static boolean detect(ResourceLocation location) {
		try (InputStream stream = Minecraft.getMinecraft().getResourceManager().getResource(location)
				.getInputStream()) {
			byte[] header = new byte[33]; // 8 signature + 25 IHDR chunk (4 len + 4 type + 13 data + 4 crc)
			int read = 0;
			while (read < header.length) {
				int n = stream.read(header, read, header.length - read);
				if (n < 0) {
					break;
				}
				read += n;
			}
			if (read < header.length) {
				return true;
			}
			for (int i = 0; i < PNG_SIGNATURE.length; i++) {
				if (header[i] != PNG_SIGNATURE[i]) {
					return true; // Not a PNG (jpg/tga/...): assume potentially transparent
				}
			}

			switch (header[25] & 0xFF) {
			case 4: // Grayscale + alpha
			case 6: // RGBA
				return true;
			case 0: // Grayscale
			case 2: // RGB
				return false;
			case 3: // Palette: transparency only via tRNS chunk
				return hasPaletteTransparency(location);
			default:
				return true;
			}
		}
		catch (IOException e) {
			return true;
		}
	}

	private static boolean hasPaletteTransparency(ResourceLocation location) {
		try (InputStream stream = Minecraft.getMinecraft().getResourceManager().getResource(location)
				.getInputStream()) {
			// Stream is positioned right after the 33-byte IHDR chunk; scan the chunk chain
			byte[] block = new byte[8];
			for (int i = 0; i < 12; i++) {
				if (stream.read(block) != 8) {
					return true;
				}
				int length = ((block[0] & 0xFF) << 24) | ((block[1] & 0xFF) << 16) | ((block[2] & 0xFF) << 8)
						| (block[3] & 0xFF);
				String type = new String(block, 4, 4, StandardCharsets.US_ASCII);
				if ("tRNS".equals(type)) {
					return true;
				}
				if ("IEND".equals(type)) {
					return false;
				}
				if (stream.skip(4L + length) < 4L + length) {
					return true;
				}
			}
			return true;
		}
		catch (IOException e) {
			return true;
		}
	}
}
