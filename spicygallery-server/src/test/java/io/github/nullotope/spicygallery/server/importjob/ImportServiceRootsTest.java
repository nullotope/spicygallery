package io.github.nullotope.spicygallery.server.importjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.nullotope.spicygallery.server.config.SpicyGalleryProperties;
import io.github.nullotope.spicygallery.server.store.ImportRunRow;
import io.github.nullotope.spicygallery.server.store.ImportRunStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Service-level root-accessibility guard, without Spring (props vary per case). */
class ImportServiceRootsTest {

    /** Recording run-store fake (no Mockito — JDK 25 breaks the BOM's inline mock maker). */
    static final class FakeRuns implements ImportRunStore {
        int started;

        @Override
        public long start() {
            started++;
            return started;
        }

        @Override
        public void touch(long id, io.github.nullotope.spicygallery.domain.ImportStats s) {}

        @Override
        public void complete(long id, io.github.nullotope.spicygallery.domain.ImportResult r) {}

        @Override
        public void setPoolId(long id, Long poolId) {}

        @Override
        public void fail(long id, String message) {}

        @Override
        public void markInterrupted() {}

        @Override
        public Optional<ImportRunRow> last() {
            return Optional.empty();
        }
    }

    private static ImportService serviceWith(SpicyGalleryProperties props) {
        FakeRuns runs = new FakeRuns();
        return new ImportService(props, runs, ImportRunEvents.NOOP, reporter -> {
            throw new AssertionError("pipeline must not be created");
        }, ids -> {
            throw new AssertionError("follow-up must not run");
        });
    }

    @Test
    void all_roots_inaccessible_throws_before_creating_a_row() {
        SpicyGalleryProperties props = new SpicyGalleryProperties(
                List.of(new SpicyGalleryProperties.Library("gone", "/nonexistent/sg-root")),
                "/tmp/app", "ffmpeg", "ffprobe", 1, List.of("png"), List.of("mp4"), null, null);
        ImportService service = serviceWith(props);
        assertThrows(AllRootsInaccessibleException.class, service::start);
    }

    @Test
    void empty_libraries_throw() {
        SpicyGalleryProperties props = new SpicyGalleryProperties(
                List.of(), "/tmp/app", "ffmpeg", "ffprobe", 1, List.of("png"), List.of("mp4"), null, null);
        ImportService service = serviceWith(props);
        assertThrows(AllRootsInaccessibleException.class, service::start);
    }

    @Test
    void regular_file_root_is_inaccessible() {
        SpicyGalleryProperties props = new SpicyGalleryProperties(
                List.of(new SpicyGalleryProperties.Library("file", "/etc/hostname")),
                "/tmp/app", "ffmpeg", "ffprobe", 1, List.of("png"), List.of("mp4"), null, null);
        ImportService service = serviceWith(props);
        assertThrows(AllRootsInaccessibleException.class, service::start);
    }
}
