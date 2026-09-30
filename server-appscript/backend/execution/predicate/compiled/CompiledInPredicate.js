class CompiledInPredicate extends CompiledPredicate {

    constructor(columnIndex,
                values) {

        super(columnIndex);

        this.values =
            values || [];

    }

    matches(row) {

        const value =
            row.values[this.columnIndex];

        return this.values.includes(value);

    }

}