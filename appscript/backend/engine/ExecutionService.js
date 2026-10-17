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

        context.scannedWorksheets =
            this.scannedWorksheets(
                context.request.getOperations()
            );

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

    //
    // Worksheets whose rows some operation of the request reads.
    // INSERT needs only the header row, so a worksheet that is
    // only inserted into is never read in full; the others are
    // read once, in full, when an operation first touches them.
    //
    scannedWorksheets(operations) {

        const scanned = new Set();

        for (const operation of operations) {

            switch (operation.getType()) {

                case OperationType.SELECT:
                case OperationType.UPDATE:
                case OperationType.DELETE:
                case OperationType.UPSERT:
                case OperationType.CLONE:

                    scanned.add(
                        this.worksheetKey(operation)
                    );

            }

        }

        return scanned;

    }

    worksheetKey(operation) {

        return operation.getSpreadsheetId() +
            ":" +
            operation.getWorksheet();

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

            case OperationType.GET_LAST_UPDATED:

                return this.executeGetLastUpdated(
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
                context,
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

    //
    // Drive's modified time of the whole spreadsheet file:
    // it covers every tab and edits made outside the engine.
    // Row changes of this request commit after it runs.
    //
    // Opt-in: the deployment must enable the Advanced Drive
    // service and the drive.metadata.readonly scope in
    // appsscript.json. Without it only this operation fails;
    // a scope the owner has not approved would stop them all.
    //
    executeGetLastUpdated(context,
                          operation) {

        if (typeof Drive === "undefined") {

            throw new Error(
                "GET_LAST_UPDATED needs the Advanced Drive service. " +
                "Enable it in appsscript.json, approve the Drive " +
                "metadata permission and redeploy (see the README " +
                "section on lastUpdated)."
            );

        }

        const file =
            Drive.Files.get(
                operation.getSpreadsheetId(),
                {
                    fields: "modifiedTime",
                    supportsAllDrives: true
                }
            );

        context.response.addResult({

            operationId:
                operation.getId(),

            spreadsheetId:
                operation.getSpreadsheetId(),

            lastUpdated:
                new Date(file.modifiedTime).getTime()

        });

    }

    executeGetColumns(context,
                      operation) {

        const columns =
            this.worksheetColumnExecutor
                .getColumns(
                    context.resources,
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
                    context.resources,
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
                context.resources,
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
                operation.getWorksheet(),
                context.scannedWorksheets.has(
                    this.worksheetKey(operation)
                )
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