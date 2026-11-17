package io.github.prasunmondal.gridbase.result;

import io.github.prasunmondal.gridbase.internal.Compat;

import java.util.List;

/**
 * How long the engine took to write a request's row changes to the sheets (engines that report it;
 * see {@link ExecutionResponse#commitTiming()}).
 *
 * <p>Apps Script buffers sheet writes, so {@link #writeTimeMillis()} is mostly the time to queue them.
 * The spreadsheet applies them, and recalculates the formulas that depend on them, during the flush:
 * a slow commit usually shows up in {@link #flushTimeMillis()}.
 *
 * @param prepareTimeMillis reading what the commit needs from the sheets (last row, grid size), done
 *                          for every worksheet before the first write
 * @param writeTimeMillis   the writes of every worksheet; the sum of their {@link Worksheet#timeMillis()}
 * @param flushTimeMillis   {@code SpreadsheetApp.flush()} before the lock is released; 0 for a request
 *                          that does not write
 * @param worksheets        one entry per worksheet that had changes, in commit order
 */
public record CommitTiming(long prepareTimeMillis, long writeTimeMillis, long flushTimeMillis,
                           List<Worksheet> worksheets) {

    public CommitTiming {
        worksheets = Compat.copyOf(worksheets);
    }

    /** prepare + write + flush. */
    public long totalTimeMillis() {
        return prepareTimeMillis + writeTimeMillis + flushTimeMillis;
    }

    /**
     * The changes written to one worksheet.
     *
     * @param cleared    whether its rows were cleared first (replace-all)
     * @param inserted   rows appended
     * @param updated    existing rows rewritten
     * @param deleted    sheet rows deleted
     * @param timeMillis time spent on its writes
     */
    public record Worksheet(String spreadsheetId, String worksheet, boolean cleared,
                            int inserted, int updated, int deleted, long timeMillis) {
    }
}
