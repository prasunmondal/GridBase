class WorksheetCommitter {

    //
    // A commit has two phases. prepare() does every sheet read the
    // commit needs (last row, last column, grid size); write() only
    // writes. GoogleSheetsProvider prepares every worksheet of the
    // request before it writes any: a read after a buffered write makes
    // Apps Script send that write and wait for the spreadsheet to
    // apply it (and recalculate), one worksheet at a time.
    //
    // Returns null when the worksheet has nothing to commit.
    //
    prepare(worksheetData, resources) {

        const changeSet =
            worksheetData.getChangeSet();

        if (!worksheetData.cleared &&
            changeSet.created.length === 0 &&
            changeSet.updated.length === 0 &&
            changeSet.deleted.length === 0) {

            return null;

        }

        const worksheet =
            resources.worksheets.get(
                worksheetData.spreadsheetId,
                worksheetData.worksheet
            );

        //
        // A row this request added and then deleted is never written.
        //
        const created =
            changeSet.created.filter(function (row) {
                return row.state !== RowState.DELETED;
            });

        const plan = {
            data: worksheetData,
            worksheet: worksheet,
            changeSet: changeSet,
            created: created,
            cleared: worksheetData.cleared,
            clearRows: 0,
            clearColumns: 0,
            startRow: 0,
            maxRows: 0
        };

        let lastRow = -1;

        if (plan.cleared) {

            lastRow =
                worksheet.getLastRow();

            const lastColumn =
                worksheet.getLastColumn();

            if (lastRow > 1 &&
                lastColumn > 0) {

                plan.clearRows = lastRow - 1;

                plan.clearColumns = lastColumn;

            }

            //
            // After the clear only the header has content, so the
            // inserted rows go right below it.
            //
            lastRow = Math.min(lastRow, 1);

        }

        if (created.length > 0) {

            if (lastRow < 0) {
                lastRow = worksheet.getLastRow();
            }

            plan.startRow = lastRow + 1;

            plan.maxRows = worksheet.getMaxRows();

        }

        return plan;

    }

    //
    // Writes a prepared plan without reading the sheet. Returns what
    // was written, for the commit timing in the response.
    //
    write(plan) {

        //
        // First, so the rows inserted after the clear are appended
        // below the header. The rows themselves (and their
        // formatting) stay.
        //
        if (plan.clearRows > 0) {

            plan.worksheet
                .getRange(
                    2,
                    1,
                    plan.clearRows,
                    plan.clearColumns
                )
                .clearContent();

        }

        plan.data.cleared = false;

        this.commitCreated(plan);

        const updated =
            this.commitUpdated(
                plan.worksheet,
                plan.changeSet.updated
            );

        const deleted =
            this.commitDeleted(
                plan.worksheet,
                plan.changeSet.deleted
            );

        plan.changeSet.clear();

        return {
            spreadsheetId: plan.data.spreadsheetId,
            worksheet: plan.data.worksheet,
            cleared: plan.cleared,
            inserted: plan.created.length,
            updated: updated,
            deleted: deleted
        };

    }

    commitCreated(plan) {

        const created = plan.created;

        if (created.length === 0) {
            return;
        }

        const values = [];

        for (const row of created) {
            values.push(row.values);
        }

        const startRow = plan.startRow;

        const lastRow = startRow + values.length - 1;

        //
        // A range below the last row of the grid is an error,
        // so a full sheet gets the missing rows first.
        //
        if (lastRow > plan.maxRows) {

            plan.worksheet.insertRowsAfter(
                plan.maxRows,
                lastRow - plan.maxRows
            );

        }

        plan.worksheet
            .getRange(
                startRow,
                1,
                values.length,
                plan.data.getColumnCount()
            )
            .setValues(values);

        for (let i = 0; i < created.length; i++) {
            created[i].sheetRow = startRow + i;
        }

    }

    commitUpdated(worksheet,
                  rows) {

        //
        // Only rows read from the sheet, each once. Rows this request
        // added go out with the append, with their final values;
        // deleted rows are removed anyway.
        //
        const seen = new Set();

        const changed = [];

        for (const row of rows) {

            if (row.state !== RowState.MODIFIED ||
                seen.has(row)) {

                continue;

            }

            seen.add(row);

            changed.push(row);

        }

        if (changed.length === 0) {
            return 0;
        }

        changed.sort(function (a, b) {
            return a.sheetRow - b.sheetRow;
        });

        //
        // Adjacent rows are written as one block that spans the
        // columns any of them changed. Other cells keep what the
        // sheet holds, formulas included.
        //
        let start = 0;

        while (start < changed.length) {

            let end = start;

            let first = changed[start].firstChanged;

            let last = changed[start].lastChanged;

            while (end + 1 < changed.length &&
                changed[end + 1].sheetRow === changed[end].sheetRow + 1) {

                end++;

                first = Math.min(first, changed[end].firstChanged);

                last = Math.max(last, changed[end].lastChanged);

            }

            const block = [];

            for (let i = start; i <= end; i++) {
                block.push(changed[i].values.slice(first, last + 1));
            }

            worksheet
                .getRange(
                    changed[start].sheetRow,
                    first + 1,
                    block.length,
                    last - first + 1
                )
                .setValues(block);

            start = end + 1;

        }

        return changed.length;

    }

    commitDeleted(worksheet,
                  rows) {

        if (rows.length === 0) {
            return 0;
        }

        //
        // One deletion per sheet row, even if the row was
        // queued more than once; rows never written (-1) are skipped.
        //
        const sheetRows =
            Array.from(
                new Set(
                    rows
                        .map(function (row) { return row.sheetRow; })
                        .filter(function (n) { return n > 1; })
                )
            );

        //
        // Delete from bottom to top so earlier deletions do not
        // shift the rows still to be deleted. Adjacent rows go in
        // one deleteRows call.
        //
        sheetRows.sort(function (a, b) {
            return b - a;
        });

        let i = 0;

        while (i < sheetRows.length) {

            const bottom = sheetRows[i];

            let top = bottom;

            while (i + 1 < sheetRows.length &&
                sheetRows[i + 1] === top - 1) {

                top--;

                i++;

            }

            worksheet.deleteRows(
                top,
                bottom - top + 1
            );

            i++;

        }

        return sheetRows.length;

    }

}
