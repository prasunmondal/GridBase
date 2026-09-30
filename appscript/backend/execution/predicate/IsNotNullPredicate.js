class IsNotNullPredicate  extends UnaryPredicate {

    constructor(columnName) {

        super();

        this.columnName = columnName;

    }

    getColumnName() {

        return this.columnName;

    }

    compiledClass() {

        return CompiledIsNotNullPredicate;

    }
}