class CompiledIsNotNullPredicate extends CompiledPredicate {

    constructor(columnIndex) {

        super(columnIndex);

    }

    matches(row) {

        const value =
            row.values[this.columnIndex];

        return value !== null &&
            value !== undefined &&
            value !== "";

    }

}