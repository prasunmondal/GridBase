class InsertExecutor {

    execute(context,
            worksheetData,
            compiledPlan) {

        const result =
            new ExecutionResult();

        const rows =
            compiledPlan.getRows();

        //
        // BULK INSERT
        //
        if (rows &&
            rows.length > 0) {

            for (const compiledRow of rows) {

                this.insertRow(
                    worksheetData,
                    compiledRow,
                    result
                );

            }

            return result;

        }

        //
        // EXISTING SINGLE INSERT
        //
        const values =
            new Array(
                worksheetData.getColumnCount()
            ).fill("");

        const compiledValues =
            compiledPlan.getValues();

        for (const value of compiledValues) {

            values[value.columnIndex] =
                value.apply("");

        }

        const row =
            new DataRow(
                -1,
                values
            );

        row.markNew();

        worksheetData.addRow(row);

        worksheetData
            .getChangeSet()
            .created
            .push(row);

        result.addRow(row);

        return result;
    }

    insertRow(worksheetData,
              compiledRow,
              result) {

        const values =
            new Array(
                worksheetData.getColumnCount()
            ).fill("");

        for (const value of compiledRow.getValues()) {

            values[value.columnIndex] =
                value.apply("");

        }

        const row =
            new DataRow(
                -1,
                values
            );

        row.markNew();

        worksheetData.addRow(row);

        worksheetData
            .getChangeSet()
            .created
            .push(row);

        result.addRow(row);
    }

}