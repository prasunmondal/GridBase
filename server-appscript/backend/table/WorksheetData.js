class WorksheetData {

    constructor(spreadsheetId, worksheet) {

        this.spreadsheetId = spreadsheetId;

        this.worksheet = worksheet;

        this.schema = new WorksheetSchema();

        this.rows = [];

        this.rowLookup = new Map();

        this.indexes = [];

        this.changeSet = new ChangeSet();

        this.loaded = false;

    }

    addRow(row) {

        this.rows.push(row);

        this.rowLookup.set(row.sheetRow, row);

    }

    getWorksheet() {
        return this.worksheet
    }

    getRows() {

        return this.rows;

    }

    getSchema() {

        return this.schema;

    }

    getIndexes() {

        return this.indexes;

    }

    getChangeSet() {

        return this.changeSet;

    }

    getColumnCount() {

        return this.schema
            .getColumns()
            .length;

    }

}