class CompiledLessThanEqualsPredicate extends CompiledPredicate {

    constructor(columnIndex, expectedValue) {

        super(columnIndex);

        this.expectedValue = expectedValue;

    }

    matches(row) {

        return Number(row.values[this.columnIndex]) <=
            Number(this.expectedValue);

    }

}