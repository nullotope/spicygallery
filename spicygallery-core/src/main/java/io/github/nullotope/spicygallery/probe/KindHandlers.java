package io.github.nullotope.spicygallery.probe;

import io.github.nullotope.spicygallery.domain.Kind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Registry over the configured {@link KindHandler}s; source of the ext → kind allowlist. */
public final class KindHandlers {

    private final List<KindHandler> handlers;
    private final Map<String, Kind> allowlist;

    public KindHandlers(List<KindHandler> handlers) {
        this.handlers = List.copyOf(handlers);
        this.allowlist = new LinkedHashMap<>();
        for (KindHandler h : this.handlers) {
            for (String ext : h.extensions()) {
                String normalized = ext.toLowerCase(Locale.ROOT);
                Kind previous = this.allowlist.put(normalized, h.kind());
                if (previous != null && previous != h.kind()) {
                    throw new IllegalArgumentException(
                            "extension '" + normalized + "' claimed by two kinds: "
                                    + previous + " and " + h.kind());
                }
            }
        }
    }

    public Optional<KindHandler> forExtension(String ext) {
        String normalized = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        return handlers.stream()
                .filter(h -> h.extensions().contains(normalized))
                .findFirst();
    }

    public Optional<KindHandler> forKind(Kind kind) {
        return handlers.stream().filter(h -> h.kind() == kind).findFirst();
    }

    /** ext (lowercased, no dot) → kind; the scanner's allowlist. */
    public Map<String, Kind> allowlist() {
        return Map.copyOf(allowlist);
    }
}
