package io.github.nullotope.spicygallery.query;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Query metatags (spec §3.2). Values are parsed/validated here (core) and widened to SQL
 * by the server's compiler. Most metatags have real backing columns; SIMILAR and CONCEPT
 * are column-less — the controller resolves them into one query vector (embedding phrase
 * / concept anchor) that drives the distance ordering (spec §3.4).
 */
public enum Metatag {
    TYPE, EXT, ROOT, WIDTH, HEIGHT, DURATION, FILESIZE, DATE, TAGCOUNT, IS, HASH, ORDER, SIMILAR, CONCEPT;

    public enum Op { GT, GTE, LT, LTE, EQ }

    public record MetaValue(Op op, Object value) {
    }

    private record OpRest(Op op, String rest) {
    }

    public static Metatag fromName(String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown metatag '" + name + ":'");
        }
    }

    /** Parses and validates the value part of {@code metatag:value}; throws on malformed input. */
    public MetaValue parseValue(String raw) {
        return switch (this) {
            case TYPE -> word(raw, "image", "video");
            case IS -> word(raw, "untagged", "tagged");
            case EXT -> new MetaValue(Op.EQ, raw.toLowerCase(Locale.ROOT));
            case HASH -> new MetaValue(Op.EQ, raw.toLowerCase(Locale.ROOT));
            case ROOT, ORDER, SIMILAR, CONCEPT -> new MetaValue(Op.EQ, raw);
            case WIDTH, HEIGHT, TAGCOUNT -> number(raw);
            case DURATION -> duration(raw);
            case FILESIZE -> filesize(raw);
            case DATE -> date(raw);
        };
    }

    private static MetaValue word(String raw, String... allowed) {
        for (String a : allowed) {
            if (a.equals(raw)) {
                return new MetaValue(Op.EQ, raw);
            }
        }
        throw new IllegalArgumentException("invalid value '" + raw + "'");
    }

    private static OpRest stripOp(String raw) {
        if (raw.startsWith(">=")) {
            return new OpRest(Op.GTE, raw.substring(2));
        }
        if (raw.startsWith("<=")) {
            return new OpRest(Op.LTE, raw.substring(2));
        }
        if (raw.startsWith(">")) {
            return new OpRest(Op.GT, raw.substring(1));
        }
        if (raw.startsWith("<")) {
            return new OpRest(Op.LT, raw.substring(1));
        }
        return new OpRest(Op.EQ, raw);
    }

    /** {@code [op]number} — comparator prefix optional, bare means EQ. */
    private static MetaValue number(String raw) {
        OpRest or = stripOp(raw);
        return new MetaValue(or.op(), longValue(or.rest()));
    }

    /** Bare = seconds; {@code 30s}/{@code 2m} accepted — result in milliseconds. */
    private static MetaValue duration(String raw) {
        OpRest or = stripOp(raw);
        String rest = or.rest().toLowerCase(Locale.ROOT);
        long multiplier;
        String digits;
        if (rest.endsWith("s")) {
            multiplier = 1000L;
            digits = rest.substring(0, rest.length() - 1);
        } else if (rest.endsWith("m")) {
            multiplier = 60_000L;
            digits = rest.substring(0, rest.length() - 1);
        } else {
            multiplier = 1000L; // bare = seconds
            digits = rest;
        }
        return new MetaValue(or.op(), longValue(digits) * multiplier);
    }

    /** {@code b}/{@code kb}/{@code mb}/{@code gb} (case-insensitive), bare = bytes. */
    private static MetaValue filesize(String raw) {
        OpRest or = stripOp(raw);
        String rest = or.rest().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        String digits = rest;
        if (rest.endsWith("kb")) {
            multiplier = 1024L;
            digits = rest.substring(0, rest.length() - 2);
        } else if (rest.endsWith("mb")) {
            multiplier = 1024L * 1024;
            digits = rest.substring(0, rest.length() - 2);
        } else if (rest.endsWith("gb")) {
            multiplier = 1024L * 1024 * 1024;
            digits = rest.substring(0, rest.length() - 2);
        } else if (rest.endsWith("b")) {
            digits = rest.substring(0, rest.length() - 1);
        }
        return new MetaValue(or.op(), longValue(digits) * multiplier);
    }

    private static MetaValue date(String raw) {
        OpRest or = stripOp(raw);
        try {
            return new MetaValue(or.op(), LocalDate.parse(or.rest()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid date '" + raw + "' (expected yyyy-MM-dd)");
        }
    }

    private static long longValue(String digits) {
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("invalid number '" + digits + "'");
        }
        return Long.parseLong(digits);
    }
}
