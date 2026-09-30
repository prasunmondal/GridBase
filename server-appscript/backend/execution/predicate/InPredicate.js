class InPredicate extends MultiValuePredicate {

    constructor(columnName, values) {

        super(columnName);

        this.values = values || [];

    }

    getValues() {

        return this.values;

    }

}