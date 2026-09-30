class OrderBy {

    constructor(columnName, direction) {

        this.columnName = columnName;
        this.direction = direction || OrderDirection.ASC;

    }

    getColumnName() {

        return this.columnName;

    }

    getDirection() {

        return this.direction;

    }
}