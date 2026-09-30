class WorksheetSchema {

    constructor() {

        this.columns = [];

        this.columnLookup = Object.create(null);

        this.primaryKeyIndex = -1;

        this.version = 1;

    }

    addColumn(column) {

        this.columns.push(column);

        this.columnLookup[column.name] = column.index;

        if (column.primaryKey) {
            this.primaryKeyIndex = column.index;
        }

    }

    getColumnIndex(columnName) {

        return this.hasColumn(columnName)
            ? this.columnLookup[columnName]
            : -1;

    }

    hasColumn(columnName) {

        return Object.prototype.hasOwnProperty.call(
            this.columnLookup,
            columnName
        );

    }

    getColumns() {

        return this.columns;

    }

}