package io.github.nullotope.spicygallery.server.tagging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nullotope.spicygallery.domain.TagCategory;
import io.github.nullotope.spicygallery.query.TagName;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed Hydra {@code /info} payload (spec §3.2): the reported model name plus the
 * label vocabulary as {@code normalized name → provider category}.
 *
 * <p>Category mapping (§3.2 table, amended by the Task 1 live-{@code /info} findings):
 * the provider strings are lowercase plain ({@code general}, {@code character},
 * {@code species}, {@code meta}, {@code copyright}, {@code lore}). {@code species}
 * deliberately maps onto {@link TagCategory#GENERAL} — it carries ~16% of the live
 * vocabulary and dropping it would gut tagging quality. Labels whose {@code subcategory}
 * is {@code rating} (the booru score labels) are out of scope (§7 non-goal) and are
 * excluded entirely at parse time: not resolvable, not counted.
 *
 * <p>{@link #labelCount()} always equals {@link #labelCategory()}{@code .size()} — the
 * count is derived, so an excluded or skipped label can never be counted.
 */
public record TaggingModelInfo(String model, Map<String, String> labelCategory, int labelCount) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TaggingModelInfo {
        labelCategory = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(labelCategory));
        labelCount = labelCategory.size();
    }

    /** Parses a raw {@code /info} body; unparseable JSON is a contract error. */
    public static TaggingModelInfo fromJson(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("unparseable /info payload", e);
        }
        JsonNode labels = root.path("labels");
        Map<String, String> labelCategory = new LinkedHashMap<>();
        for (java.util.Iterator<Map.Entry<String, JsonNode>> it = labels.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> label = it.next();
            JsonNode spec = label.getValue();
            // rating subcategory = booru score labels (safe/questionable/explicit): out of scope.
            if ("rating".equalsIgnoreCase(spec.path("subcategory").asText(null))) {
                continue;
            }
            String normalized;
            try {
                normalized = TagName.normalize(label.getKey());
            } catch (IllegalArgumentException e) {
                continue; // key outside the gallery tag charset: skipped
            }
            labelCategory.put(normalized, spec.path("category").asText(null));
        }
        return new TaggingModelInfo(root.path("model").asText(""), labelCategory, labelCategory.size());
    }

    /** Category of an already-normalized tag name; empty when unknown or unmappable. */
    public Optional<TagCategory> categoryOf(String normalizedTag) {
        return mapCategory(labelCategory.get(normalizedTag));
    }

    /** Maps a provider category string (§3.2 table); anything else → empty (caller WARNs). */
    public static Optional<TagCategory> mapCategory(String providerCategory) {
        if (providerCategory == null) {
            return Optional.empty();
        }
        return switch (providerCategory.toLowerCase(Locale.ROOT)) {
            case "general", "species" -> Optional.of(TagCategory.GENERAL);
            case "artist" -> Optional.of(TagCategory.ARTIST);
            case "character" -> Optional.of(TagCategory.CHARACTER);
            case "copyright" -> Optional.of(TagCategory.COPYRIGHT);
            case "meta", "lore" -> Optional.of(TagCategory.META);
            default -> Optional.empty();
        };
    }
}
