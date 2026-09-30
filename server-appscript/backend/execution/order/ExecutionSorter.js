class ExecutionSorter {

    sort(result, compiledPlan) {

        const orderBy = compiledPlan.getOrderBy();

        if (!orderBy || orderBy.length === 0) {
            return;
        }

        result.rows.sort(function (a, b) {

            for (let i = 0; i < orderBy.length; i++) {

                const rule = orderBy[i];

                const left = a.values[rule.columnIndex];
                const right = b.values[rule.columnIndex];

                if (left === right) {
                    continue;
                }

                if (rule.direction === OrderDirection.ASC) {
                    return left < right ? -1 : 1;
                }

                return left > right ? -1 : 1;

            }

            return 0;

        });

    }

}