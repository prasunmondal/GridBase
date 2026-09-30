class ExecutionPaginator {

    paginate(result, compiledPlan) {

        const offset = compiledPlan.getOffset();
        const limit = compiledPlan.getLimit();

        let rows = result.getRows();

        if (offset > 0) {
            rows = rows.slice(offset);
        }

        if (limit >= 0) {
            rows = rows.slice(0, limit);
        }

        result.setRows(rows);
    }

}