class CompiledEqualsPredicate extends CompiledPredicate {

    constructor(columnIndex, expectedValue) {

        super(columnIndex);

        this.expectedValue = expectedValue;

        // Convert once: a JSON number (5) must match a cell holding 5.
        this.expectedText = String(expectedValue);

    }

    matches(row) {

        return String(row.values[this.columnIndex]) === this.expectedText;

    }

}

