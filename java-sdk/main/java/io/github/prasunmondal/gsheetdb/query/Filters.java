package io.github.prasunmondal.gsheetdb.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Static factory for filters. Typical use: {@code import static ...query.Filters.*;}
 *
 * <p><b>How values are compared on the server</b> (the SDK converts values so these rules hold):</p>
 * <ul>
 *   <li>{@code eq / ne / contains / startsWith / endsWith} compare text. The cell is rendered with
 *       JavaScript's {@code String(cell)}, so pass numbers as numbers ({@code eq("qty", 5)}), not as
 *       formatted text ({@code "5.00"} would never match).</li>
 *   <li>{@code gt / gte / lt / lte / between} compare numbers. Dates and times ({@code Instant},
 *       {@code LocalDate}, ...) are sent as epoch milliseconds, which is how date cells compare in
 *       Apps Script.</li>
 *   <li>{@code in} uses strict equality on the raw cell value: numbers match numbers, text matches text.</li>
 * </ul>
 */
public final class Filters {

    private Filters() {
    }

    /** {@code column = value}; {@code eq(column, null)} is translated to {@link #isNull(String)}. */
    public static Filter eq(String column, Object value) {
        return value == null ? isNull(column) : Filter.binary(column, Operator.EQUALS, value);
    }

    /** {@code column <> value}; {@code ne(column, null)} is translated to {@link #isNotNull(String)}. */
    public static Filter ne(String column, Object value) {
        return value == null ? isNotNull(column) : Filter.binary(column, Operator.NOT_EQUALS, value);
    }

    public static Filter gt(String column, Object value) {
        return Filter.binary(column, Operator.GREATER_THAN, requireValue(value));
    }

    public static Filter gte(String column, Object value) {
        return Filter.binary(column, Operator.GREATER_THAN_EQUALS, requireValue(value));
    }

    public static Filter lt(String column, Object value) {
        return Filter.binary(column, Operator.LESS_THAN, requireValue(value));
    }

    public static Filter lte(String column, Object value) {
        return Filter.binary(column, Operator.LESS_THAN_EQUALS, requireValue(value));
    }

    /** Case-sensitive substring match. */
    public static Filter contains(String column, Object text) {
        return Filter.binary(column, Operator.CONTAINS, requireValue(text));
    }

    public static Filter startsWith(String column, Object prefix) {
        return Filter.binary(column, Operator.STARTS_WITH, requireValue(prefix));
    }

    public static Filter endsWith(String column, Object suffix) {
        return Filter.binary(column, Operator.ENDS_WITH, requireValue(suffix));
    }

    public static Filter in(String column, Object... values) {
        return Filter.in(column, Arrays.asList(values));
    }

    public static Filter in(String column, Collection<?> values) {
        return Filter.in(column, new ArrayList<>(values));
    }

    /** Inclusive range: {@code minimum <= column <= maximum}. */
    public static Filter between(String column, Object minimum, Object maximum) {
        return Filter.between(column, minimum, maximum);
    }

    /** Matches null, missing and empty ({@code ""}) cells. */
    public static Filter isNull(String column) {
        return Filter.unary(column, Operator.IS_NULL);
    }

    public static Filter isNotNull(String column) {
        return Filter.unary(column, Operator.IS_NOT_NULL);
    }

    private static Object requireValue(Object value) {
        return Objects.requireNonNull(value, "filter value must not be null (use isNull/isNotNull)");
    }
}
