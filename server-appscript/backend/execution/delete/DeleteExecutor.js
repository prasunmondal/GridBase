class DeleteExecutor {

    constructor() {

        this.executor =
            new ExecutionExecutor();

    }

    execute(context,
            worksheetData,
            compiledPlan) {

        const result =
            new ExecutionResult();

        const matchingRows =
            this.executor.execute(
                context,
                worksheetData,
                compiledPlan
            );

        for (const row of matchingRows.getRows()) {

            row.markDeleted();

            worksheetData
                .getChangeSet()
                .deleted
                .push(row);

            result.addRow(row);
        }

        result.deleted =
            result.getRowCount();

        return result;

    }

}