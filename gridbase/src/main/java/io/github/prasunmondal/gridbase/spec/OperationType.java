package io.github.prasunmondal.gridbase.spec;

/** Operation types accepted by the engine (mirrors {@code backend/model/OperationType.js}). */
public enum OperationType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    UPSERT,
    CLONE,
    REPLACE_ALL_DATA,
    CREATE_WORKSHEET,
    CLEAR_WORKSHEET,
    GET_COLUMNS,
    ADD_COLUMNS,
    GET_LAST_UPDATED;

    /** True for operations that never modify the spreadsheet (safe to retry). */
    public boolean isReadOnly() {
        return this == SELECT || this == GET_COLUMNS || this == GET_LAST_UPDATED;
    }

    /**
     * True for read-only operations whose replies may be served from the response cache. GET_LAST_UPDATED
     * is not: it exists to check whether cached data is stale.
     */
    public boolean isCacheable() {
        return isReadOnly() && this != GET_LAST_UPDATED;
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
            case REPLACE_ALL_DATA:
                return true;
            default:
                return false;
        }
    }
}
