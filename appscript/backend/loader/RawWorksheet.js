class RawWorksheet {

    constructor(values) {

        this.values = values || [];

        this.headers = this.values.length > 0 ? this.values[0] : [];

        this.rows = this.values.length > 1 ? this.values.slice(1) : [];
    }

    getHeaders() {
        return this.headers;
    }

    getRows() {
        return this.rows;
    }

    isWorksheetEmpty() {
        return this.headers.length === 0;
    }

    hasDataRows() {
        return this.rows.length > 0;
    }

    getColumnCount() {
        return this.headers.length;
    }

    getRowCount() {
        return this.rows.length;
    }
}