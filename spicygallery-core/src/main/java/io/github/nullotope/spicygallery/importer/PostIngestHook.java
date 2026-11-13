package io.github.nullotope.spicygallery.importer;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.Kind;
import java.nio.file.Path;

/**
 * Optional post-ingest step (slice-3 spec §3.2): fires after a New/Changed entry's
 * thumbnail is written (the reserved slot after {@code KindHandler.thumbnail}).
 * Implementations must not fail the import — the pipeline swallows + logs.
 */
@FunctionalInterface
public interface PostIngestHook {

    void onIngested(EntryId id, Path absPath, Kind kind);
}
