class ExecutionService {

    constructor() {

        this.engine =
            new ExecutionEngine();

        this.planBuilder =
            new ExecutionPlanBuilder();

        this.resultMapper =
            new ResultMapper();

        this.createWorksheetExecutor =
            new CreateWorksheetExecutor();

        this.worksheetColumnExecutor =
            new WorksheetColumnExecutor();

        this.clearWorksheetExecutor =
            new ClearWorksheetExecutor();
    }


    execute(context) {

        for (
            const operation
            of context.request.getOperations()
            ) {

            this.executeOperation(
                context,
                operation
            );

        }

        context.provider.commitAll();

    }

    executeOperation(context,
                     operation) {

        switch (operation.getType()) {

            case OperationType.CREATE_WORKSHEET:

                return this.executeCreateWorksheet(
                    context,
                    operation
                );

            case OperationType.GET_COLUMNS:

                return this.executeGetColumns(
                    context,
                    operation
                );

            case OperationType.ADD_COLUMNS:

                return this.executeAddColumns(
                    context,
                    operation
                );

            case OperationType.CLEAR_WORKSHEET:

                return this.executeClearWorksheet(
                    context,
                    operation
                );

            default:

                return this.executeRowOperation(
                    context,
                    operation
                );

        }

    }

    executeClearWorksheet(context,
                          operation) {

        const result =
            this.clearWorksheetExecutor.execute(
                operation
            );

        context.response.addResult({

            operationId:
                operation.getId(),

            worksheet:
                operation.getWorksheet(),

            rowsCleared:
            result.rowsCleared,

            columnsCleared:
            result.columnsCleared

        });

    }

    executeGetColumns(context,
                      operation) {

        const columns =
            this.worksheetColumnExecutor
                .getColumns(
                    operation
                );

        context.response.addResult({

            operationId:
                operation.getId(),

            worksheet:
                operation.getWorksheet(),

            columns:
            columns

        });

    }


    executeAddColumns(context,
                      operation) {

        const result =
            this.worksheetColumnExecutor
                .addColumns(
                    operation
                );

        context.response.addResult({

            operationId:
                operation.getId(),

            worksheet:
                operation.getWorksheet(),

            columns:
            result.columns,

            skippedColumns:
            result.skippedColumns,

            startColumn:
            result.startColumn,

            count:
            result.count

        });

    }

    executeCreateWorksheet(context,
                           operation) {

        const result =
            this.createWorksheetExecutor.execute(
                operation
            );

        context.response.addResult({

            operationId:
                operation.getId(),

            worksheet:
            result.worksheet,

            sheetId:
            result.sheetId

        });

    }

    executeRowOperation(context,
                        operation) {

        const worksheetData =
            context.provider.getWorksheetData(
                context,
                operation.getSpreadsheetId(),
                operation.getWorksheet()
            );

        const plan =
            this.planBuilder.build(
                context,
                operation
            );

        const execution =
            this.engine.execute(
                context,
                worksheetData,
                plan
            );

        const apiResult =
            this.resultMapper.map(
                execution.compiledPlan,
                execution.result,
                worksheetData
            );

        context.response.addResult(
            apiResult
        );

        context.getDebug().add(
            "Worksheet",
            worksheetData.worksheet
        );

        context.getDebug().add(
            "Rows Loaded",
            worksheetData.getRows().length
        );

        context.getDebug().add(
            "Schema",
            worksheetData.getSchema()
        );

        context.getDebug().add(
            "Predicates",
            plan.getPredicates().length
        );

    }

}