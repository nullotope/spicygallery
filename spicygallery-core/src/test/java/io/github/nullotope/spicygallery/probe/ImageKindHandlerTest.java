package io.github.nullotope.spicygallery.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.ProbeResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageKindHandlerTest {

    static final Path FIX = Path.of("src/test/resources/fixtures");
    static final ImageKindHandler H = new ImageKindHandler(
            Set.of("jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp"));

    @Test
    void probes_png() throws IOException {
        ProbeResult r = H.probe(FIX.resolve("plain.png"));
        assertEquals(new ProbeResult(8, 4, null, "image/png"), r);
    }

    @Test
    void probes_exif_orientation_applied() throws IOException {
        // rot90.jpg is stored 8x4 with EXIF Orientation=6 → displayed 4x8
        ProbeResult r = H.probe(FIX.resolve("rot90.jpg"));
        assertEquals(4, r.width());
        assertEquals(8, r.height());
        assertEquals("image/jpeg", r.mime());
    }

    @Test
    void thumbnail_caps_and_keeps_aspect() throws IOException {
        Path wide = FIX.resolve("wide.jpg"); // 1024x512
        java.awt.image.BufferedImage t = ImageIO.read(new ByteArrayInputStream(
                H.thumbnail(wide, H.probe(wide))));
        assertEquals(512, Math.max(t.getWidth(), t.getHeight()));
        assertTrue(Math.abs((double) t.getWidth() / t.getHeight() - 2.0) < 0.01);

        Path tall = FIX.resolve("tall.png"); // 300x900
        java.awt.image.BufferedImage t2 = ImageIO.read(new ByteArrayInputStream(
                H.thumbnail(tall, H.probe(tall))));
        assertEquals(512, Math.max(t2.getWidth(), t2.getHeight()));
        assertTrue(t2.getHeight() == 512 && t2.getWidth() < 200);
    }

    @Test
    void thumbnail_never_upscales_and_applies_orientation() throws IOException {
        Path rot = FIX.resolve("rot90.jpg"); // stored 8x4, displayed 4x8
        java.awt.image.BufferedImage t = ImageIO.read(new ByteArrayInputStream(
                H.thumbnail(rot, H.probe(rot))));
        assertEquals(4, t.getWidth()); // not 8x4 — orientation applied to pixels
        assertEquals(8, t.getHeight());
    }

    @Test
    void corrupt_image_throws() throws IOException {
        byte[] truncated = Arrays.copyOf(Files.readAllBytes(FIX.resolve("plain.jpg")), 50);
        Path bad = Files.write(Path.of("target/corrupt.jpg"), truncated);
        assertThrows(IOException.class, () -> H.probe(bad));
    }

    @Test
    void webp_and_bmp_probe() throws IOException {
        assertEquals(8, H.probe(FIX.resolve("tiny.webp")).width());
        assertEquals(8, H.probe(FIX.resolve("tiny.bmp")).width());
    }

    /** Minimal PNG with a valid signature + IHDR declaring 40000×40000 and no data. */
    private static byte[] pngBombHeader(int width, int height) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        java.nio.ByteBuffer chunk = java.nio.ByteBuffer.allocate(4 + 4 + 13);
        chunk.putInt(13).put("IHDR".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        chunk.putInt(width).putInt(height);
        chunk.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        byte[] typeAndData = chunk.array();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeAndData, 4, 17);
        out.writeBytes(typeAndData);
        java.nio.ByteBuffer crcBytes = java.nio.ByteBuffer.allocate(4);
        crcBytes.putInt((int) crc.getValue());
        out.writeBytes(crcBytes.array());
        return out.toByteArray();
    }

    @Test
    void decompression_bomb_header_is_rejected_before_decode() throws IOException {
        // review I7: a file declaring huge dimensions must be rejected from its
        // header — decoding 40000×40000 would try to allocate gigabytes (OOM)
        Path bomb = Files.write(Path.of("target/bomb.png"), pngBombHeader(40000, 40000));
        assertThrows(IOException.class, () -> H.probe(bomb));
    }

    @Test
    void gif_probe() throws IOException {
        ProbeResult r = H.probe(FIX.resolve("tiny.gif"));
        assertEquals(8, r.width());
        assertEquals("image/gif", r.mime());
    }
}
