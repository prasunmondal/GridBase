class ColumnValue {

    constructor(columnName,
                value,
                operation) {

        this.columnName = columnName;

        this.value = value;

        this.operation =
            operation ||
            ValueOperation.SET;

    }

    getColumnName() {

        return this.columnName;

    }

    getValue() {

        return this.value;

    }

    getOperation() {

        return this.operation;

    }

}