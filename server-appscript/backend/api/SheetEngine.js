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

        try {

            const json = JSON.parse(e.postData.contents);

            const request = RequestParser.parse(json);
            context = new ExecutionContext(request);

            RequestValidator.validate(request);

            context.provider =
                new GoogleSheetsProvider(
                    context.resources
                );

            const service =
                new ExecutionService();

            service.execute(context);

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

        }

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