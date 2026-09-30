class CompiledValueOperation {

    constructor(columnIndex,
                columnName,
                value) {

        this.columnIndex = columnIndex;
        this.columnName = columnName;
        this.value = value;

    }

    apply(oldValue) {

        throw new Error(
            "apply() not implemented."
        );

    }

}