class DataRowFactory {

    //
    // The row keeps the array getValues() returned: nothing else
    // holds it, and copying it would double the memory of a load.
    //
    create(sheetRow, values) {

        return new DataRow(
            sheetRow,
            values
        );

    }

}
