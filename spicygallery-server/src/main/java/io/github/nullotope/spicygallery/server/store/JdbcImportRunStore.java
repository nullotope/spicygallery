package io.github.nullotope.spicygallery.server.store;

import io.github.nullotope.spicygallery.domain.ImportResult;
import io.github.nullotope.spicygallery.domain.ImportStats;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** import_run lifecycle (spec §5/§8): one row per run, REST-reconcile source. */
@Repository
public class JdbcImportRunStore implements ImportRunStore {

    private final JdbcClient jdbc;

    public JdbcImportRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long start() {
        return jdbc.sql("insert into import_run (status) values ('running') returning id")
                .query(Long.class)
                .single();
    }

    @Transactional
    public void touch(long id, ImportStats s) {
        jdbc.sql("""
                        update import_run set scanned = ?, added = ?, updated = ?,
                                missing_count = ?, error_count = ? where id = ?
                        """)
                .param(s.scanned())
                .param(s.added())
                .param(s.updated())
                .param(s.missing())
                .param(s.errors())
                .param(id)
                .update();
    }

    @Transactional
    public void complete(long id, ImportResult r) {
        String message = r.skippedRoots().isEmpty() ? null
                : "skipped: " + String.join(", ", r.skippedRoots());
        jdbc.sql("""
                        update import_run set status = 'completed', finished_at = now(),
                                scanned = ?, added = ?, updated = ?, missing_count = ?,
                                error_count = ?, message = ?
                        where id = ?
                        """)
                .param(r.scanned())
                .param(r.added())
                .param(r.updated())
                .param(r.missing())
                .param(r.errors())
                .param(message)
                .param(id)
                .update();
    }

    @Transactional
    public void fail(long id, String message) {
        jdbc.sql("update import_run set status = 'failed', finished_at = now(), message = ? where id = ?")
                .param(message)
                .param(id)
                .update();
    }

    @Transactional
    public void setPoolId(long id, Long poolId) {
        jdbc.sql("update import_run set pool_id = ? where id = ?")
                .param(poolId)
                .param(id)
                .update();
    }

    @Transactional
    public void markInterrupted() {
        jdbc.sql("""
                        update import_run set status = 'failed', finished_at = now(),
                                message = 'interrupted by restart'
                        where status = 'running'
                        """)
                .update();
    }

    @Transactional(readOnly = true)
    public Optional<ImportRunRow> last() {
        return jdbc.sql("""
                        select id, status, started_at, finished_at, scanned, added, updated,
                               missing_count, error_count, message, pool_id
                        from import_run order by id desc limit 1
                        """)
                .query((rs, i) -> new ImportRunRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getTimestamp("started_at").toInstant(),
                        toInstant(rs.getTimestamp("finished_at")),
                        rs.getInt("scanned"),
                        rs.getInt("added"),
                        rs.getInt("updated"),
                        rs.getInt("missing_count"),
                        rs.getInt("error_count"),
                        rs.getString("message"),
                        rs.getObject("pool_id", Long.class)))
                .optional();
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
