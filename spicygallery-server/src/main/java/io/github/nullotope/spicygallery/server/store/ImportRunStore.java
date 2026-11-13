package io.github.nullotope.spicygallery.server.store;

import io.github.nullotope.spicygallery.domain.ImportResult;
import io.github.nullotope.spicygallery.domain.ImportStats;
import java.util.Optional;

/** import_run lifecycle port (spec §5/§8) — implemented by the JDBC store. */
public interface ImportRunStore {

    long start();

    void touch(long id, ImportStats s);

    void complete(long id, ImportResult r);

    void fail(long id, String message);

    /** Records (or clears, via null) the import follow-up's pool link (spec §3.8). */
    void setPoolId(long id, Long poolId);

    void markInterrupted();

    Optional<ImportRunRow> last();
}
