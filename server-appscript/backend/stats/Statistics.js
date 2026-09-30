class Statistics {

    constructor() {

        this.request = {
            started: 0,
            finished: 0,
            totalTime: 0
        };

        this.loader = {
            rowsRead: 0,
            columnsRead: 0,

            readTime: 0,
            schemaTime: 0,
            rowBuildTime: 0,
            indexBuildTime: 0,

            totalTime: 0
        };

        this.committer = {
            created: 0,
            updated: 0,
            deleted: 0,

            commitTime: 0
        };

        this.execution = {
            compileTime: 0,
            executeTime: 0,
            totalTime: 0,

            rowsExamined: 0,
            rowsMatched: 0,

            indexUsed: null,
            fullScan: true
        };

        this.cache = {
            spreadsheetHits: 0,
            spreadsheetMisses: 0,

            worksheetHits: 0,
            worksheetMisses: 0,

            dataHits: 0,
            dataMisses: 0
        };
    }

}