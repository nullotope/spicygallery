package io.github.nullotope.spicygallery.embed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Embed-input preparation (spec §2): original decode, max-side cap, thumb fallback. */
class ImagePreparerTest {

    private static final Path FIX = Path.of("src", "test", "resources", "fixtures");

    private static BufferedImage image(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static Path writePng(BufferedImage img, Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    @Test
    void small_image_passes_through_no_upscale(@TempDir Path dir) throws IOException {
        Path src = writePng(image(64, 32), dir, "small.png");
        byte[] out = ImagePreparer.prepare(src, new byte[0], 1024);
        BufferedImage decoded = decode(out);
        assertEquals(64, decoded.getWidth());
        assertEquals(32, decoded.getHeight());
    }

    @Test
    void large_image_is_capped_to_max_side(@TempDir Path dir) throws IOException {
        Path src = writePng(image(2048, 1024), dir, "large.png");
        BufferedImage decoded = decode(ImagePreparer.prepare(src, new byte[0], 1024));
        assertEquals(1024, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
    }

    @Test
    void undecodable_bytes_fall_back_to_thumb(@TempDir Path dir) throws IOException {
        Path bad = dir.resolve("garbage.png");
        Files.write(bad, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        byte[] thumb = {9, 9, 9};
        byte[] out = ImagePreparer.prepare(bad, thumb, 1024);
        assertArrayEquals(thumb, out);
    }

    @Test
    void animated_gif_yields_first_frame(@TempDir Path dir) throws IOException {
        // real 2-frame animated GIF via the ImageIO sequence writer
        Path gif = dir.resolve("anim.gif");
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var out = new MemoryCacheImageOutputStream(new ByteArrayOutputStream())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new javax.imageio.IIOImage(image(30, 20), null, null), null);
            writer.writeToSequence(new javax.imageio.IIOImage(image(30, 20), null, null), null);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        // re-read what we wrote to a file (the stream writer needs an output stream — redo via file)
        BufferedImage first = image(30, 20);
        ImageWriter w2 = ImageIO.getImageWritersByFormatName("gif").next();
        try (var fos = Files.newOutputStream(gif);
             var out2 = new MemoryCacheImageOutputStream(fos)) {
            w2.setOutput(out2);
            w2.prepareWriteSequence(null);
            w2.writeToSequence(new javax.imageio.IIOImage(first, null, null), null);
            w2.writeToSequence(new javax.imageio.IIOImage(image(30, 20), null, null), null);
            w2.endWriteSequence();
        } finally {
            w2.dispose();
        }

        BufferedImage decoded = decode(ImagePreparer.prepare(gif, new byte[0], 1024));
        assertEquals(30, decoded.getWidth());
        assertEquals(20, decoded.getHeight());
    }

    @Test
    void exif_orientation_applied_to_pixels() throws IOException {
        // rot90.jpg is stored 8x4 with EXIF Orientation=6 → displayed 4x8 (thumb path applies it;
        // the embed input must see the same upright pixels)
        BufferedImage decoded = decode(ImagePreparer.prepare(FIX.resolve("rot90.jpg"), new byte[0], 1024));
        assertEquals(4, decoded.getWidth());
        assertEquals(8, decoded.getHeight());
    }

    @Test
    void jpg_fixture_still_preparable_and_not_identical_to_thumb() throws IOException {
        // sanity: a real fixture decodes and the fallback is NOT what comes back
        byte[] thumb = {1};
        byte[] out = ImagePreparer.prepare(FIX.resolve("plain.jpg"), thumb, 1024);
        assertNotEquals(1, out.length);
    }
}
