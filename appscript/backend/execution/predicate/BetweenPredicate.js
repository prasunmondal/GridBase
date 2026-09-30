class BetweenPredicate extends MultiValuePredicate {

    constructor(columnName,
                minimumValue,
                maximumValue) {

        super(columnName);

        this.minimumValue =
            minimumValue;

        this.maximumValue =
            maximumValue;

    }

    getMinimumValue() {

        return this.minimumValue;

    }

    getMaximumValue() {

        return this.maximumValue;

    }

}