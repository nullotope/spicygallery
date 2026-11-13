package io.github.nullotope.spicygallery.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Index status of a media entry. Wire name is the kebab-case string. */
public enum Status {
    OK("ok"),
    MISSING("missing"),
    ERROR("error");

    private final String wireName;

    Status(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static Status fromWireName(String s) {
        for (Status st : values()) {
            if (st.wireName.equals(s)) {
                return st;
            }
        }
        throw new IllegalArgumentException("unknown status: " + s);
    }
}
