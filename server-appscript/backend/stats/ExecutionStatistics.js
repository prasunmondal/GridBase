class ExecutionStatistics {

    constructor() {

        this.rowsExamined = 0;

        this.rowsMatched = 0;

        this.indexUsed = null;

        this.fullScan = true;

    }

    reset() {

        this.executionTime = 0;
        this.rowsExamined = 0;
        this.rowsMatched = 0;
        this.indexUsed = null;
        this.fullScan = true;

    }

}