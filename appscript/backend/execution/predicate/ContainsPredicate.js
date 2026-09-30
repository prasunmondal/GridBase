class ContainsPredicate  extends BinaryPredicate {

    constructor(columnName, expectedValue) {

        super();

        this.columnName = columnName;
        this.expectedValue = expectedValue;

    }

    getColumnName() {

        return this.columnName;

    }

    getExpectedValue() {

        return this.expectedValue;

    }

    compiledClass() {

        return CompiledContainsPredicate;

    }

}