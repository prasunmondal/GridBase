class WorksheetPool {

    constructor(spreadsheetPool){

        this.pool = spreadsheetPool;

        this.cache = {};

    }

    get(spreadsheetId, worksheet){

        const key =
            spreadsheetId + ":" + worksheet;

        if(this.cache[key])

            return this.cache[key];

        const ss =
            this.pool.get(spreadsheetId);

        const ws = ss.getSheetByName(worksheet);

        if (!ws) {
            throw new Error(
                "Worksheet not found: " +
                worksheet
            );
        }

        this.cache[key] = ws;

        return ws;
    }

}