class CreateWorksheetExecutor {

    execute(operation) {

        const spreadsheetId =
            operation.getSpreadsheetId();

        const worksheetName =
            operation.getWorksheet();

        const spreadsheet =
            SpreadsheetApp.openById(
                spreadsheetId
            );

        const existing =
            spreadsheet.getSheetByName(
                worksheetName
            );

        if (existing) {

            throw new Error(
                "Worksheet already exists: " +
                worksheetName
            );

        }

        const worksheet =
            spreadsheet.insertSheet(
                worksheetName
            );

        return {
            worksheet:
            worksheetName,

            sheetId:
                worksheet.getSheetId()
        };

    }

}