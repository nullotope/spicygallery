package io.github.nullotope.spicygallery.embed;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Embed-input preparation (spec §2): decode the ORIGINAL, cap its max side (token
 * budget), JPEG-encode. EXIF orientation is applied so a rotated photo embeds upright
 * (same contract as the grid thumbnail). Any decode failure falls back to the entry's
 * grid thumbnail bytes — the embed is best-effort, never an error.
 */
public final class ImagePreparer {

    private ImagePreparer() {
    }

    /** Upright, capped JPEG bytes; {@code fallbackThumb} when the original cannot decode. */
    public static byte[] prepare(Path original, byte[] fallbackThumb, int maxSide) {
        try {
            BufferedImage img = ImageIO.read(original.toFile());
            if (img == null) {
                return fallbackThumb;
            }
            BufferedImage oriented = applyOrientation(img, exifOrientation(original));
            return ImageScale.encodeJpeg(ImageScale.downscale(oriented, maxSide));
        } catch (IOException | RuntimeException e) {
            return fallbackThumb;
        }
    }

    /** EXIF orientation, 1 when absent (same reading as ImageKindHandler). */
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

    private static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return img;
        }
        boolean swap = orientation >= 5 && orientation <= 8;
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        // x' = a*x + c*y + e; y' = b*x + d*y + f — EXIF values 2..8 per spec
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> null;
        };
        if (t != null) {
            g.setTransform(t);
        }
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }
}
