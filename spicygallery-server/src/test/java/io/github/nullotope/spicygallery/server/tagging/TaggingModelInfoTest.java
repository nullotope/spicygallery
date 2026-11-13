package io.github.nullotope.spicygallery.server.tagging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.nullotope.spicygallery.domain.TagCategory;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Hydra /info parsing + provider-category mapping (spec §3.2, live-vocabulary amendments). */
class TaggingModelInfoTest {

    @Test
    void maps_provider_categories_onto_the_five_gallery_categories() {
        assertEquals(Optional.of(TagCategory.GENERAL), TaggingModelInfo.mapCategory("general"));
        assertEquals(Optional.of(TagCategory.ARTIST), TaggingModelInfo.mapCategory("artist"));
        assertEquals(Optional.of(TagCategory.CHARACTER), TaggingModelInfo.mapCategory("character"));
        assertEquals(Optional.of(TagCategory.COPYRIGHT), TaggingModelInfo.mapCategory("copyright"));
        assertEquals(Optional.of(TagCategory.META), TaggingModelInfo.mapCategory("meta"));
        assertEquals(Optional.of(TagCategory.META), TaggingModelInfo.mapCategory("lore"));
        // Task 1 live /info ruling: species (1426 labels, 16% of the vocabulary) maps onto
        // general — deliberately, without a schema change; skipping it would gut tagging.
        assertEquals(Optional.of(TagCategory.GENERAL), TaggingModelInfo.mapCategory("species"));
        assertEquals(Optional.empty(), TaggingModelInfo.mapCategory("invalid"));
        assertEquals(Optional.empty(), TaggingModelInfo.mapCategory(null));
    }

    @Test
    void parses_info_and_looks_up_normalized_names() {
        String json = """
            {"model":"hydra-3.5","extensions":[],"image_config":{},
             "labels":{"red_hair":{"category":"general","subcategory":null,"implies":[]},
                       "Azur Lane":{"category":"copyright","subcategory":null,"implies":[]}}}
            """;
        var info = TaggingModelInfo.fromJson(json);
        assertEquals("hydra-3.5", info.model());
        assertEquals(2, info.labelCount());
        assertEquals(Optional.of(TagCategory.COPYRIGHT), info.categoryOf("azur_lane")); // TagName.normalize
        assertEquals(Optional.empty(), info.categoryOf("not_a_label"));
    }

    // Controller ruling (Task 1 findings): Hydra's meta labels carry a subcategory; the
    // three rating ones (safe/questionable/explicit) are out of scope (spec §7) — they must
    // behave as if they were not labels at all: not resolvable, not counted.
    @Test
    void rating_subcategory_labels_are_excluded_entirely() {
        String json = """
            {"model":"hydra-3.5","extensions":[],"image_config":{},
             "labels":{"safe":{"category":"meta","subcategory":"rating","implies":[]},
                       "red_hair":{"category":"general","subcategory":null,"implies":[]}}}
            """;
        var info = TaggingModelInfo.fromJson(json);
        assertEquals(1, info.labelCount());
        assertEquals(Optional.empty(), info.categoryOf("safe"));
        assertEquals(Optional.of(TagCategory.GENERAL), info.categoryOf("red_hair"));
    }

    @Test
    void unnormalizable_label_keys_are_skipped() {
        String json = """
            {"model":"hydra-3.5","extensions":[],"image_config":{},
             "labels":{"ok_name":{"category":"general","subcategory":null,"implies":[]},
                       "!!!":{"category":"general","subcategory":null,"implies":[]}}}
            """;
        var info = TaggingModelInfo.fromJson(json);
        assertEquals(1, info.labelCount());
        assertEquals(Optional.empty(), info.categoryOf("!!!"));
    }
}
