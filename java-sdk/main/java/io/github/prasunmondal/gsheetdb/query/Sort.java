package io.github.prasunmondal.gsheetdb.query;

import java.util.Objects;

/** One {@code orderBy} entry. Sorting happens on raw cell values on the server. */
public record Sort(String column, Direction direction) {

    public enum Direction { ASC, DESC }

    public Sort {
        if (column == null || column.isEmpty()) {
            throw new IllegalArgumentException("Sort column must not be empty");
        }
        Objects.requireNonNull(direction, "direction");
    }

    public static Sort asc(String column) {
        return new Sort(column, Direction.ASC);
    }

    public static Sort desc(String column) {
        return new Sort(column, Direction.DESC);
    }
}
