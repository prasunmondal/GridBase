class CompiledContainsPredicate extends CompiledPredicate {

    constructor(columnIndex, expectedValue) {

        super(columnIndex);

        this.expectedValue = expectedValue;

        this.expectedText = String(expectedValue);

    }

    matches(row) {
        return String(row.values[this.columnIndex])
            .includes(this.expectedText);
    }
}