class CompiledInPredicate extends CompiledPredicate {

    constructor(columnIndex,
                values) {

        super(columnIndex);

        this.values =
            values || [];

        // Same equality as Array.includes (SameValueZero), in O(1).
        this.valueSet =
            Array.isArray(this.values)
                ? new Set(this.values)
                : null;

    }

    matches(row) {

        const value =
            row.values[this.columnIndex];

        return this.valueSet
            ? this.valueSet.has(value)
            : this.values.includes(value);

    }

}