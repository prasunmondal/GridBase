package io.github.prasunmondal.gridbase.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Stand-ins for JDK 9+ library methods that Android (API 26) does not have, e.g. {@code List.of},
 * {@code List.copyOf}, {@code Stream.toList()}, {@code String.isBlank()} and {@code HexFormat}.
 * The {@code android-api} check in {@code pom.xml} fails the build if main code calls them directly.
 */
public final class Compat {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Compat() {
    }

    /** Like {@code List.of}: unmodifiable, rejects nulls. */
    @SafeVarargs
    public static <T> List<T> listOf(T... items) {
        return copyOf(Arrays.asList(items));
    }

    /** Like {@code List.copyOf}: unmodifiable snapshot, rejects nulls. */
    public static <T> List<T> copyOf(Collection<? extends T> items) {
        List<T> copy = new ArrayList<>(items);
        for (T item : copy) {
            Objects.requireNonNull(item);
        }
        return Collections.unmodifiableList(copy);
    }

    /** Like {@code Stream.toList()}: unmodifiable, allows nulls. */
    public static <T> Collector<T, ?, List<T>> toList() {
        return Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList);
    }

    /** Like {@code String.isBlank()}. */
    public static boolean isBlank(String s) {
        return s.codePoints().allMatch(Character::isWhitespace);
    }

    /** Like {@code String.stripLeading()}. */
    public static String stripLeading(String s) {
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                break;
            }
            i += Character.charCount(cp);
        }
        return s.substring(i);
    }

    /** Like {@code HexFormat.of().formatHex(bytes)}. */
    public static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = HEX[(bytes[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
