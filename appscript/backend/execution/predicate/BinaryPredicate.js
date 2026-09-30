class BinaryPredicate extends Predicate {

    constructor(columnName, expectedValue) {

        super(columnName);

        this.expectedValue = expectedValue;

    }

    getExpectedValue() {

        return this.expectedValue;

    }

}