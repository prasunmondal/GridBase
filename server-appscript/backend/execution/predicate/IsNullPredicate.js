class IsNullPredicate  extends UnaryPredicate {

    constructor(columnName) {

        super();

        this.columnName = columnName;

    }

    getColumnName() {

        return this.columnName;

    }

    compiledClass() {

        return CompiledIsNullPredicate;

    }
}