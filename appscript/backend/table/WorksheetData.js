class WorksheetData {

    constructor(spreadsheetId, worksheet) {

        this.spreadsheetId = spreadsheetId;

        this.worksheet = worksheet;

        this.schema = new WorksheetSchema();

        this.rows = [];

        this.indexes = [];

        this.changeSet = new ChangeSet();

        this.loaded = false;

        // false while only the header row has been read
        this.rowsLoaded = false;

        // true once REPLACE_ALL_DATA ran; the commit clears the sheet first
        this.cleared = false;

    }

    //
    // REPLACE_ALL_DATA: no rows are left, so none are read later in the
    // request, and changes staged before it are dropped.
    //
    clearRows() {

        this.rows = [];

        this.indexes = [];

        this.changeSet.clear();

        this.rowsLoaded = true;

        this.cleared = true;

    }

    addRow(row) {

        this.rows.push(row);

    }

    //
    // Rows read from the sheet go before the rows this
    // request added, which the sheet does not have yet.
    //
    addLoadedRows(rows) {

        this.rows =
            this.rows.length === 0
                ? rows
                : rows.concat(this.rows);

        this.rowsLoaded = true;

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
