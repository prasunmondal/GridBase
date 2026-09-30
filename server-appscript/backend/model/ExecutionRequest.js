class ExecutionRequest {

    constructor() {

        this.requestId = "";

        this.operations = [];

    }

    addOperation(operation) {

        this.operations.push(operation);

        return this;

    }

    getOperations() {

        return this.operations;

    }

}