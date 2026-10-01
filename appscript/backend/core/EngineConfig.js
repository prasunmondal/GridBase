class EngineConfig {

    constructor() {

        this.version = "1.0.0";

        this.debug = true;

        this.maxOperations = 100;

        this.maxRowsPerOperation = 5000;

        this.enableLogging = true;

        this.executionTimeoutMillis = 300000;

        // How long a writing request waits for another one to finish.
        this.lockTimeoutMillis = 30000;

    }

}