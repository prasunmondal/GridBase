class SheetEngine {

    static instance() {

        if (!SheetEngine._instance) {

            SheetEngine._instance =
                new SheetEngine();

        }

        return SheetEngine._instance;

    }

    constructor() {

        this.config = new EngineConfig();
    }

    handlePost(e) {

        let context = null;

        let lock = null;

        try {

            const json = JSON.parse(e.postData.contents);

            const request = RequestParser.parse(json);
            context = new ExecutionContext(request);

            RequestValidator.validate(request);

            //
            // Requests that write are serialized: two of them appending at
            // getLastRow() + 1 at the same time would overwrite each other.
            // Read-only requests never write, so they don't wait.
            //
            if (this.writes(request)) {

                //
                // openById is slow and reads nothing the lock
                // protects, so it happens before the wait: the lock
                // is held only while sheet contents are read and written.
                //
                this.openSpreadsheets(context);

                const scriptLock = LockService.getScriptLock();

                scriptLock.waitLock(this.config.lockTimeoutMillis);

                lock = scriptLock;

            }

            context.provider =
                new GoogleSheetsProvider(
                    context.resources
                );

            const service =
                new ExecutionService();

            service.execute(context);

            //
            // Apps Script buffers sheet writes and would send them when
            // the script ends, after the lock is gone: the next writer
            // could then read the old last row and overwrite these rows.
            // Flushing here also reports a failed write as a failure.
            // The response is built after the lock is released.
            //
            if (lock) {

                SpreadsheetApp.flush();

                lock.releaseLock();

                lock = null;

            }

            context.response.executionTime =
                context.statistics.execution.totalTime;

            return this.buildResponse(
                context.response
            );

        } catch (ex) {

            return ContentService
                .createTextOutput(JSON.stringify({
                    success: false,
                    error: ex.message,
                    exceptionType: ex.name,
                        debug:
                            context
                                ? context.getDebug().getEntries()
                                : [],
                    stackTrace: ex.stack ? ex.stack.split("\n") : []
                }))
                .setMimeType(ContentService.MimeType.JSON);

        } finally {

            //
            // Failed request: send what was already written (schema
            // operations) before another writer can start.
            //
            if (lock) {

                try {
                    SpreadsheetApp.flush();
                } catch (ignored) {
                    // The request already failed; its error is reported.
                }

                lock.releaseLock();

            }

        }

    }

    openSpreadsheets(context) {

        for (const operation of context.request.getOperations()) {

            //
            // GET_LAST_UPDATED goes through Drive only.
            //
            if (operation.type === OperationType.GET_LAST_UPDATED) {
                continue;
            }

            context.resources.spreadsheets.get(
                operation.getSpreadsheetId()
            );

        }

    }

    writes(request) {

        return request.getOperations().some(function (operation) {
            return operation.type !== OperationType.SELECT &&
                operation.type !== OperationType.GET_COLUMNS &&
                operation.type !== OperationType.GET_LAST_UPDATED;
        });

    }

    buildResponse(response) {

        return ContentService
            .createTextOutput(
                JSON.stringify(response)
            )
            .setMimeType(ContentService.MimeType.JSON);

    }

    handleGet() {

        return ContentService
            .createTextOutput("SheetEngine Running");

    }

}