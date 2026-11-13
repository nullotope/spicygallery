package io.github.nullotope.spicygallery.server.web;

import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.server.store.JdbcTagStore;
import io.github.nullotope.spicygallery.server.tags.TagService;
import io.github.nullotope.spicygallery.server.web.dto.AliasRequest;
import io.github.nullotope.spicygallery.server.web.dto.AutocompleteDto;
import io.github.nullotope.spicygallery.server.web.dto.ImplicationRequest;
import io.github.nullotope.spicygallery.server.web.dto.SetCategoryRequest;
import io.github.nullotope.spicygallery.server.web.dto.TagDto;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tag management API (spec §7): /tags listing, autocomplete, category change, and the
 * API-only alias/implication management (admin UI deferred to a later slice).
 */
@RestController
public class TagController {

    private final TagService tags;

    public TagController(TagService tags) {
        this.tags = tags;
    }

    @GetMapping("/api/tags")
    public TagsPage list(@RequestParam Optional<String> q,
                         @RequestParam Optional<String> category,
                         @RequestParam Optional<String> sort,
                         @RequestParam Optional<String> cursor,
                         @RequestParam Optional<Integer> limit) {
        TagCategory categoryValue = category.filter(c -> !c.isBlank())
                .map(TagCategory::fromWireName).orElse(null);
        JdbcTagStore.TagSort sortValue = sort(sort.filter(s -> !s.isBlank()).orElse("name"));
        String[] cursorParts = splitCursor(cursor);
        String cursorKey = cursorParts[0];
        long cursorId = cursorParts.length > 1 ? Long.parseLong(cursorParts[1]) : 0;
        int limitValue = limit.orElse(50);
        if (limitValue < 1 || limitValue > 200) {
            throw new IllegalArgumentException("limit out of range 1..200: " + limitValue);
        }
        List<JdbcTagStore.TagRow> raw = tags.listTags(
                q.map(String::trim).filter(s -> !s.isEmpty()).orElse(null),
                categoryValue, sortValue, cursorKey, cursorId, limitValue);
        boolean hasMore = raw.size() > limitValue;
        List<JdbcTagStore.TagRow> rows = hasMore ? raw.subList(0, limitValue) : raw;
        String nextCursor = null;
        if (hasMore) {
            JdbcTagStore.TagRow last = rows.get(rows.size() - 1);
            nextCursor = cursorKey(last, sortValue) + ":" + last.id();
        }
        return new TagsPage(rows.stream()
                .map(r -> new TagDto(r.id(), r.name(), r.category(), r.postCount(), r.taggedBy()))
                .toList(), nextCursor);
    }

    private static JdbcTagStore.TagSort sort(String wire) {
        try {
            return JdbcTagStore.TagSort.valueOf(wire.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown sort: " + wire);
        }
    }

    private static String cursorKey(JdbcTagStore.TagRow row, JdbcTagStore.TagSort sort) {
        return switch (sort) {
            case NAME -> row.name(); // names cannot contain ':'
            case COUNT -> String.valueOf(row.postCount());
            case NEW -> String.valueOf(row.createdAt().getEpochSecond() * 1_000_000
                    + row.createdAt().getNano() / 1000); // epoch micros (store contract)
        };
    }

    private static String[] splitCursor(Optional<String> cursor) {
        String raw = cursor.filter(c -> !c.isBlank()).orElse(null);
        if (raw == null) {
            return new String[] {null};
        }
        int idx = raw.lastIndexOf(':');
        if (idx < 0) {
            throw new IllegalArgumentException("invalid cursor: " + raw);
        }
        return new String[] {raw.substring(0, idx), raw.substring(idx + 1)};
    }

    public record TagsPage(List<TagDto> items, String nextCursor) {}

    @GetMapping("/api/tags/autocomplete")
    public List<AutocompleteDto> autocomplete(@RequestParam String q,
                                              @RequestParam Optional<Integer> limit) {
        int limitValue = limit.orElse(20);
        if (limitValue < 1 || limitValue > 50) {
            throw new IllegalArgumentException("limit out of range 1..50: " + limitValue);
        }
        if (q.isBlank()) {
            return List.of();
        }
        return tags.autocomplete(q.trim(), limitValue).stream()
                .map(h -> new AutocompleteDto(h.name(), h.category(), h.postCount(), h.aliasOf()))
                .toList();
    }

    @PatchMapping("/api/tags/{name}")
    public TagDto setCategory(@PathVariable String name, @RequestBody SetCategoryRequest request) {
        TagCategory category = TagCategory.fromWireName(request.category());
        var row = tags.changeCategory(name, category);
        return new TagDto(row.id(), row.name(), row.category(), row.postCount(), row.taggedBy());
    }

    @GetMapping("/api/tags/aliases")
    public List<JdbcTagStore.AliasRow> aliases() {
        return tags.listAliases();
    }

    @PostMapping("/api/tags/aliases")
    org.springframework.http.ResponseEntity<Void> createAlias(@RequestBody AliasRequest request) {
        tags.createAlias(request.antecedent(), request.consequent());
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/tags/aliases/{name}")
    org.springframework.http.ResponseEntity<Void> deleteAlias(@PathVariable String name) {
        tags.removeAlias(name);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping("/api/tags/implications")
    public List<JdbcTagStore.ImplicationRow> implications() {
        return tags.listImplications();
    }

    @PostMapping("/api/tags/implications")
    org.springframework.http.ResponseEntity<Void> createImplication(@RequestBody ImplicationRequest request) {
        tags.createImplication(request.antecedent(), request.consequent());
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/tags/implications/{antecedent}/{consequent}")
    org.springframework.http.ResponseEntity<Void> deleteImplication(@PathVariable String antecedent,
                                                                    @PathVariable String consequent) {
        tags.removeImplication(antecedent, consequent);
        return org.springframework.http.ResponseEntity.noContent().build();
    }
}
