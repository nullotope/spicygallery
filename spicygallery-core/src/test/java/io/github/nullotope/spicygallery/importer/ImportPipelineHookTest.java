package io.github.nullotope.spicygallery.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.ImportResult;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.LibraryRoot;
import io.github.nullotope.spicygallery.domain.Status;
import io.github.nullotope.spicygallery.probe.ImageKindHandler;
import io.github.nullotope.spicygallery.probe.KindHandlers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Post-ingest hook (spec slice-3 §3.2): fires for New/Changed, never fails the import. */
class ImportPipelineHookTest {

    private static final KindHandlers IMAGES =
            new KindHandlers(List.of(new ImageKindHandler(Set.of("png"))));

    private record HookCall(long entryId, String absPath, Kind kind) {}

    private final Map<Long, HookCall> calls = new ConcurrentHashMap<>();
    private volatile boolean failHook;

    private ImportPipeline pipeline(List<LibraryRoot> roots, FakeSink sink, Path appData) {
        PostIngestHook hook = (id, absPath, kind) -> {
            if (failHook) {
                throw new RuntimeException("hook boom");
            }
            calls.put(id.value(), new HookCall(id.value(), absPath.toString(), kind));
        };
        return new ImportPipeline(roots, IMAGES, sink, appData, 1,
                new ImportPipelineTest.RecordingReporter(), hook);
    }

    private Path copyPng(Path lib, String name) throws IOException {
        return Files.copy(Path.of("src/test/resources/fixtures/plain.png"), lib.resolve(name),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void hook_fires_for_new_and_changed_not_unchanged(@TempDir Path tmp) throws Exception {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        FakeSink sink = new FakeSink();
        Path png = copyPng(lib, "a.png");

        pipeline(List.of(new LibraryRoot("lib", lib)), sink, appData).call();
        assertEquals(1, calls.size(), "new ingest fires the hook");
        long id = calls.keySet().iterator().next();

        calls.clear();
        pipeline(List.of(new LibraryRoot("lib", lib)), sink, appData).call();
        assertEquals(0, calls.size(), "unchanged entries do not re-fire");

        // content change (size differs → ScanOp.Changed) re-fires for the SAME entry
        try (var out = Files.newOutputStream(png, StandardOpenOption.APPEND)) {
            out.write(1);
        }
        pipeline(List.of(new LibraryRoot("lib", lib)), sink, appData).call();
        assertEquals(1, calls.size(), "changed ingest re-fires");
        assertEquals(id, calls.keySet().iterator().next(), "same entry id");
    }

    @Test
    void hook_receives_entry_id_and_abs_path(@TempDir Path tmp) throws Exception {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        copyPng(lib, "b.png");
        pipeline(List.of(new LibraryRoot("lib", lib)), new FakeSink(), appData).call();
        HookCall call = calls.values().iterator().next();
        assertEquals(Kind.IMAGE, call.kind());
        assertTrue(call.absPath().endsWith("b.png"), call.absPath());
    }

    @Test
    void hook_failure_does_not_fail_import(@TempDir Path tmp) throws Exception {
        Path lib = Files.createDirectories(tmp.resolve("lib"));
        Path appData = Files.createDirectories(tmp.resolve("appdata"));
        copyPng(lib, "c.png");
        FakeSink sink = new FakeSink();
        failHook = true;
        ImportResult result = pipeline(List.of(new LibraryRoot("lib", lib)), sink, appData).call();
        assertEquals(1, result.added(), "entry still added");
        assertEquals(0, result.errors(), "hook failure is not an import error");
        EntryId id = sink.byPath.get("lib/c.png").id();
        assertEquals(Status.OK, sink.statusOf(id.value()));
    }
}
