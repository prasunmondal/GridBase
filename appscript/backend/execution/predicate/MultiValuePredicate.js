class MultiValuePredicate  extends Predicate {

    constructor(columnName, values) {

        super(columnName);

        this.values = values;

    }

    getValues() {

        return this.values;

    }


    compiledClass() {

        return CompiledMultiValuePredicate;

    }

}