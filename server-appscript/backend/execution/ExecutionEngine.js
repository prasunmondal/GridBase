class ExecutionEngine {

    constructor(compiler, executor) {

        this.compiler =
            compiler ?? new ExecutionCompiler();

        this.executor =
            executor ?? new ExecutionExecutor();

        this.selectExecutor =
            new SelectExecutor();

        this.insertExecutor =
            new InsertExecutor();

        this.updateExecutor =
            new UpdateExecutor();

        this.deleteExecutor =
            new DeleteExecutor();

        this.upsertExecutor =
            new UpsertExecutor();

        this.cloneExecutor =
            new CloneExecutor();

        this.executionSorter =
            new ExecutionSorter();

        this.paginator =
            new ExecutionPaginator();

    }

    execute(context,
            worksheetData,
            executionPlan) {

        const executionStats =
            context.statistics.execution;

        const compile =
            Stopwatch.measure(() => {

                return this.compiler.compile(
                    context,
                    worksheetData,
                    executionPlan
                );

            });

        executionStats.compileTime =
            compile.elapsed;

        const compiledPlan =
            compile.result;

        let result;

        switch (compiledPlan.getType()) {

            case OperationType.SELECT:

                result =
                    this.selectExecutor.execute(
                        context,
                        worksheetData,
                        compiledPlan
                    );

                break;

            case OperationType.INSERT:

                result =
                    this.insertExecutor.execute(
                        context,
                        worksheetData,
                        compiledPlan
                    );

                break;

            case OperationType.UPDATE:

                result =
                    this.updateExecutor.execute(
                        context,
                        worksheetData,
                        compiledPlan
                    );

                break;

            case OperationType.DELETE:

                result =
                    this.deleteExecutor.execute(
                        context,
                        worksheetData,
                        compiledPlan
                    );

                break;

            case OperationType.UPSERT:

                result =
                    this.upsertExecutor.execute(
                        context,
                        worksheetData,
                        compiledPlan
                    );

                break;

            case OperationType.CLONE:

                result =
                    this.cloneExecutor.execute(
                        context,
                        worksheetData,
                        compiledPlan
                    );

                break;

            default:

                throw new Error(
                    "Unsupported operation type: " +
                    compiledPlan.getType()
                );

        }

        return {
            compiledPlan: compiledPlan,
            result: result
        };
    }
}