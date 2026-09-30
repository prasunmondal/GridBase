class DataRowFactory {

    create(sheetRow, values) {

        return new DataRow(
            sheetRow,
            values.slice()
        );

    }

}