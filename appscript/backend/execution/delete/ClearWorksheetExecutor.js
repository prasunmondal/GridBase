class ClearWorksheetExecutor {

    execute(operation) {

        const spreadsheet =
            SpreadsheetApp.openById(
                operation.getSpreadsheetId()
            );

        const worksheet =
            spreadsheet.getSheetByName(
                operation.getWorksheet()
            );

        if (!worksheet) {

            throw new Error(
                "Worksheet not found: " +
                operation.getWorksheet()
            );

        }

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