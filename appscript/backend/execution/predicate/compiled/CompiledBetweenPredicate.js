class CompiledBetweenPredicate extends CompiledPredicate {

    constructor(columnIndex,
                minimumValue,
                maximumValue) {

        super(columnIndex);

        this.minimumValue =
            minimumValue;

        this.maximumValue =
            maximumValue;

    }

    matches(row) {

        const value =
            row.values[this.columnIndex];

        return value >= this.minimumValue &&
            value <= this.maximumValue;

    }

}