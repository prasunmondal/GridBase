class ExecutionPlanBuilder {

    build(context, operation) {

        const plan =
            new ExecutionPlan();

        plan.setId(operation.getId());
        plan.setType(operation.getType());

        for (const predicate of operation.getPredicates()) {

            plan.addPredicate(predicate);

        }

        plan.setLimit(operation.getLimit());

        plan.setOffset(
            operation.getOffset()
        );

        for (const order of operation.getOrderBy()) {
            plan.addOrderBy(order);
        }

        for (const projection of operation.getProjections()) {
            plan.addProjection(projection);
        }

        for (const value of operation.getValues()) {

            plan.addValue(value);

        }

        for (const row of operation.getRows()) {

            plan.addRow(row);

        }

        // console.log("Execution Plan: " + JSON.stringify(plan));
        context.getDebug().add("Execution Plan", plan);
        return plan;

    }

}