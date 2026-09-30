class CompiledPredicate {

    constructor(columnIndex) {

        this.columnIndex = columnIndex;

    }

    matches(row) {

        throw new Error(
            this.constructor.name + ".matches() is not implemented."
        );

    }

}