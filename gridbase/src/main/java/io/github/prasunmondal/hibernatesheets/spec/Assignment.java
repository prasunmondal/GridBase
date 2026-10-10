package io.github.prasunmondal.hibernatesheets.spec;

import java.util.Objects;

/**
 * A value written to one column. {@code SET} replaces the cell, {@code APPEND}/{@code PREPEND}
 * concatenate text to the current cell value (mirrors {@code backend/write/ValueOperation.js}).
 */
public record Assignment(String column, Object value, Kind kind) {

    public enum Kind { SET, APPEND, PREPEND }

    public Assignment {
        if (column == null || column.isEmpty()) {
            throw new IllegalArgumentException("Column name must not be empty");
        }
        Objects.requireNonNull(kind, "kind");
    }
}
