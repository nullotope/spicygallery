package io.github.nullotope.spicygallery.domain;

import java.nio.file.Path;
import java.time.Instant;

/** A file the scanner found on disk. {@code ext} is lowercased without the dot. */
public record ScannedFile(LibraryRoot root, String relPath, Path absPath,
                          long sizeBytes, Instant mtime, String ext) {}
