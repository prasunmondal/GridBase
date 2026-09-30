class CompiledNotEqualsPredicate extends CompiledPredicate {

    constructor(columnIndex, expectedValue) {

        super(columnIndex);

        this.expectedValue = expectedValue;

    }

    matches(row) {

        return String(row.values[this.columnIndex]) !== String(this.expectedValue);

    }

}