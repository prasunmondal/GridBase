package io.github.prasunmondal.gsheetdb.query;

/** Filter operators understood by the engine's {@code RequestParser.parsePredicate}. */
public enum Operator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_EQUALS,
    LESS_THAN,
    LESS_THAN_EQUALS,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    IN,
    BETWEEN,
    IS_NULL,
    IS_NOT_NULL
}
