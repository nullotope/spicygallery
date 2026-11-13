package io.github.nullotope.spicygallery.importer;

import io.github.nullotope.spicygallery.domain.EntryId;
import io.github.nullotope.spicygallery.domain.Kind;
import io.github.nullotope.spicygallery.domain.NewEntry;
import io.github.nullotope.spicygallery.domain.StoredLocation;
import io.github.nullotope.spicygallery.domain.Status;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** In-memory MediaStoreSink recording every call, for pipeline tests. */
final class FakeSink implements MediaStoreSink {

    final Map<String, StoredLocation> byPath = new ConcurrentHashMap<>();
    final Map<Long, NewEntry> rows = new ConcurrentHashMap<>();
    final Map<Long, Status> statuses = new ConcurrentHashMap<>();
    final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(100);
    /** test hook: when non-null, every insert awaits it (simulates slow hashing/IO). */
    volatile CountDownLatch insertGate;

    @Override
    public Optional<StoredLocation> find(String rootName, String relPath) {
        return Optional.ofNullable(byPath.get(rootName + "/" + relPath));
    }

    @Override
    public Map<String, StoredLocation> allInRoot(String rootName) {
        return byPath.entrySet().stream()
                .filter(e -> e.getKey().startsWith(rootName + "/"))
                .collect(Collectors.toMap(
                        e -> e.getKey().substring(rootName.length() + 1),
                        Map.Entry::getValue));
    }

    @Override
    public EntryId insert(NewEntry e) {
        CountDownLatch gate = insertGate;
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("insert gate interrupted", ie);
            }
        }
        long id = seq.incrementAndGet();
        rows.put(id, e);
        statuses.put(id, Status.OK);
        byPath.put(e.rootName() + "/" + e.relPath(),
                new StoredLocation(new EntryId(id), e.kind(), e.ext(),
                        e.sizeBytes(), e.mediaMtime(), Status.OK));
        calls.add("insert:" + id);
        return new EntryId(id);
    }

    @Override
    public void update(EntryId id, NewEntry e) {
        rows.put(id.value(), e);
        statuses.put(id.value(), Status.OK);
        calls.add("update:" + id.value());
    }

    @Override
    public void setStatus(EntryId id, Status status) {
        statuses.put(id.value(), status);
        calls.add("status:" + id.value() + ":" + status.wireName());
    }

    NewEntry row(String rootRel) {
        return rows.values().stream()
                .filter(e -> (e.rootName() + "/" + e.relPath()).equals(rootRel))
                .findFirst()
                .orElse(null);
    }

    Status statusOf(long id) {
        return statuses.get(id);
    }
}
