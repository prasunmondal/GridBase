class CompiledEndsWithPredicate extends CompiledPredicate {

    constructor(columnIndex, expectedValue) {

        super(columnIndex);

        this.expectedValue = expectedValue;

    }

    matches(row) {
        return String(row.values[this.columnIndex])
            .endsWith(String(this.expectedValue));
    }
}