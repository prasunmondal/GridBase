class ExecutionResult {

    constructor() {

        this.rows = [];

    }

    addRow(row) {

        this.rows.push(row);

    }

    getRows() {

        return this.rows;

    }

    setRows(rows) {

        this.rows = rows;

    }

    getRowCount() {

        return this.rows.length;

    }

    isEmpty() {

        return this.rows.length === 0;

    }

}