class ClearWorksheetExecutor {

    execute(resources,
            operation) {

        const worksheet =
            resources.worksheets.get(
                operation.getSpreadsheetId(),
                operation.getWorksheet()
            );

        const lastRow =
            worksheet.getLastRow();

        const lastColumn =
            worksheet.getLastColumn();

        //
        // Nothing to clear
        //
        if (lastRow <= 1 ||
            lastColumn === 0) {

            return {

                rowsCleared: 0,

                columnsCleared:
                    lastColumn

            };

        }

        //
        // Preserve row 1 (headers)
        //
        const dataRows =
            lastRow - 1;

        worksheet
            .getRange(
                2,
                1,
                dataRows,
                lastColumn
            )
            .clearContent();

        return {

            rowsCleared:
                dataRows,

            columnsCleared:
                lastColumn

        };

    }

}