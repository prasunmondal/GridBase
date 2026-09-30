class ValueReader {

    constructor(resourceManager) {
        this.resources = resourceManager;
    }

    read(reference) {

        const worksheet =
            this.resources.worksheets.get(
                reference.spreadsheetId,
                reference.worksheet
            );

        let values = [];

        if (worksheet.getLastRow() > 0) {

            values = worksheet
                .getRange(
                    1,
                    1,
                    worksheet.getLastRow(),
                    worksheet.getLastColumn()
                )
                .getValues();

        }

        return new RawWorksheet(values);

    }
}