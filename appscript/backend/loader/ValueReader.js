class ValueReader {

    constructor(resourceManager) {
        this.resources = resourceManager;
    }

    /**
     * Header row and every data row, in one read.
     */
    read(reference) {

        const values =
            this.worksheet(reference)
                .getDataRange()
                .getValues();

        //
        // The data range of an empty sheet is still A1.
        //
        if (values.length === 1 &&
            values[0].length === 1 &&
            values[0][0] === "") {

            return new RawWorksheet([]);

        }

        return new RawWorksheet(values);

    }

    /**
     * Only the header row, as wide as the widest row.
     */
    readHeaders(reference) {

        const worksheet =
            this.worksheet(reference);

        const lastColumn =
            worksheet.getLastColumn();

        if (lastColumn === 0) {
            return new RawWorksheet([]);
        }

        return new RawWorksheet(
            worksheet
                .getRange(1, 1, 1, lastColumn)
                .getValues()
        );

    }

    /**
     * The data rows (sheet row 2 on), as wide as the schema.
     */
    readRows(reference, columnCount) {

        const worksheet =
            this.worksheet(reference);

        const lastRow =
            worksheet.getLastRow();

        if (lastRow < 2 || columnCount === 0) {
            return [];
        }

        return worksheet
            .getRange(2, 1, lastRow - 1, columnCount)
            .getValues();

    }

    worksheet(reference) {

        return this.resources.worksheets.get(
            reference.spreadsheetId,
            reference.worksheet
        );

    }
}
