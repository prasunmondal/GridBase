package io.github.prasunmondal.gridbase.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One condition of a {@code where} clause. Build instances with {@link Filters}.
 * All filters of an operation are combined with AND (the engine has no OR).
 */
public final class Filter {

    private final String column;
    private final Operator operator;
    private final Object value;
    private final List<Object> values;
    private final Object minimum;
    private final Object maximum;

    private Filter(String column, Operator operator, Object value,
                   List<Object> values, Object minimum, Object maximum) {
        this.column = requireColumn(column);
        this.operator = Objects.requireNonNull(operator, "operator");
        this.value = value;
        this.values = values;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    static Filter binary(String column, Operator operator, Object value) {
        return new Filter(column, operator, value, null, null, null);
    }

    static Filter unary(String column, Operator operator) {
        return new Filter(column, operator, null, null, null, null);
    }

    static Filter in(String column, List<?> values) {
        Objects.requireNonNull(values, "values");
        return new Filter(column, Operator.IN, null,
                Collections.unmodifiableList(new ArrayList<>(values)), null, null);
    }

    static Filter between(String column, Object minimum, Object maximum) {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        return new Filter(column, Operator.BETWEEN, null, null, minimum, maximum);
    }

    private static String requireColumn(String column) {
        if (column == null || column.isEmpty()) {
            throw new IllegalArgumentException("Filter column must not be empty");
        }
        return column;
    }

    public String column() {
        return column;
    }

    public Operator operator() {
        return operator;
    }

    /** Operand of binary operators. */
    public Object value() {
        return value;
    }

    /** Operands of {@link Operator#IN}. */
    public List<Object> values() {
        return values;
    }

    public Object minimum() {
        return minimum;
    }

    public Object maximum() {
        return maximum;
    }

    @Override
    public String toString() {
        switch (operator) {
            case IS_NULL:
            case IS_NOT_NULL:
                return column + " " + operator;
            case IN:
                return column + " IN " + values;
            case BETWEEN:
                return column + " BETWEEN " + minimum + " AND " + maximum;
            default:
                return column + " " + operator + " " + value;
        }
    }
}
