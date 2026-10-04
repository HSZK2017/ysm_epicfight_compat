package com.ysmef.compat.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.ysmef.compat.YSMEpicFightCompat;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** CPU-only image format detection, decoding and alpha scanning. */
final class TextureDecoder {

    /**
     * OpenYSM/ModernYSM ship the ImageStream decoders (WebP/AVIF) inside their
     * jar (jar-in-jar). The compat mod cannot compile against them (the libs
     * YSM jar is obfuscated), so they are looked up reflectively at runtime.
     * Null when a legacy fork without ImageStream is installed.
     */
    private static final Class<?> YSM_WEBP_DECODER_CLASS = findImageStreamDecoder("rip.ysm.imagestream.webp.WebpDecoder");
    private static final Class<?> YSM_AVIF_DECODER_CLASS = findImageStreamDecoder("rip.ysm.imagestream.avif.AvifDecoder");
    private static final Map<Class<?>, Method> IMAGE_STREAM_READ_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> IMAGE_STREAM_CTORS = new ConcurrentHashMap<>();
    private static volatile boolean IMAGE_STREAM_MISSING_LOGGED = false;
    private static volatile boolean IMAGE_STREAM_FAILED_LOGGED = false;

    private TextureDecoder() {}

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /**
     * One-time full scan of the decoded texture for translucent pixels
     * (alpha &lt; 253). Runs on the texture decode worker thread (see
     * ensureTextureUploaded) - every pixel is checked, because a strided
     * sampling misses small translucent regions (hair strands, gradients) and
     * the GPU path's first pass then discards them (alphaMode == 1).
     */
    static boolean hasTranslucentPixels(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (((image.getPixelRGBA(x, y) >>> 24) & 0xFF) < 253) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Decode texture bytes into a NativeImage, supporting PNG/JPEG encoded data,
     * WebP/AVIF (through YSM's ImageStream, reflectively) and raw RGBA pixels
     * (legacy .ysm binary textures).
     *
     * Uses the InputStream-based read: NativeImage.read(byte[]) copies the whole
     * array onto the 64KB LWJGL MemoryStack, which overflows for large textures
     * ("Out of stack space"), while the InputStream overload buffers off-heap.
     */
    static NativeImage decodeTexture(ResourceLocation rl, byte[] data, int[] info) throws IOException {
        if (data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return NativeImage.read(new ByteArrayInputStream(data));
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return NativeImage.read(new ByteArrayInputStream(data));
        }
        if (isRiffWebp(data)) {
            return decodeWithImageStream(YSM_WEBP_DECODER_CLASS, data, "WebP");
        }
        if (isFtypAvif(data)) {
            return decodeWithImageStream(YSM_AVIF_DECODER_CLASS, data, "AVIF");
        }

        if (info != null && info[2] == -1) {
            return readRawRgba(data, info[0], info[1]);
        }

        if (data.length % 4 == 0) {
            int pixels = data.length / 4;
            int side = (int) Math.round(Math.sqrt(pixels));
            if ((long) side * side == pixels) {
                return readRawRgba(data, side, side);
            }
        }
        YSMEpicFightCompat.LOGGER.warn("YSM-EF Compat: unsupported texture format for {}", rl);
        return null;
    }

    /** Decode formats that must be re-encoded before entering the generated pack. */
    static NativeImage decodePackTexture(byte[] data, int[] info) throws IOException {
        if (isRiffWebp(data)) {
            return decodeWithImageStream(YSM_WEBP_DECODER_CLASS, data, "WebP");
        }
        if (isFtypAvif(data)) {
            return decodeWithImageStream(YSM_AVIF_DECODER_CLASS, data, "AVIF");
        }
        return readRawRgba(data, info != null ? info[0] : 0, info != null ? info[1] : 0);
    }

    /**
     * Interpret the bytes as raw RGBA pixels (YSM legacy texture format) and
     * build a NativeImage (Minecraft packs pixels as ABGR).
     */
    private static NativeImage readRawRgba(byte[] data, int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height * 4 > data.length) {
            int side = (int) Math.round(Math.sqrt(data.length / 4.0));
            width = side;
            height = side;
        }
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int r = data[i] & 0xFF;
                int g = data[i + 1] & 0xFF;
                int b = data[i + 2] & 0xFF;
                int a = data[i + 3] & 0xFF;
                image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return image;
    }

    private static Class<?> findImageStreamDecoder(String className) {
        try {
            return Class.forName(className, false, TextureDecoder.class.getClassLoader());
        } catch (Throwable ignored) {
            try {
                ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
                return contextLoader != null
                        ? Class.forName(className, false, contextLoader)
                        : Class.forName(className);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    static boolean isRiffWebp(byte[] data) {
        return data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    static boolean isFtypAvif(byte[] data) {
        return data.length >= 12
                && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p';
    }

    /**
     * Decode WebP/AVIF with OpenYSM/ModernYSM's ImageStream decoders
     * (rip.ysm.imagestream.*). Those classes are loaded from the YSM jar at
     * runtime; reflection keeps the compat mod buildable against the obfuscated
     * release jar and still works on LegacyYSM when the classes are absent
     * (returns null and the model keeps its mesh with an untextured/fallback
     * texture instead of crashing).
     */
    private static NativeImage decodeWithImageStream(Class<?> decoderClass, byte[] data, String formatName) {
        if (decoderClass == null) {
            // Some YSM forks register ImageStream as an ImageIO plugin rather
            // than exposing the decoder class directly.
            try {
                BufferedImage imageIoImage = ImageIO.read(new ByteArrayInputStream(data));
                if (imageIoImage != null) {
                    return bufferedImageToNative(imageIoImage);
                }
            } catch (Throwable ignored) {
            }
            if (!IMAGE_STREAM_MISSING_LOGGED) {
                IMAGE_STREAM_MISSING_LOGGED = true;
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: {} texture found but the YSM ImageStream decoder is not available; "
                                + "the model will render without this texture", formatName);
            }
            return null;
        }
        try {
            Method read = IMAGE_STREAM_READ_METHODS.get(decoderClass);
            if (read == null) {
                for (Method candidate : decoderClass.getMethods()) {
                    if ("read".equals(candidate.getName())
                            && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0] == byte[].class
                            && BufferedImage.class.isAssignableFrom(candidate.getReturnType())) {
                        read = candidate;
                        break;
                    }
                }
                if (read == null) {
                    if (!IMAGE_STREAM_FAILED_LOGGED) {
                        IMAGE_STREAM_FAILED_LOGGED = true;
                        YSMEpicFightCompat.LOGGER.warn(
                                "YSM-EF Compat: cannot find read(byte[]) on {}; {} textures will be skipped",
                                decoderClass.getName(), formatName);
                    }
                    return null;
                }
                IMAGE_STREAM_READ_METHODS.put(decoderClass, read);
            }
            Constructor<?> ctor = IMAGE_STREAM_CTORS.get(decoderClass);
            if (ctor == null) {
                ctor = decoderClass.getDeclaredConstructor();
                IMAGE_STREAM_CTORS.put(decoderClass, ctor);
            }
            Object image = read.invoke(ctor.newInstance(), (Object) data);
            return image instanceof BufferedImage bufferedImage
                    ? bufferedImageToNative(bufferedImage)
                    : null;
        } catch (Throwable t) {
            if (!IMAGE_STREAM_FAILED_LOGGED) {
                IMAGE_STREAM_FAILED_LOGGED = true;
                YSMEpicFightCompat.LOGGER.warn(
                        "YSM-EF Compat: failed to decode {} texture with {}", formatName, decoderClass.getName(), t);
            }
            return null;
        }
    }

    /** Convert an ImageStream BufferedImage to Minecraft's ABGR NativeImage. */
    private static NativeImage bufferedImageToNative(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, width, height, true);
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                int a = (pixel >>> 24) & 0xFF;
                int r = (pixel >>> 16) & 0xFF;
                int g = (pixel >>> 8) & 0xFF;
                int b = pixel & 0xFF;
                // NativeImage packs pixels as ABGR.
                out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return out;
    }

}
