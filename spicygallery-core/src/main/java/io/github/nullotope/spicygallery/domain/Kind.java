package io.github.nullotope.spicygallery.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Media kind. Wire name is the kebab-case string that crosses every boundary. */
public enum Kind {
    IMAGE("image"),
    VIDEO("video");

    private final String wireName;

    Kind(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static Kind fromWireName(String s) {
        for (Kind k : values()) {
            if (k.wireName.equals(s)) {
                return k;
            }
        }
        throw new IllegalArgumentException("unknown kind: " + s);
    }
}
