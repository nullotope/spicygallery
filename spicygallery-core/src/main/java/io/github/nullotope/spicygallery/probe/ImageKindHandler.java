package io.github.nullotope.spicygallery.probe;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.ProbeResult;
import io.github.nullotope.spicygallery.embed.ImageScale;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

/**
 * Image kind handler (spec §6.3): decodes via ImageIO (TwelveMonkeys registers
 * JPEG/WebP/BMP), applies the EXIF orientation to both the reported dimensions and the
 * thumbnail pixels, and encodes thumbnails as JPEG quality 0.82, max side 512, no upscale.
 */
public final class ImageKindHandler implements KindHandler {

    static final int MAX_SIDE = 512;
    static final float JPEG_QUALITY = 0.82f;

    /** Decode guard (review I7): no dimension beyond this is ever rasterized. */
    static final int MAX_DIMENSION = 30_000;

    private static final Map<String, String> FORMAT_MIME = Map.of(
            "png", "image/png",
            "jpeg", "image/jpeg",
            "jpg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp",
            "tiff", "image/tiff");

    private final Set<String> extensions;

    public ImageKindHandler(Set<String> extensions) {
        this.extensions = Set.copyOf(extensions);
    }

    @Override
    public Kind kind() {
        return Kind.IMAGE;
    }

    @Override
    public Set<String> extensions() {
        return extensions;
    }

    @Override
    public ProbeResult probe(Path file) throws IOException {
        // Header-only pass first (review I7): a file declaring huge dimensions must be
        // rejected from its header — decoding it would allocate gigabytes (OOM), and
        // an OOM is an Error no error-row path can catch.
        String mime;
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("no image reader for: " + file);
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                String format = reader.getFormatName();
                mime = FORMAT_MIME.get(format.toLowerCase(Locale.ROOT));
                if (mime == null) {
                    throw new IOException("unsupported image format '" + format + "': " + file);
                }
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
                    throw new IOException("image dimensions " + w + "×" + h
                            + " exceed the " + MAX_DIMENSION + "px guard (decompression bomb?): "
                            + file);
                }
            } finally {
                reader.dispose();
            }
        }
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new IOException("no image reader could decode: " + file);
        }
        int orientation = exifOrientation(file);
        boolean swapped = orientation >= 5 && orientation <= 8;
        int width = swapped ? img.getHeight() : img.getWidth();
        int height = swapped ? img.getWidth() : img.getHeight();
        return new ProbeResult(width, height, null, mime);
    }

    @Override
    public byte[] thumbnail(Path file, ProbeResult probe) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new IOException("no image reader could decode: " + file);
        }
        BufferedImage oriented = applyOrientation(img, exifOrientation(file));
        BufferedImage scaled = scaleDown(oriented, MAX_SIDE);
        return encodeJpeg(scaled);
    }

    /** EXIF orientation, 1 when absent (EXIF is JPEG-only in practice; others read 1). */
    private static int exifOrientation(Path file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int o = dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                if (o >= 1 && o <= 8) {
                    return o;
                }
            }
        } catch (Exception e) {
            // no EXIF / unreadable metadata → upright
        }
        return 1;
    }

    /** Applies the EXIF orientation to pixels (spec §6.3). */
    static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        return switch (orientation) {
            case 2 -> flipHorizontal(img);
            case 3 -> rotate90(rotate90(img));
            case 4 -> flipVertical(img);
            case 5 -> flipHorizontal(rotate90(img));
            case 6 -> rotate90(img);
            case 7 -> flipVertical(rotate90(img));
            case 8 -> rotate90(rotate90(rotate90(img)));
            default -> img;
        };
    }

    private static BufferedImage rotate90(BufferedImage src) {
        BufferedImage out = compatible(src, src.getHeight(), src.getWidth());
        Graphics2D g = out.createGraphics();
        AffineTransform t = new AffineTransform();
        t.translate(src.getHeight(), 0);
        t.quadrantRotate(1);
        g.drawImage(src, t, null);
        g.dispose();
        return out;
    }

    private static BufferedImage flipHorizontal(BufferedImage src) {
        BufferedImage out = compatible(src, src.getWidth(), src.getHeight());
        Graphics2D g = out.createGraphics();
        g.drawImage(src, src.getWidth(), 0, -src.getWidth(), src.getHeight(), null);
        g.dispose();
        return out;
    }

    private static BufferedImage flipVertical(BufferedImage src) {
        BufferedImage out = compatible(src, src.getWidth(), src.getHeight());
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, src.getHeight(), src.getWidth(), -src.getHeight(), null);
        g.dispose();
        return out;
    }

    private static BufferedImage compatible(BufferedImage src, int w, int h) {
        // JPEG cannot encode alpha; flatten to opaque RGB
        return new BufferedImage(w, h, hasAlpha(src) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    }

    private static boolean hasAlpha(BufferedImage img) {
        return img.getColorModel().hasAlpha();
    }

    static BufferedImage scaleDown(BufferedImage src, int maxSide) {
        return ImageScale.downscale(src, maxSide);
    }

    private static byte[] encodeJpeg(BufferedImage img) throws IOException {
        return ImageScale.encodeJpeg(img);
    }
}
