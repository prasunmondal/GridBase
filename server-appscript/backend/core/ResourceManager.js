class ResourceManager {
    constructor(registry) {
        this.spreadsheets = new SpreadsheetPool();
        this.worksheets = new WorksheetPool(this.spreadsheets);
        this.headers = new HeaderPool(this.worksheets);
        this.data = new WorksheetDataPool();
    }
}