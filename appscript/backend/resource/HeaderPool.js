class HeaderPool {

    constructor(worksheetPool){

        this.pool = worksheetPool;

        this.cache = {};

    }

    get(spreadsheetId, worksheet){

        const key =
            spreadsheetId+":"+worksheet;

        if(this.cache[key])

            return this.cache[key];

        const ws =
            this.pool.get(spreadsheetId,worksheet);

        const headers =
            ws.getRange(
                1,
                1,
                1,
                ws.getLastColumn())
            .getValues()[0];

        this.cache[key]=headers;

        return headers;

    }

}