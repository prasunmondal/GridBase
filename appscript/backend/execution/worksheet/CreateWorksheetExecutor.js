class CreateWorksheetExecutor {

    execute(resources,
            operation) {

        const worksheetName =
            operation.getWorksheet();

        const spreadsheet =
            resources.spreadsheets.get(
                operation.getSpreadsheetId()
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