package io.github.nullotope.spicygallery.server.store;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.Status;
import io.github.nullotope.spicygallery.domain.StoredLocation;
import io.github.nullotope.spicygallery.importer.MediaStoreSink;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC implementation of the core MediaStoreSink port; every call is its own transaction. */
@Repository
public class JdbcMediaStoreSink implements MediaStoreSink {

    private final JdbcClient jdbc;

    public JdbcMediaStoreSink(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public EntryId insert(NewEntry e) {
        Long id = jdbc.sql("""
                        insert into media_entry (kind, status, root_name, rel_path, file_name, ext, mime,
                                size_bytes, content_hash, width, height, duration_ms, media_mtime)
                        values (?, 'ok', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .param(e.kind().wireName())
                .param(e.rootName())
                .param(e.relPath())
                .param(e.fileName())
                .param(e.ext())
                .param(e.mime())
                .param(e.sizeBytes())
                .param(e.contentHash())
                .param(e.width())
                .param(e.height())
                .param(e.durationMs())
                .param(Timestamp.from(e.mediaMtime()))
                .query(Long.class)
                .single();
        return new EntryId(id);
    }

    @Override
    @Transactional
    public void update(EntryId id, NewEntry e) {
        jdbc.sql("""
                        update media_entry set kind = ?, status = 'ok', file_name = ?, ext = ?, mime = ?,
                                size_bytes = ?, content_hash = ?, width = ?, height = ?, duration_ms = ?,
                                media_mtime = ?, updated_at = now()
                        where id = ?
                        """)
                .param(e.kind().wireName())
                .param(e.fileName())
                .param(e.ext())
                .param(e.mime())
                .param(e.sizeBytes())
                .param(e.contentHash())
                .param(e.width())
                .param(e.height())
                .param(e.durationMs())
                .param(Timestamp.from(e.mediaMtime()))
                .param(id.value())
                .update();
    }

    @Override
    @Transactional
    public void setStatus(EntryId id, Status status) {
        jdbc.sql("update media_entry set status = ?, updated_at = now() where id = ?")
                .param(status.wireName())
                .param(id.value())
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredLocation> find(String rootName, String relPath) {
        return jdbc.sql("""
                        select id, kind, ext, size_bytes, media_mtime, status
                        from media_entry where root_name = ? and rel_path = ?
                        """)
                .param(rootName)
                .param(relPath)
                .query((rs, i) -> new StoredLocation(
                        new EntryId(rs.getLong("id")),
                        Kind.fromWireName(rs.getString("kind")),
                        rs.getString("ext"),
                        rs.getLong("size_bytes"),
                        rs.getObject("media_mtime", java.time.OffsetDateTime.class).toInstant(),
                        Status.fromWireName(rs.getString("status"))))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, StoredLocation> allInRoot(String rootName) {
        Map<String, StoredLocation> out = new LinkedHashMap<>();
        jdbc.sql("""
                        select rel_path, id, kind, ext, size_bytes, media_mtime, status
                        from media_entry where root_name = ?
                        """)
                .param(rootName)
                .query((rs, i) -> {
                    out.put(rs.getString("rel_path"), new StoredLocation(
                            new EntryId(rs.getLong("id")),
                            Kind.fromWireName(rs.getString("kind")),
                            rs.getString("ext"),
                            rs.getLong("size_bytes"),
                            rs.getObject("media_mtime", java.time.OffsetDateTime.class).toInstant(),
                            Status.fromWireName(rs.getString("status"))));
                    return null;
                })
                .list();
        return out;
    }
}
