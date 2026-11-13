package io.github.nullotope.spicygallery.probe;

import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.ProbeResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * The modularity seam of the import pipeline (spec §6.3): one handler per media kind.
 * Adding a kind (audio, 3D models, …) means adding a handler and allowlist entries —
 * the pipeline itself never changes.
 */
public interface KindHandler {

    Kind kind();

    /** Lowercased extensions without the dot, from server config. */
    Set<String> extensions();

    /** Probes metadata; throws {@link IOException} on an unreadable/undecodable file. */
    ProbeResult probe(Path file) throws IOException;

    /** Encoded JPEG thumbnail bytes, max side 512, aspect preserved, no upscale. */
    byte[] thumbnail(Path file, ProbeResult probe) throws IOException;
}
