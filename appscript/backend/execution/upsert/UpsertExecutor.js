class UpsertExecutor {

    constructor() {

        this.executor =
            new ExecutionExecutor();

        this.insertExecutor =
            new InsertExecutor();

        this.updateExecutor =
            new UpdateExecutor();

    }

    execute(context,
            worksheetData,
            compiledPlan) {

        const predicates =
            compiledPlan.getPredicates();

        const rows =
            worksheetData.getRows();

        //
        // Does a matching row already exist?
        //
        for (let i = 0; i < rows.length; i++) {

            if (rows[i].state === RowState.DELETED) {
                continue;
            }

            if (this.executor.matches(
                context,
                rows[i],
                predicates
            )) {

                //
                // Existing row found -> UPDATE
                //
                return this.updateExecutor.execute(
                    context,
                    worksheetData,
                    compiledPlan
                );

            }

        }

        //
        // No matching row -> INSERT
        //
        return this.insertExecutor.execute(
            context,
            worksheetData,
            compiledPlan
        );

    }

}

