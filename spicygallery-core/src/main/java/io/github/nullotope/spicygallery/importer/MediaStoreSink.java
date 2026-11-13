package io.github.nullotope.spicygallery.importer;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.Status;
import io.github.nullotope.spicygallery.domain.StoredLocation;
import java.util.Optional;

/**
 * Write side of the import pipeline (port, implemented by the server's JDBC store).
 * Every call is its own transaction on the implementing side (spec §5).
 */
public interface MediaStoreSink extends StoreLookup {

    /** Inserts and returns the assigned id. */
    EntryId insert(NewEntry e);

    /** Updates in place, keeping the id (tags later survive re-ingest, spec §5). */
    void update(EntryId id, NewEntry e);

    void setStatus(EntryId id, Status status);

    /** Convenience: find by an entry's location parts. */
    default Optional<StoredLocation> findByEntry(String rootName, String relPath) {
        return find(rootName, relPath);
    }
}
