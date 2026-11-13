package io.github.nullotope.spicygallery.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PropertiesDefaultsTest {

    @Test
    void defaults_apply_when_binding_omits_keys() {
        SpicyGalleryProperties p = new SpicyGalleryProperties(null, null, null, null,
                null, null, null, null, null);
        assertEquals("./data", p.appData());
        assertEquals("ffmpeg", p.ffmpegPath());
        assertEquals(4, p.workers());
        assertEquals(List.of("jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp"), p.imageExts());
        assertEquals(List.of("mp4", "webm", "mkv", "mov"), p.videoExts());
        assertEquals(List.of(), p.libraries());
    }

    @Test
    void duplicate_library_names_rejected() {
        SpicyGalleryProperties p = new SpicyGalleryProperties(
                List.of(new SpicyGalleryProperties.Library("a", "/x"),
                        new SpicyGalleryProperties.Library("a", "/y")),
                "./data", "ffmpeg", "ffprobe", 4,
                List.of("png"), List.of("mp4"), null, null);
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    void embedding_enabled_defaults_true_when_a_single_property_is_bound() {
        // Boot record constructor-binding path: setting ONLY base-url must NOT flip
        // enabled to false (primitive-default trap). Reproduces the live compose
        // configuration: SPICYGALLERY_EMBEDDING_BASE_URL without SPICYGALLERY_EMBEDDING_ENABLED.
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                java.util.Map.of("spicygallery.embedding.base-url", "http://host.docker.internal:9292/v1"));
        SpicyGalleryProperties p = new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("spicygallery", org.springframework.boot.context.properties.bind.Bindable.of(SpicyGalleryProperties.class))
                .get();
        assertEquals("http://host.docker.internal:9292/v1", p.embedding().baseUrl());
        assertEquals(Boolean.TRUE, p.embedding().enabled(), "enabled must default true");
        assertEquals("embeddinggemma-2", p.embedding().model());
    }

    @Test
    void tagging_enabled_defaults_true_when_a_single_property_is_bound() {
        var binder = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        java.util.Map.of("spicygallery.tagging.base-url", "http://host.docker.internal:9292/upstream/hydra-3.5")));
        var tagging = binder.bind("spicygallery.tagging", SpicyGalleryProperties.Tagging.class).get();
        assertTrue(tagging.enabled());
        assertEquals("http://host.docker.internal:9292/upstream/hydra-3.5", tagging.baseUrl());
        assertEquals("hydra-3.5", tagging.model());
        assertEquals("f1.0@0.1", tagging.calibration());
        assertEquals("inherit", tagging.implications());
        assertEquals(4, tagging.concurrency());
    }

    @Test
    void tagging_defaults_when_absent() {
        // 9 positional args: the record gains `Tagging tagging` as its 9th (last) component.
        var p = new SpicyGalleryProperties(null, null, null, null, null, null, null, null, null);
        assertEquals("http://host.docker.internal:9292/upstream/hydra-3.5", p.tagging().baseUrl());
        assertTrue(p.tagging().enabled());
        assertEquals(300, p.tagging().failureCooldownSeconds()); // breaker cooldown default (Task 5)
    }
}
