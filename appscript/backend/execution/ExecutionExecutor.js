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

        const needed =
            this.rowsNeeded(compiledPlan);

        const matched =
            result.getRows();

        let examined = 0;

        for (let r = 0;
             r < rows.length && matched.length < needed;
             r++) {

            const row = rows[r];

            //
            // Rows deleted by an earlier operation in this request
            // no longer exist for SELECT / UPDATE / DELETE / CLONE.
            //
            if (row.state === RowState.DELETED) {
                continue;
            }

            examined++;

            if (!this.matches(
                context,
                row,
                predicates
            )) {
                continue;
            }

            matched.push(row);

        }

        executionStats.rowsExamined += examined;

        executionStats.rowsMatched += matched.length;

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

    //
    // Without ORDER BY the answer is the first offset + limit
    // matches in sheet order, so the scan can stop there.
    // Same reading of offset / limit as ExecutionPaginator.
    //
    rowsNeeded(compiledPlan) {

        const limit =
            compiledPlan.getLimit();

        const orderBy =
            compiledPlan.getOrderBy();

        if (limit < 0 ||
            (orderBy && orderBy.length > 0)) {

            return Infinity;

        }

        return Math.max(compiledPlan.getOffset(), 0) + limit;

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

