class CompiledLessThanEqualsPredicate extends CompiledPredicate {

    constructor(columnIndex, expectedValue) {

        super(columnIndex);

        this.expectedValue = expectedValue;

        this.expectedNumber = Number(expectedValue);

    }

    matches(row) {

        return Number(row.values[this.columnIndex]) <=
            this.expectedNumber;

    }

}