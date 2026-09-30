class CloneExecutor {

    constructor() {

        this.executor =
            new ExecutionExecutor();

    }

    execute(context,
            worksheetData,
            compiledPlan) {

        const result =
            new ExecutionResult();

        //
        // Find source rows using the existing query engine
        //
        const sourceRows =
            this.executor.execute(
                context,
                worksheetData,
                compiledPlan
            );

        //
        // Nothing matched
        //
        if (sourceRows.isEmpty()) {

            throw new Error(
                "No matching row found for CLONE."
            );

        }

        const valuesToApply =
            compiledPlan.getValues();

        //
        // Clone every matching row
        //
        for (const sourceRow of sourceRows.getRows()) {

            //
            // Clone values
            //
            const values =
                sourceRow.values.slice();

            //
            // Apply SET values
            //
            for (const value of valuesToApply) {

                values[value.columnIndex] =

                    value.apply(
                        values[value.columnIndex]
                    );

            }

            //
            // Create cloned row
            //
            const clonedRow =
                new DataRow(
                    -1,
                    values
                );

            clonedRow.markNew();

            //
            // Add to worksheet
            //
            worksheetData.addRow(
                clonedRow
            );

            //
            // Track change
            //
            worksheetData
                .getChangeSet()
                .created
                .push(clonedRow);

            //
            // Return cloned row
            //
            result.addRow(
                clonedRow
            );

            result.inserted++;

        }

        return result;

    }
}