package io.github.nullotope.spicygallery.importer;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.LibraryRoot;
import io.github.nullotope.spicygallery.domain.ScannedFile;
import io.github.nullotope.spicygallery.domain.Status;
import io.github.nullotope.spicygallery.domain.StoredLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;

/**
 * Walks the configured library roots and streams one {@link ScanOp} per decision
 * (spec §6.2): per root, file ops in deterministic order (sorted entries per directory,
 * depth-first), then the root's stored rows the walk did not see (Missing, relPath order).
 * Dot-names are skipped at every depth. Roots are assumed readable — accessibility is
 * the pipeline's job. Files whose attributes cannot be read are treated as absent.
 */
public final class LibraryScanner {

    private final List<LibraryRoot> roots;
    private final Map<String, Kind> allowlist;

    public LibraryScanner(List<LibraryRoot> roots, Map<String, Kind> allowlist) {
        this.roots = List.copyOf(roots);
        this.allowlist = Map.copyOf(allowlist);
    }

    public void scan(StoreLookup lookup, Consumer<ScanOp> out) throws IOException {
        for (LibraryRoot root : roots) {
            Map<String, EntryId> seen = new HashMap<>();
            walk(root, root.root(), lookup, seen, out);
            Map<String, StoredLocation> storedInRoot = new TreeMap<>(lookup.allInRoot(root.name()));
            for (var entry : storedInRoot.entrySet()) {
                if (!seen.containsKey(entry.getKey())) {
                    out.accept(new ScanOp.Missing(entry.getValue().id()));
                }
            }
        }
    }

    private void walk(LibraryRoot root, Path dir, StoreLookup lookup,
                      Map<String, EntryId> seen, Consumer<ScanOp> out) throws IOException {
        for (Path entry : sortedEntries(dir)) {
            String name = entry.getFileName().toString();
            if (name.startsWith(".")) {
                continue; // files AND directories at any depth
            }
            BasicFileAttributes attrs;
            try {
                // NOFOLLOW: a symlinked directory must never be recursed into (a cycle
                // would StackOverflowError the walk — an Error no error-row path catches,
                // review I3). Symlinks are skipped entirely; libraries are user-controlled
                // but the gallery never promises to follow links.
                attrs = Files.readAttributes(entry, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                continue; // rare: stat failure → treated absent this run
            }
            if (attrs.isSymbolicLink()) {
                continue;
            }
            if (attrs.isDirectory()) {
                walk(root, entry, lookup, seen, out);
                continue;
            }
            String relPath = toRel(root, entry);
            String ext = extOf(name);
            // Filesystem mtimes carry nanoseconds; the store (Postgres timestamptz)
            // keeps microseconds. Truncate here — the single point both the diff
            // comparison and the stored value flow through — or every file with
            // sub-microsecond precision is re-ingested on every run (mtime never
            // compares equal after the roundtrip).
            Instant mtime = attrs.lastModifiedTime().toInstant().truncatedTo(ChronoUnit.MICROS);
            ScannedFile scanned = new ScannedFile(root, relPath, entry,
                    attrs.size(), mtime, ext);
            if (!allowlist.containsKey(ext)) {
                out.accept(new ScanOp.UnknownExt(scanned));
                seen.put(relPath, null);
                continue;
            }
            StoredLocation stored = lookup.find(root.name(), relPath).orElse(null);
            if (stored == null) {
                out.accept(new ScanOp.New(scanned));
                seen.put(relPath, null);
                continue;
            }
            seen.put(relPath, stored.id());
            boolean unchanged = stored.sizeBytes() == attrs.size()
                    && stored.mediaMtime().equals(mtime);
            if (!unchanged || stored.status() == Status.ERROR) {
                out.accept(new ScanOp.Changed(scanned, stored.id()));
            } else if (stored.status() == Status.MISSING) {
                out.accept(new ScanOp.Resurrect(scanned, stored.id()));
            } else {
                out.accept(new ScanOp.Unchanged(stored.id(), scanned));
            }
        }
    }

    /** Sorted-entry directory listing (Linux directory order is arbitrary; the plan pins "path asc").
     *  A directory deleted between listing and walking is tolerated (empty), not fatal. */
    static List<Path> sortedEntries(Path dir) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            stream.forEach(entries::add);
        } catch (java.nio.file.NoSuchFileException e) {
            return entries; // vanished mid-walk — nothing to contribute
        }
        entries.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return entries;
    }

    static String toRel(LibraryRoot root, Path file) {
        String rel = root.root().relativize(file).toString();
        // Windows path separators only — on POSIX a '\' is a legal filename character
        // and converting it would both collide names and plant traversal-shaped
        // rel_paths (review I5); the controller confines the resolved path regardless.
        return java.io.File.separatorChar == '\\' ? rel.replace('\\', '/') : rel;
    }

    static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return ""; // no dot, or a dot-name — no extension
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
