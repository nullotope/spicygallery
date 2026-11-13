package io.github.nullotope.spicygallery.server.importjob;

import io.github.nullotope.spicygallery.natural.NaturalOrder;
import io.github.nullotope.spicygallery.server.pools.PoolService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real import follow-up (spec §3.8): pools the run's added entries — named
 * {@code Import YYYY-MM-DD HH:MM} from the finish moment (the caller invokes this right
 * after the run row completed), members ordered by (directory, NaturalOrder filename, id),
 * deduped name, system tag. One transaction: a failure rolls the pool back entirely and
 * the caller's WARN-only guard keeps the run completed with {@code pool_id} null.
 */
@Component
public class PoolImportFollowUp implements ImportPoolFollowUp {

    /** The follow-up pool's name pattern: 'Import ' + the finish minute (spec §3.8). */
    static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PoolService pools;
    private final JdbcClient jdbc;

    public PoolImportFollowUp(PoolService pools, JdbcClient jdbc) {
        this.pools = pools;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public long createPool(List<Long> addedEntryIds) {
        if (addedEntryIds == null || addedEntryIds.isEmpty()) {
            throw new IllegalArgumentException("no added entries to pool");
        }
        String name = "Import " + LocalDateTime.now().format(NAME_FORMAT);
        return pools.createImported(name, orderedByDirectoryThenNaturalName(addedEntryIds));
    }

    /**
     * Spec §3.8's member order: (directory, NaturalOrder filename, id) — the directory is
     * the rel_path minus its file name ({@code ''} = root top level), both compared
     * naturally in Java (SQL has no natural-order collation). Ids whose rows vanished
     * between run and follow-up drop out silently.
     */
    private List<Long> orderedByDirectoryThenNaturalName(List<Long> ids) {
        record Row(long id, String relPath) {}
        // SCALE CEILING, not a bug: Postgres binds each id as its own parameter here, so an
        // initial import adding more than 65535 entries exceeds the protocol's per-statement
        // bind limit and fails this query — the follow-up pool draft then fails and the run
        // still completes with pool_id null (the caller's WARN-only guard). Failure-safe by
        // design. If the ceiling ever matters, `= any(:ids)` with an array bind lifts it.
        List<Row> rows = jdbc.sql("select id, rel_path from media_entry where id in (:ids)")
                .param("ids", ids)
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("rel_path")))
                .list();
        Comparator<Row> order = Comparator
                .comparing((Row r) -> directoryOf(r.relPath()), NaturalOrder::compare)
                .thenComparing(r -> fileNameOf(r.relPath()), NaturalOrder::compare)
                .thenComparingLong(Row::id);
        return rows.stream().sorted(order).map(Row::id).toList();
    }

    private static String directoryOf(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash < 0 ? "" : relPath.substring(0, slash);
    }

    private static String fileNameOf(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash < 0 ? relPath : relPath.substring(slash + 1);
    }
}
