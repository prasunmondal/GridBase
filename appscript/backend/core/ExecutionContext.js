class ExecutionContext {

    constructor(request) {
        this.request = request;
        this.resources = new ResourceManager();
        this.response = new ApiResponse(request.requestId);
        this.statistics = new Statistics();
        this.debug = new DebugContext();
    }

    getDebug() {
        return this.debug;
    }
}