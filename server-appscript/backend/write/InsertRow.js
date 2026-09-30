class InsertRow {

    constructor() {

        this.values = [];

    }

    addValue(value) {

        this.values.push(value);

        return this;
    }

    getValues() {

        return this.values;
    }

}