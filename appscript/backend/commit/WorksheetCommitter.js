class WorksheetCommitter {

    commit(worksheetData, resources) {

        const changeSet =
            worksheetData.getChangeSet();

        if (changeSet.created.length === 0 &&
            changeSet.updated.length === 0 &&
            changeSet.deleted.length === 0) {

            return;

        }

        const worksheet =
            resources.worksheets.get(
                worksheetData.spreadsheetId,
                worksheetData.worksheet
            );

        this.commitCreated(
            worksheet,
            worksheetData,
            changeSet.created
        );

        this.commitUpdated(
            worksheet,
            changeSet.updated
        );

        this.commitDeleted(
            worksheet,
            changeSet.deleted
        );

        changeSet.clear();

    }

    commitCreated(worksheet,
                  worksheetData,
                  rows) {

        //
        // A row this request added and then deleted is never written.
        //
        const created =
            rows.filter(function (row) {
                return row.state !== RowState.DELETED;
            });

        if (created.length === 0) {
            return;
        }

        const values = [];

        for (const row of created) {
            values.push(row.values);
        }

        const startRow =
            worksheet.getLastRow() + 1;

        this.ensureRows(
            worksheet,
            startRow + values.length - 1
        );

        worksheet
            .getRange(
                startRow,
                1,
                values.length,
                worksheetData.getColumnCount()
            )
            .setValues(values);

        for (let i = 0; i < created.length; i++) {
            created[i].sheetRow = startRow + i;
        }

    }

    //
    // A range below the last row of the grid is an error,
    // so a full sheet gets the missing rows first.
    //
    ensureRows(worksheet,
               lastRow) {

        const maxRows =
            worksheet.getMaxRows();

        if (lastRow > maxRows) {

            worksheet.insertRowsAfter(
                maxRows,
                lastRow - maxRows
            );

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
            return;
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

    }

    commitDeleted(worksheet,
                  rows) {

        if (rows.length === 0) {
            return;
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

    }

}
