class LoadResult {

    constructor(data) {

        this.data = data;

        this.rowCount = data.rows.length;

        this.columnCount = data.schema.columns.length;

        this.loadTime = 0;

    }

}