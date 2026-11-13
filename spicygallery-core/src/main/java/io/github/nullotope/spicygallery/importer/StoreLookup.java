package io.github.nullotope.spicygallery.importer;

import io.github.nullotope.spicygallery.domain.StoredLocation;
import java.util.Map;
import java.util.Optional;

/** Read side the scanner diffs against (implemented by the store; extended by MediaStoreSink). */
public interface StoreLookup {

    Optional<StoredLocation> find(String rootName, String relPath);

    /** All stored locations under one library root, keyed by relPath — Missing detection. */
    Map<String, StoredLocation> allInRoot(String rootName);
}
