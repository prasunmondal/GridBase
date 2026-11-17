class ApiResponse {

    constructor(requestId) {

        this.success = true;

        this.requestId = requestId;

        this.executionTime = 0;

        this.results = [];

        // Commit timing, set by ExecutionService (see
        // GoogleSheetsProvider.commitAll); null until the commit ran.
        this.commit = null;

        this.errors = [];

        this.debug = [];
        this.exception = null;

    }

    addResult(result) {

        this.results.push(result);

    }

    addError(error) {

        this.success = false;

        this.errors.push(error);

    }
}