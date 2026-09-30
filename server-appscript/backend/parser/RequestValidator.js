class RequestValidator {

    static validate(request) {

        if (!request) {
            throw new Error("Request cannot be null.");
        }

        const operations = request.getOperations();

        if (!operations || operations.length === 0) {
            throw new Error(
                "Request must contain at least one operation."
            );
        }

        const ids = new Set();

        for (const operation of operations) {

            if (ids.has(operation.getId())) {

                throw new Error(
                    "Duplicate operation id: " +
                    operation.getId()
                );

            }

            ids.add(operation.getId());

        }

        for (let i = 0; i < operations.length; i++) {
            this.validateOperation(
                operations[i],
                i
            );
        }

    }

    static validateOperation(operation, index) {

        if (!operation) {
            throw new Error(
                "Operation " + index + " is null."
            );
        }

        this.require(
            operation.getId(),
            "Operation " + index + " must specify an id."
        );

        switch (operation.type) {

            case OperationType.SELECT:
                this.validateSelect(operation, index);
                break;

            case OperationType.INSERT:
                this.validateInsert(operation, index);
                break;

            case OperationType.UPDATE:
                this.validateUpdate(operation, index);
                break;

            case OperationType.DELETE:
                this.validateDelete(operation, index);
                break;

            case OperationType.UPSERT:
                this.validateUpsert(operation, index);
                break;

            case OperationType.CLONE:
                this.validateClone(operation, index);
                break;

            case OperationType.CREATE_WORKSHEET:
                this.validateCreateWorksheet(operation, index);
                break;

            case OperationType.GET_COLUMNS:
                this.validateGetColumns(operation, index);
                break;

            case OperationType.ADD_COLUMNS:
                this.validateAddColumns(operation, index);
                break;

            case OperationType.CLEAR_WORKSHEET:

                this.validateClearWorksheet(
                    operation,
                    index
                );

                break;

            default:
                throw new Error(
                    "Unsupported operation type: " +
                    operation.type
                );

        }

    }

    static validateClearWorksheet(operation,
                                  index) {

        this.require(
            operation.getSpreadsheetId(),

            "Operation " + index +
            " must specify a spreadsheet."
        );

        this.require(
            operation.getWorksheet(),

            "Operation " + index +
            " must specify a worksheet."
        );

    }

    static validateGetColumns(operation,
                              index) {

        this.require(
            operation.getSpreadsheetId(),
            "Operation " + index +
            " must specify a spreadsheet."
        );

        this.require(
            operation.getWorksheet(),
            "Operation " + index +
            " must specify a worksheet."
        );

    }

    static validateAddColumns(operation,
                              index) {

        this.require(
            operation.getSpreadsheetId(),
            "Operation " + index +
            " must specify a spreadsheet."
        );

        this.require(
            operation.getWorksheet(),
            "Operation " + index +
            " must specify a worksheet."
        );

        const columns =
            operation.getColumns();

        if (!columns ||
            columns.length === 0) {

            throw new Error(
                "Operation " + index +
                " must specify at least one column."
            );

        }

        for (let i = 0; i < columns.length; i++) {

            this.require(
                columns[i],
                "Operation " + index +
                " contains an invalid column at index " +
                i
            );

        }

    }

    static validateSelect(operation, index) {

        this.require(
            operation.getSpreadsheetId(),
            "Operation " + index +
            " must specify a spreadsheet."
        );

        this.require(
            operation.worksheet,
            "Operation " + index +
            " must specify a worksheet."
        );

        if (operation.limit < -1) {
            throw new Error(
                "Operation " + index +
                " has an invalid limit."
            );
        }

        if (operation.offset < 0) {
            throw new Error(
                "Operation " + index +
                " has an invalid offset."
            );
        }

        const predicates =
            operation.getPredicates();

        for (let i = 0; i < predicates.length; i++) {
            this.validatePredicate(
                predicates[i],
                i
            );
        }

    }

    static validateInsert(operation, index) {

        return true
        // throw new Error(
        //     "INSERT is not implemented."
        // );

    }

    static validateUpdate(operation, index) {

        return true
        // throw new Error(
        //     "UPDATE is not implemented."
        // );

    }

    static validateDelete(operation, index) {

        return true;
        // throw new Error(
        //     "DELETE is not implemented."
        // );
    }

    static validateUpsert(operation, index) {
        return true;
        // throw new Error(
        //     "UPSERT is not implemented."
        // );

    }

    static validateClone(operation, index) {
        return true;
        // throw new Error(
        //     "CLONE is not implemented."
        // );

    }

    static validateCreateWorksheet(operation,
                                   index) {

        this.require(
            operation.getSpreadsheetId(),
            "Operation " + index +
            " must specify a spreadsheet."
        );

        const worksheet =
            operation.getWorksheet();

        this.require(
            worksheet,
            "Operation " + index +
            " must specify a worksheet."
        );

        if (worksheet.trim().length === 0) {

            throw new Error(
                "Operation " + index +
                " must specify a valid worksheet."
            );

        }

    }

    static validateUnaryPredicate(predicate, index) {

        this.require(
            predicate.getColumnName(),
            "Predicate " + index +
            " must specify a column."
        );

    }

    static validateBinaryPredicate(predicate, index) {

        this.require(
            predicate.getColumnName(),
            "Predicate " + index +
            " must specify a column."
        );

        if (predicate.getExpectedValue() === undefined) {

            throw new Error(
                "Predicate " + index +
                " must specify a value."
            );

        }

    }

    static validatePredicate(predicate, index) {

        if (!predicate) {

            throw new Error(
                "Predicate " + index + " is null."
            );

        }

        //
        // Binary predicates
        //
        if (
            predicate instanceof EqualsPredicate ||
            predicate instanceof NotEqualsPredicate ||
            predicate instanceof GreaterThanPredicate ||
            predicate instanceof GreaterThanEqualsPredicate ||
            predicate instanceof LessThanPredicate ||
            predicate instanceof LessThanEqualsPredicate ||
            predicate instanceof ContainsPredicate ||
            predicate instanceof StartsWithPredicate ||
            predicate instanceof EndsWithPredicate
        ) {

            return this.validateBinaryPredicate(
                predicate,
                index
            );

        }

        //
        // Multi-value predicates
        //
        if (predicate instanceof InPredicate) {

            this.require(
                predicate.getColumnName(),
                "Predicate " + index + " must specify a column."
            );

            if (!Array.isArray(predicate.getValues()) ||
                predicate.getValues().length === 0) {

                throw new Error(
                    "Predicate " + index +
                    " (IN) must specify a non-empty 'values' array."
                );

            }

            return;

        }

        if (predicate instanceof BetweenPredicate) {

            this.require(
                predicate.getColumnName(),
                "Predicate " + index + " must specify a column."
            );

            if (predicate.getMinimumValue() === undefined ||
                predicate.getMaximumValue() === undefined) {

                throw new Error(
                    "Predicate " + index +
                    " (BETWEEN) must specify 'minimum' and 'maximum'."
                );

            }

            return;

        }

        //
        // Unary predicates
        //
        if (
            predicate instanceof IsNullPredicate ||
            predicate instanceof IsNotNullPredicate
        ) {

            return this.validateUnaryPredicate(
                predicate,
                index
            );

        }

        throw new Error(
            "Unsupported predicate: " +
            predicate.constructor.name
        );

    }

    static validateEqualsPredicate(predicate, index) {

        this.require(
            predicate.getColumnName(),
            "Predicate " + index +
            " must specify a column."
        );

        if (predicate.getExpectedValue() === undefined) {
            throw new Error(
                "Predicate " + index +
                " must specify a value."
            );
        }

    }

    static require(value, message) {

        if (
            value === null ||
            value === undefined ||
            value === ""
        ) {
            throw new Error(message);
        }

    }

}

