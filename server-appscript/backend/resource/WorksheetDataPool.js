class WorksheetDataPool {

    constructor() {

        this.cache = {};

    }

    get(spreadsheetId, worksheet) {

        const key =
            spreadsheetId + ":" + worksheet;

        if (!this.cache[key]) {

            this.cache[key] =
                new WorksheetDataReference(
                    spreadsheetId,
                    worksheet
                );

        }

        return this.cache[key];

    }

    getAll() {

        return Object.values(this.cache);

    }

}