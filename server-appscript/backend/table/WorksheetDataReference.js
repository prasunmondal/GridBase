class WorksheetDataReference {

    constructor(spreadsheetId, worksheet) {

        this.spreadsheetId = spreadsheetId;

        this.worksheet = worksheet;

        this.data = null;

    }

    isLoaded() {
        return this.data !== null;
    }

}