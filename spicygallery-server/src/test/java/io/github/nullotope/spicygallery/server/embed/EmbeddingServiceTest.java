package io.github.nullotope.spicygallery.server.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.embed.FrameExtractor;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.server.PostgresBase;
import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.EmbeddingStore;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** EmbeddingService (spec §3.2): kind-aware inputs, batching, breaker, dim guard. */
@SpringBootTest
class EmbeddingServiceTest extends PostgresBase {

    @Autowired JdbcClient jdbc;
    @Autowired EmbeddingStore store;

    private final RecordingProvider provider = new RecordingProvider();
    private final List<FrameExtractor.VideoFrame> stubFrames = new ArrayList<>();
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void clean() {
        jdbc.sql("delete from media_embedding").update();
        jdbc.sql("delete from media_entry").update();
        provider.reset();
        stubFrames.clear();
        clock.set(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private EmbeddingService service(Path appData, SpicyGalleryProperties.Embedding cfg) {
        return new EmbeddingService(provider, store, cfg, appData,
                (video, workDir, tools, maxFrames) -> List.copyOf(stubFrames), clock);
    }

    private long entry(String name) {
        return jdbc.sql("""
                        insert into media_entry (kind, status, root_name, rel_path, file_name, ext, mime,
                                                 size_bytes, content_hash, media_mtime, imported_at, updated_at)
                        values ('image', 'ok', 'api', :p, :p, 'png', 'image/png', 1, :h,
                                timestamptz '2026-01-01T00:00:00Z', now(), now())
                        returning id
                        """)
                .param("p", name)
                .param("h", "hash-" + name)
                .query(Long.class).single();
    }

    private static Path realPng(Path dir, String name) throws IOException {
        BufferedImage img = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.RED);
        g.fillRect(0, 0, 16, 8);
        g.dispose();
        Path p = dir.resolve(name);
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    static final class RecordingProvider implements EmbeddingProvider {
        final List<List<EmbeddingProvider.EmbeddingInput>> calls = new ArrayList<>();
        Function<EmbeddingProvider.EmbeddingInput, float[]> answer =
                in -> seven68();
        boolean fail;

        static float[] seven68() {
            float[] v = new float[768];
            v[0] = 1;
            return v;
        }

        void reset() {
            calls.clear();
            answer = in -> seven68();
            fail = false;
        }

        @Override
        public List<float[]> embed(List<EmbeddingProvider.EmbeddingInput> inputs) throws IOException {
            calls.add(List.copyOf(inputs));
            if (fail) {
                throw new IOException("provider down");
            }
            return inputs.stream().map(answer).toList();
        }
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void set(Instant i) {
            now = i;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void image_entry_embeds_one_row_with_model_tag(@TempDir Path dir) throws IOException {
        long id = entry("img.png");
        Path original = realPng(dir, "img.png");
        service(dir, cfg(3)).embedEntry(id, original, Kind.IMAGE);
        assertTrue(store.hasForModel(id, "embeddinggemma-2"));
        assertEquals(1, jdbc.sql("select count(*) from media_embedding where media_entry_id = :id")
                .param("id", id).query(Integer.class).single());
        // the embed input was the IMAGE bytes, not text
        assertEquals(1, provider.calls.size());
        assertEquals(null, provider.calls.get(0).get(0).text());
        assertTrue(provider.calls.get(0).get(0).image().length > 0);
    }

    @Test
    void video_entry_embeds_one_row_per_frame(@TempDir Path dir) throws IOException {
        long id = entry("vid.mp4");
        stubFrames.add(new FrameExtractor.VideoFrame(0, 0L, new byte[] {(byte) 0xFF, (byte) 0xD8}));
        stubFrames.add(new FrameExtractor.VideoFrame(1, 1000L, new byte[] {(byte) 0xFF, (byte) 0xD8}));
        service(dir, cfg(8)).embedEntry(id, dir.resolve("missing.mp4"), Kind.VIDEO);
        assertEquals(2, jdbc.sql("select count(*) from media_embedding where media_entry_id = :id")
                .param("id", id).query(Integer.class).single());
        // frames persisted for slice 4
        assertTrue(Files.isDirectory(dir.resolve("frames").resolve(String.valueOf(id))));
    }

    @Test
    void chunks_into_batch_size_calls(@TempDir Path dir) throws IOException {
        long id = entry("vid2.mp4");
        for (int i = 0; i < 3; i++) {
            stubFrames.add(new FrameExtractor.VideoFrame(i, i * 1000L, new byte[] {(byte) 0xFF}));
        }
        service(dir, cfg(2)).embedEntry(id, dir.resolve("missing.mp4"), Kind.VIDEO);
        assertEquals(2, provider.calls.size());
        assertEquals(2, provider.calls.get(0).size());
        assertEquals(1, provider.calls.get(1).size());
    }

    @Test
    void reembed_replaces_rows(@TempDir Path dir) throws IOException {
        long id = entry("img2.png");
        Path original = realPng(dir, "img2.png");
        EmbeddingService svc = service(dir, cfg(8));
        svc.embedEntry(id, original, Kind.IMAGE);
        svc.embedEntry(id, original, Kind.IMAGE);
        assertEquals(1, jdbc.sql("select count(*) from media_embedding where media_entry_id = :id")
                .param("id", id).query(Integer.class).single());
    }

    @Test
    void breaker_opens_and_skips_for_cooldown(@TempDir Path dir) throws IOException {
        long id = entry("img3.png");
        Path original = realPng(dir, "img3.png");
        provider.fail = true;
        EmbeddingService svc = service(dir, cfg(8));
        assertThrows(UncheckedIOException.class, () -> svc.embedEntry(id, original, Kind.IMAGE));
        assertFalse(svc.breakerClosed());

        // within cooldown: immediate skip, no provider call
        EmbeddingUnavailableException e = assertThrows(EmbeddingUnavailableException.class,
                () -> svc.embedEntry(id, original, Kind.IMAGE));
        assertTrue(e.getMessage().contains("unreachable"));

        // after cooldown: retry allowed
        clock.advance(Duration.ofMinutes(6));
        assertTrue(svc.breakerClosed());
    }

    @Test
    void video_extraction_failure_is_local_not_provider(@TempDir Path dir) throws IOException {
        long id = entry("vid3.mp4");
        // extractor seam failing the way a corrupt video / ffmpeg timeout would
        EmbeddingService svc = new EmbeddingService(provider, store, cfg(8), dir,
                (video, workDir, tools, maxFrames) -> {
                    throw new IOException("ffmpeg exploded");
                }, clock);
        // Review Focus 4 + Task 6 ruling: a LOCAL failure never trips the PROVIDER
        // breaker, never errors callers — the entry stays un-embedded, backfill retries.
        svc.embedEntry(id, dir.resolve("missing.mp4"), Kind.VIDEO);
        assertTrue(svc.breakerClosed(), "breaker must stay closed for local failures");
        assertFalse(store.hasForModel(id, "embeddinggemma-2"));
        assertEquals(0, provider.calls.size());
    }

    @Test
    void text_cache_survives_concurrent_queries(@TempDir Path dir) throws Exception {
        // the cache lives on Tomcat request threads: two overlapping similar: misses
        // must never corrupt it (the old double-LinkedHashMap raced on put/evict).
        // Provider fake is stateless/thread-safe so ONLY the cache is under test;
        // 480 distinct phrases > 128 keeps the eviction path under constant pressure.
        EmbeddingService svc = new EmbeddingService(
                inputs -> inputs.stream().map(in -> RecordingProvider.seven68()).toList(),
                store, cfg(8), dir,
                (video, workDir, tools, maxFrames) -> List.of(), clock);
        int threads = 12;
        int perThread = 40;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.Queue<Exception> failures = new java.util.concurrent.ConcurrentLinkedQueue<>();
        var start = new java.util.concurrent.CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        svc.textVector("phrase-" + tid + "-" + i);
                    }
                } catch (Exception e) {
                    failures.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), "concurrent textVector failed: "
                + failures.stream().limit(3).map(Object::toString).toList());
        // and a cached phrase still resolves after the storm
        assertEquals(768, svc.textVector("phrase-0-0").length);
    }

    @Test
    void dimension_mismatch_is_loud(@TempDir Path dir) throws IOException {
        long id = entry("img4.png");
        Path original = realPng(dir, "img4.png");
        provider.answer = in -> new float[] {1, 2, 3}; // 3 dims ≠ 768
        EmbeddingService svc = service(dir, cfg(8));
        assertThrows(IllegalStateException.class, () -> svc.embedEntry(id, original, Kind.IMAGE));
        assertFalse(store.hasForModel(id, "embeddinggemma-2")); // nothing stored
    }

    @Test
    void disabled_config_is_noop(@TempDir Path dir) throws IOException {
        long id = entry("img5.png");
        Path original = realPng(dir, "img5.png");
        SpicyGalleryProperties.Embedding cfg = new SpicyGalleryProperties.Embedding(
                false, "http://x", "m", null, 8, 64, 1024, 300L, 60L);
        service(dir, cfg).embedEntry(id, original, Kind.IMAGE);
        assertEquals(0, provider.calls.size());
        assertFalse(store.hasForModel(id, "m"));
    }

    private static SpicyGalleryProperties.Embedding cfg(int batchSize) {
        return new SpicyGalleryProperties.Embedding(
                true, "http://embedding:8080/v1", "embeddinggemma-2", null, batchSize, 64, 1024, 300L, 60L);
    }
}
