class SpreadsheetPool {

    constructor() {
        this.cache = {};
    }

    get(spreadsheetId) {

        if (!spreadsheetId) {
            throw new Error(
                "Spreadsheet ID is missing: " +
                JSON.stringify(spreadsheetId)
            );
        }

        if (this.cache[spreadsheetId]) {
            return this.cache[spreadsheetId];
        }

        const spreadsheet =
            SpreadsheetApp.openById(
                spreadsheetId
            );

        this.cache[spreadsheetId] = spreadsheet;

        return spreadsheet;
    }

}