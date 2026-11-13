package io.github.nullotope.spicygallery.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The ONE central Jackson mapper (single-mapper rule): every JSON we read or write
 * in Java — including ffprobe output — goes through {@link #mapper()}. Never
 * string-build JSON.
 */
public final class SpicyGalleryJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private SpicyGalleryJson() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
