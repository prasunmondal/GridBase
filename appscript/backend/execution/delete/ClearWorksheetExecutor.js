class ClearWorksheetExecutor {

    //
    // Staged like a row operation: the in-memory rows are dropped
    // now, and the sheet is cleared by the commit at the end of the
    // request. If a later operation (the INSERT of a save) fails,
    // the sheet is left as it was.
    //
    execute(context,
            operation) {

        const worksheet =
            context.resources.worksheets.get(
                operation.getSpreadsheetId(),
                operation.getWorksheet()
            );

        const lastRow =
            worksheet.getLastRow();

        const lastColumn =
            worksheet.getLastColumn();

        const worksheetData =
            context.provider.getWorksheetData(
                context,
                operation.getSpreadsheetId(),
                operation.getWorksheet(),
                false
            );

        worksheetData.clearRows();

        return {

            //
            // Preserve row 1 (headers)
            //
            rowsCleared:
                lastColumn === 0
                    ? 0
                    : Math.max(lastRow - 1, 0),

            columnsCleared:
                lastColumn

        };

    }

}
