class SelectExecutor {

    constructor() {

        this.executor =
            new ExecutionExecutor();

    }

    execute(context,
            worksheetData,
            compiledPlan) {

        const executionStats =
            context.statistics.execution;

        const execute =
            Stopwatch.measure(() => {

                return this.executor.execute(
                    context,
                    worksheetData,
                    compiledPlan
                );

            });

        executionStats.executeTime =
            execute.elapsed;

        executionStats.totalTime =
            executionStats.compileTime +
            executionStats.executeTime;

        return execute.result;

    }

}