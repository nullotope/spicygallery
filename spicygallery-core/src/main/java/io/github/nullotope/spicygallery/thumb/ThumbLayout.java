package io.github.nullotope.spicygallery.thumb;

import java.nio.file.Path;

/** Thumbnail file layout (spec §7): {app-data}/thumbs/{id % 1000}/{id}.jpg — id-derived, no DB column. */
public final class ThumbLayout {

    private ThumbLayout() {}

    public static Path pathFor(Path appData, long id) {
        return appData.resolve("thumbs")
                .resolve(String.valueOf(id % 1000))
                .resolve(id + ".jpg");
    }
}
