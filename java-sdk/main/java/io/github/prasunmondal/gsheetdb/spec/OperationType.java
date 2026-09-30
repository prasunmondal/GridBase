package io.github.prasunmondal.gsheetdb.spec;

/** Operation types accepted by the engine (mirrors {@code backend/model/OperationType.js}). */
public enum OperationType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    UPSERT,
    CLONE,
    CREATE_WORKSHEET,
    CLEAR_WORKSHEET,
    GET_COLUMNS,
    ADD_COLUMNS;

    /** True for operations that never modify the spreadsheet (safe to retry). */
    public boolean isReadOnly() {
        return this == SELECT || this == GET_COLUMNS;
    }

    /** True for operations whose result is a list of rows. */
    public boolean returnsRows() {
        switch (this) {
            case SELECT:
            case INSERT:
            case UPDATE:
            case DELETE:
            case UPSERT:
            case CLONE:
                return true;
            default:
                return false;
        }
    }
}
