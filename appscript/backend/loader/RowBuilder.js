class RowBuilder {

    constructor() {
        this.factory = new DataRowFactory();
    }

    build(worksheetData, rawWorksheet) {

        let sheetRow = 2;

        for (const values of rawWorksheet.getRows()) {
            const row = this.factory.create(
                sheetRow,
                values
            );

            worksheetData.addRow(row);

            sheetRow++;
        }

    }

}