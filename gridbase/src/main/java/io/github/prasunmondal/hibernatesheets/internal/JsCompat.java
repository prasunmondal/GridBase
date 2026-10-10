package io.github.prasunmondal.hibernatesheets.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;

/**
 * Converts Java values into the shape the Apps Script engine compares against, so filters behave
 * the way a Java caller expects.
 */
public final class JsCompat {

    private static final BigDecimal UPPER_PLAIN = new BigDecimal("1e21");
    private static final BigDecimal LOWER_PLAIN = new BigDecimal("1e-6");

    private JsCompat() {
    }

    /**
     * Renders a value exactly as JavaScript's {@code String(value)} would, for the value types that can
     * live in a sheet cell. The engine's EQUALS/NOT_EQUALS/CONTAINS/... evaluate
     * {@code String(cell) === expected}, so {@code 5.0} must become {@code "5"} and {@code 1e-7}
     * must become {@code "1e-7"}.
     */
    public static String toJsString(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean || value instanceof Character) {
            return value.toString();
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger) {
            return value.toString();
        }
        if (value instanceof BigDecimal b) {
            return jsNumber(b);
        }
        if (value instanceof Double d) {
            return jsDouble(d, Double.toString(d));
        }
        if (value instanceof Float f) {
            return jsDouble(f.doubleValue(), Float.toString(f));
        }
        if (value instanceof Number n) {
            return jsNumber(new BigDecimal(n.toString()));
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        return value.toString();
    }

    private static String jsDouble(double d, String javaText) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        return jsNumber(new BigDecimal(javaText));
    }

    private static String jsNumber(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        BigDecimal abs = stripped.abs();
        if (abs.compareTo(UPPER_PLAIN) < 0 && abs.compareTo(LOWER_PLAIN) >= 0) {
            return stripped.toPlainString();
        }
        // JavaScript exponential form: 1e+21, 1.5e-7
        String digits = abs.unscaledValue().toString();
        int exponent = digits.length() - 1 - abs.scale();
        StringBuilder sb = new StringBuilder();
        if (stripped.signum() < 0) {
            sb.append('-');
        }
        sb.append(digits.charAt(0));
        if (digits.length() > 1) {
            sb.append('.').append(digits, 1, digits.length());
        }
        sb.append('e').append(exponent >= 0 ? '+' : '-').append(Math.abs(exponent));
        return sb.toString();
    }

    /**
     * For ordered comparisons (GREATER_THAN, BETWEEN, ...) the engine uses {@code Number(cell)} or raw
     * relational operators, under which a date cell evaluates to epoch milliseconds. Temporal values are
     * therefore converted to epoch milliseconds; numbers, text and booleans pass through unchanged.
     */
    public static Object toComparable(Object value, ZoneId zone) {
        if (value instanceof Instant i) {
            return i.toEpochMilli();
        }
        if (value instanceof ZonedDateTime z) {
            return z.toInstant().toEpochMilli();
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant().toEpochMilli();
        }
        if (value instanceof LocalDateTime l) {
            return l.atZone(zone).toInstant().toEpochMilli();
        }
        if (value instanceof LocalDate d) {
            return d.atStartOfDay(zone).toInstant().toEpochMilli();
        }
        if (value instanceof Date d) {
            return d.getTime();
        }
        if (value instanceof Calendar c) {
            return c.getTimeInMillis();
        }
        if (value instanceof Number || value instanceof String || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported value for an ordered comparison: "
                + value.getClass().getName() + " (use a number, text or a java.time value)");
    }
}
