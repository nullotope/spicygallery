package io.github.nullotope.spicygallery.importer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.LibraryRoot;
import io.github.nullotope.spicygallery.domain.StoredLocation;
import io.github.nullotope.spicygallery.domain.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryScannerTest {

    static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    /** In-memory StoreLookup: keyed by "rootName/relPath", enumerable per root. */
    record FakeStore(Map<String, StoredLocation> byPath) implements StoreLookup {
        @Override
        public Optional<StoredLocation> find(String rootName, String relPath) {
            return Optional.ofNullable(byPath.get(rootName + "/" + relPath));
        }

        @Override
        public Map<String, StoredLocation> allInRoot(String rootName) {
            Map<String, StoredLocation> out = new HashMap<>();
            byPath.forEach((key, value) -> {
                String prefix = rootName + "/";
                if (key.startsWith(prefix)) {
                    out.put(key.substring(prefix.length()), value);
                }
            });
            return out;
        }
    }

    static Map<String, Kind> imageAllowlist() {
        Map<String, Kind> m = new HashMap<>();
        for (String ext : List.of("jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp")) {
            m.put(ext, Kind.IMAGE);
        }
        return m;
    }

    static void setMtime(Path p, Instant t) throws IOException {
        Files.setLastModifiedTime(p, FileTime.from(t));
    }

    /** "New(alpha/a.png)", "Resurrect(alpha/9)", … — root name prefixed for readable assertions. */
    static List<String> describe(List<ScanOp> ops) {
        List<String> out = new ArrayList<>();
        for (ScanOp op : ops) {
            if (op instanceof ScanOp.New n) {
                out.add("New(" + n.file().root().name() + "/" + n.file().relPath() + ")");
            } else if (op instanceof ScanOp.Changed c) {
                out.add("Changed(" + c.file().root().name() + "/" + c.file().relPath() + ")");
            } else if (op instanceof ScanOp.UnknownExt u) {
                out.add("UnknownExt(" + u.file().root().name() + "/" + u.file().relPath() + ")");
            } else if (op instanceof ScanOp.Unchanged u) {
                out.add("Unchanged(" + u.id().value() + ")");
            } else if (op instanceof ScanOp.Resurrect r) {
                out.add("Resurrect(" + r.id().value() + ")");
            } else if (op instanceof ScanOp.Missing m) {
                out.add("Missing(" + m.id().value() + ")");
            }
        }
        return out;
    }

    @Test
    void diff_branches_in_deterministic_order(@TempDir Path tmp) throws Exception {
        Path alpha = Files.createDirectories(tmp.resolve("alpha"));
        Path beta = Files.createDirectories(tmp.resolve("beta"));
        Files.writeString(alpha.resolve("b.png"), "x");            // new
        Path changed = Files.writeString(alpha.resolve("a.png"), "old");
        setMtime(changed, T);
        Files.createDirectories(beta.resolve("sub"));
        Files.writeString(beta.resolve("sub/c.png"), "y");         // new (nested)
        Files.writeString(alpha.resolve(".hidden.png"), "h");      // skipped silently
        Files.writeString(alpha.resolve("noext"), "z");            // unknown ext
        Files.writeString(alpha.resolve("X.JPE"), "j");            // uppercase ext → matched, ext lowercased

        Map<String, StoredLocation> db = new HashMap<>();
        db.put("alpha/a.png", new StoredLocation(new EntryId(7), Kind.IMAGE, "png",
                4, T, Status.OK));                                  // size differs → Changed
        db.put("alpha/gone.png", new StoredLocation(new EntryId(8), Kind.IMAGE, "png",
                1, T, Status.OK));                                  // absent from walk → Missing
        Path back = Files.writeString(alpha.resolve("back.png"), "x");
        setMtime(back, T);
        db.put("alpha/back.png", new StoredLocation(new EntryId(9), Kind.IMAGE, "png",
                1, T, Status.MISSING));                             // resurrect
        Path same = Files.writeString(alpha.resolve("same.png"), "x");
        setMtime(same, T);
        db.put("alpha/same.png", new StoredLocation(new EntryId(10), Kind.IMAGE, "png",
                1, T, Status.OK));                                  // unchanged
        db.put("alpha/err.png", new StoredLocation(new EntryId(11), Kind.IMAGE, "png",
                1, T, Status.ERROR));                               // error always re-ingests

        List<ScanOp> ops = new ArrayList<>();
        new LibraryScanner(
                List.of(new LibraryRoot("alpha", alpha), new LibraryRoot("beta", beta)),
                imageAllowlist())
                .scan(new FakeStore(db), ops::add);

        // deterministic per-root order = sorted entries per directory, code-unit ascending
        // (uppercase X < lowercase a), then Missing ops in relPath order; err.png and
        // gone.png exist only in the DB → Missing; an on-disk error row would re-ingest.
        assertEquals(List.of(
                "New(alpha/X.JPE)",
                "Changed(alpha/a.png)",
                "New(alpha/b.png)",
                "Resurrect(9)",
                "UnknownExt(alpha/noext)",
                "Unchanged(10)",
                "Missing(11)",
                "Missing(8)",
                "New(beta/sub/c.png)"), describe(ops));
        assertTrue(ops.stream().noneMatch(o -> describe(List.of(o)).toString().contains(".hidden")),
                "dot-files must never surface as ops");
    }

    @Test
    void uppercase_ext_is_lowercased(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("alpha"));
        Path file = Files.writeString(root.resolve("Photo.JPG"), "x");
        setMtime(file, T);
        List<ScanOp> ops = new ArrayList<>();
        new LibraryScanner(List.of(new LibraryRoot("alpha", root)), imageAllowlist())
                .scan(new FakeStore(Map.of()), ops::add);
        assertEquals(1, ops.size());
        assertEquals("jpg", ((ScanOp.New) ops.get(0)).file().ext());
    }

    @Test
    void non_utf8_name_walks_without_throwing(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("alpha"));
        Files.writeString(root.resolve("bad\uFFFDname.png"), "x");
        List<ScanOp> ops = new ArrayList<>();
        new LibraryScanner(List.of(new LibraryRoot("alpha", root)), imageAllowlist())
                .scan(new FakeStore(Map.of()), ops::add);
        assertEquals(1, ops.size());
        assertTrue(ops.get(0) instanceof ScanOp.New);
    }

    @Test
    void symlink_cycle_is_skipped_not_followed(@TempDir Path tmp) throws Exception {
        // review I3: a symlinked directory must not be recursed into — a cycle would
        // otherwise StackOverflowError the walk (an Error the run lifecycle can't catch)
        Path root = Files.createDirectories(tmp.resolve("alpha"));
        Path inner = Files.createDirectories(root.resolve("real"));
        Files.writeString(inner.resolve("fine.png"), "x");
        Files.createSymbolicLink(root.resolve("loop"), root);

        List<ScanOp> ops = new ArrayList<>();
        new LibraryScanner(List.of(new LibraryRoot("alpha", root)), imageAllowlist())
                .scan(new FakeStore(Map.of()), ops::add);

        assertEquals(1, ops.size());
        assertEquals("real/fine.png", ((ScanOp.New) ops.get(0)).file().relPath());
    }

    @Test
    void directory_vanishing_mid_walk_is_tolerated(@TempDir Path tmp) throws Exception {
        // minor from review: newDirectoryStream on a concurrently-deleted directory
        // must skip it, not abort the whole run
        Path root = Files.createDirectories(tmp.resolve("alpha"));
        Files.writeString(root.resolve("fine.png"), "x");
        List<ScanOp> ops = new ArrayList<>();
        var scanner = new LibraryScanner(List.of(new LibraryRoot("alpha", root)), imageAllowlist());
        // simulate the deletion racing the walk: remove the directory entirely
        Files.delete(root.resolve("fine.png"));
        Files.delete(root);
        assertDoesNotThrow(() -> LibraryScanner.sortedEntries(root));
        scanner.scan(new FakeStore(Map.of()), ops::add);
        assertTrue(ops.isEmpty());
    }
}
