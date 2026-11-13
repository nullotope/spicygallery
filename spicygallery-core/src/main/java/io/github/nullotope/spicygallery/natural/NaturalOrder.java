package io.github.nullotope.spicygallery.natural;

/**
 * Natural (human) filename ordering for pool members (spec §3.3): digit runs compare
 * numerically ("page2" < "page10"), text runs compare case-insensitively (Locale.ROOT),
 * and digit runs sort before text runs ("001" < "cover"). Zero padding is ignored when
 * digit runs are numerically equal ("03" == "3" → 0).
 */
public final class NaturalOrder {

    private NaturalOrder() {
    }

    /**
     * Compares two filenames naturally; throws NPE on null arguments — callers guarantee
     * non-null. Equal filenames under this order yield 0; callers tiebreak (e.g. by id).
     * Divergence semantics: runs compare as WHOLE units, so when one text run is a strict
     * prefix of the other the longer run sorts after — digit-vs-text does not re-apply at
     * the divergence point ({@code "ab2" > "a3"} because {@code "ab" > "a"}).
     */
    public static int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            boolean digitA = isDigit(a.charAt(i));
            boolean digitB = isDigit(b.charAt(j));
            if (digitA && digitB) {
                int endA = digitRunEnd(a, i);
                int endB = digitRunEnd(b, j);
                int cmp = compareDigitRuns(a, i, endA, b, j, endB);
                if (cmp != 0) {
                    return cmp;
                }
                i = endA;
                j = endB;
            } else if (digitA) {
                return -1; // digit runs sort before text runs
            } else if (digitB) {
                return 1;
            } else {
                int endA = textRunEnd(a, i);
                int endB = textRunEnd(b, j);
                int cmp = a.substring(i, endA).toLowerCase(java.util.Locale.ROOT)
                        .compareTo(b.substring(j, endB).toLowerCase(java.util.Locale.ROOT));
                if (cmp != 0) {
                    return cmp;
                }
                i = endA;
                j = endB;
            }
        }
        if (i >= a.length() && j >= b.length()) {
            return 0;
        }
        return i >= a.length() ? -1 : 1;
    }

    /** Compares digit runs by numeric value; leading zeros are ignored ("03" == "3"). */
    private static int compareDigitRuns(String a, int fromA, int toA, String b, int fromB, int toB) {
        int startA = skipZeros(a, fromA, toA);
        int startB = skipZeros(b, fromB, toB);
        int lenA = toA - startA;
        int lenB = toB - startB;
        if (lenA != lenB) {
            return Integer.compare(lenA, lenB); // more significant digits → larger number
        }
        for (int k = 0; k < lenA; k++) {
            char ca = a.charAt(startA + k);
            char cb = b.charAt(startB + k);
            if (ca != cb) {
                return ca - cb;
            }
        }
        return 0;
    }

    private static int skipZeros(String s, int from, int to) {
        while (from < to && s.charAt(from) == '0') {
            from++;
        }
        return from;
    }

    private static int digitRunEnd(String s, int from) {
        int end = from;
        while (end < s.length() && isDigit(s.charAt(end))) {
            end++;
        }
        return end;
    }

    private static int textRunEnd(String s, int from) {
        int end = from;
        while (end < s.length() && !isDigit(s.charAt(end))) {
            end++;
        }
        return end;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
