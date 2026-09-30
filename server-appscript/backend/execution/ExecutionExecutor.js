class ExecutionExecutor {

    constructor() {

        this.sorter =
            new ExecutionSorter();

        this.paginator =
            new ExecutionPaginator();

    }

    execute(context,
            worksheetData,
            compiledPlan) {

        const result =
            new ExecutionResult();

        const executionStats =
            context.statistics.execution;

        const rows =
            worksheetData.getRows();

        const predicates =
            compiledPlan.getPredicates();

        for (const row of rows) {

            //
            // Rows deleted by an earlier operation in this request
            // no longer exist for SELECT / UPDATE / DELETE / CLONE.
            //
            if (row.state === RowState.DELETED) {
                continue;
            }

            executionStats.rowsExamined++;

            if (!this.matches(
                context,
                row,
                predicates
            )) {
                continue;
            }

            executionStats.rowsMatched++;

            result.addRow(row);

        }

        //
        // ORDER BY
        //
        this.sorter.sort(
            result,
            compiledPlan
        );

        //
        // OFFSET / LIMIT
        //
        this.paginator.paginate(
            result,
            compiledPlan
        );

        return result;

    }

    matches(context,
            row,
            predicates) {

        if (!predicates) {
            return true;
        }

        for (const predicate of predicates) {

            if (!predicate.matches(row)) {
                return false;
            }

        }

        return true;

    }

}

