class Predicate {

    constructor(columnName) {

        this.columnName = columnName;

    }

    getColumnName() {

        return this.columnName;

    }

    matches(row) {

        throw new Error(
            this.constructor.name + ".matches() is not implemented."
        );

    }

    compiledClass() {

        throw new Error(
            this.constructor.name +
            ".compiledClass() not implemented."
        );

    }

}