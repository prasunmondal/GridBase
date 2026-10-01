package io.github.prasunmondal.hibernatesheets.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.prasunmondal.hibernatesheets.internal.JsCompat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One worksheet row as returned by the engine: column name to cell value, in sheet order.
 *
 * <p>Raw values are whatever the engine sent: {@code String}, {@code Integer}/{@code Long}/{@code Double},
 * {@code Boolean}, or {@code null}. Empty cells arrive as {@code ""}; every typed getter treats
 * {@code null} and {@code ""} alike and returns {@code null}. Date cells arrive as ISO-8601 UTC strings
 * (JavaScript {@code JSON.stringify(Date)}); {@link #getLocalDate} / {@link #getLocalDateTime} convert
 * them using the client's configured time zone.</p>
 */
public final class Row {

    private final Map<String, Object> values;
    private final ObjectMapper mapper;
    private final ZoneId zone;

    public Row(Map<String, Object> values, ObjectMapper mapper, ZoneId zone) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** Column names in this row (projection order for SELECT with columns, sheet order otherwise). */
    public Set<String> columns() {
        return values.keySet();
    }

    public boolean has(String column) {
        return values.containsKey(column);
    }

    /** Raw cell value. */
    public Object get(String column) {
        return values.get(column);
    }

    /** True when the cell is null, missing or {@code ""}. */
    public boolean isBlank(String column) {
        Object v = values.get(column);
        return v == null || (v instanceof String s && s.isEmpty());
    }

    /** Cell as text, rendered the way the engine compares it ({@code 5.0} becomes {@code "5"}). */
    public String getString(String column) {
        Object v = values.get(column);
        return v == null ? null : JsCompat.toJsString(v);
    }

    public Integer getInteger(String column) {
        BigDecimal d = getBigDecimal(column);
        return d == null ? null : d.intValueExact();
    }

    public Long getLong(String column) {
        BigDecimal d = getBigDecimal(column);
        return d == null ? null : d.longValueExact();
    }

    public Double getDouble(String column) {
        BigDecimal d = getBigDecimal(column);
        return d == null ? null : d.doubleValue();
    }

    public BigDecimal getBigDecimal(String column) {
        if (isBlank(column)) {
            return null;
        }
        Object v = values.get(column);
        try {
            if (v instanceof BigDecimal b) {
                return b;
            }
            if (v instanceof BigInteger b) {
                return new BigDecimal(b);
            }
            if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                return BigDecimal.valueOf(((Number) v).longValue());
            }
            if (v instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (v instanceof Boolean b) {
                return b ? BigDecimal.ONE : BigDecimal.ZERO;
            }
            return new BigDecimal(v.toString().trim().replace(",", ""));
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalStateException("Column '" + column + "' is not numeric: " + v, e);
        }
    }

    /** Accepts real booleans and the text values {@code true/false/yes/no/1/0} (case-insensitive). */
    public Boolean getBoolean(String column) {
        if (isBlank(column)) {
            return null;
        }
        Object v = values.get(column);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        switch (v.toString().trim().toLowerCase()) {
            case "true":
            case "yes":
            case "y":
            case "1":
                return true;
            case "false":
            case "no":
            case "n":
            case "0":
                return false;
            default:
                throw new IllegalStateException("Column '" + column + "' is not a boolean: " + v);
        }
    }

    /**
     * Date cells come back as ISO instants; numbers are treated as epoch milliseconds;
     * plain dates/date-times are interpreted in the client's time zone.
     */
    public Instant getInstant(String column) {
        if (isBlank(column)) {
            return null;
        }
        Object v = values.get(column);
        if (v instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        String s = v.toString().trim();
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(s).atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(s).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Column '" + column + "' is not a date/time: " + v, e);
        }
    }

    /** The calendar date of the cell in the client's time zone. */
    public LocalDate getLocalDate(String column) {
        Instant i = getInstant(column);
        return i == null ? null : i.atZone(zone).toLocalDate();
    }

    public LocalDateTime getLocalDateTime(String column) {
        Instant i = getInstant(column);
        return i == null ? null : i.atZone(zone).toLocalDateTime();
    }

    /**
     * Maps the row onto a POJO or record with Jackson. Column names map to property names
     * (use {@code @JsonProperty("Column Name")} when they differ). Empty cells become {@code null}.
     */
    public <T> T as(Class<T> type) {
        try {
            return mapper.convertValue(values, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot map row to " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    /** Unmodifiable view of the raw values. */
    public Map<String, Object> asMap() {
        return values;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Row other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "Row" + values;
    }
}
