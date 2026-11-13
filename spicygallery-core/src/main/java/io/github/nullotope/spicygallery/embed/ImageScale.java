package io.github.nullotope.spicygallery.embed;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Shared raster plumbing (extracted from ImageKindHandler in slice 3): bilinear
 * no-upscale downscale and JPEG q0.82 encoding with white alpha-matting. One copy of
 * the constants exists — the grid thumb and the embed input look the same by design.
 */
public final class ImageScale {

    /** Matches ImageKindHandler's thumbnail quality — visual parity for embeds. */
    public static final float JPEG_QUALITY = 0.82f;

    private ImageScale() {
    }

    /** Scales down to fit maxSide; never upscales; source returned as-is when small. */
    public static BufferedImage downscale(BufferedImage src, int maxSide) {
        int max = Math.max(src.getWidth(), src.getHeight());
        if (max <= maxSide) {
            return src;
        }
        double scale = (double) maxSide / max;
        int w = (int) Math.round(src.getWidth() * scale);
        int h = (int) Math.round(src.getHeight() * scale);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    public static byte[] encodeJpeg(BufferedImage img) throws IOException {
        BufferedImage rgb = img.getType() == BufferedImage.TYPE_INT_RGB ? img : flatten(img);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 15);
        try (MemoryCacheImageOutputStream imageOut = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(imageOut);
            writer.write(null, new IIOImage(rgb, null, null), params);
            imageOut.flush();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static BufferedImage flatten(BufferedImage src) {
        // matte onto white — transparent pixels must not turn black
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }
}
