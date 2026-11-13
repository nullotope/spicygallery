package io.github.nullotope.spicygallery.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.github.nullotope.spicygallery.server.PostgresBase;

@SpringBootTest(properties = {
    "spicygallery.libraries[0].name=library", "spicygallery.libraries[0].root=/tmp/l1",
    "spicygallery.libraries[1].name=exports", "spicygallery.libraries[1].root=/tmp/l2",
    "spicygallery.workers=7"})
class PropertiesBindingTest extends PostgresBase {

    @Test
    void env_style_indexed_lists_bind(@Autowired SpicyGalleryProperties p) {
        assertEquals("library", p.libraries().get(0).name());
        assertEquals("/tmp/l1", p.libraries().get(0).root());
        assertEquals("exports", p.libraries().get(1).name());
        assertEquals(7, p.workers());
        assertEquals(List.of("jpg", "jpeg", "jpe", "png", "gif", "webp", "bmp"), p.imageExts());
        assertEquals(List.of("mp4", "webm", "mkv", "mov"), p.videoExts());
    }
}
