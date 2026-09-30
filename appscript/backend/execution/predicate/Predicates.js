class Predicates {

    static equals(columnName, value) {

        return new EqualsPredicate(
            columnName,
            value
        );

    }

    static notEquals(columnName, value) {

        return new NotEqualsPredicate(
            columnName,
            value
        );

    }

    static greaterThan(columnName, value) {

        return new GreaterThanPredicate(
            columnName,
            value
        );

    }

    static greaterThanEquals(columnName, value) {

        return new GreaterThanEqualsPredicate(
            columnName,
            value
        );

    }

    static lessThan(columnName, value) {

        return new LessThanPredicate(
            columnName,
            value
        );

    }

    static lessThanEquals(columnName, value) {

        return new LessThanEqualsPredicate(
            columnName,
            value
        );

    }

    static startsWith(columnName, value) {

        return new StartsWithPredicate(
            columnName,
            value
        );

    }

    static endsWith(columnName, value) {

        return new EndsWithPredicate(
            columnName,
            value
        );

    }

    static contains(columnName, value) {

        return new ContainsPredicate(
            columnName,
            value
        );

    }

    static in(columnName, values) {

        return new InPredicate(
            columnName,
            values
        );

    }

    static between(columnName,
                   minimumValue,
                   maximumValue) {

        return new BetweenPredicate(
            columnName,
            minimumValue,
            maximumValue
        );

    }

    static isNull(columnName) {

        return new IsNullPredicate(
            columnName
        );

    }

    static isNotNull(columnName) {

        return new IsNotNullPredicate(
            columnName
        );

    }

}