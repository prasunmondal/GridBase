class RequestParser {

    static parse(json) {

        if (!json) {
            throw new Error("Request is empty.");
        }

        const request =
            new ExecutionRequest();

        request.requestId =
            json.requestId || "";

        if (!Array.isArray(json.operations)) {
            throw new Error(
                "'operations' must be an array."
            );
        }

        for (const op of json.operations) {

            request.addOperation(
                this.parseOperation(op)
            );

        }

        return request;

    }

    static parseOperation(json) {

        const operation =
            new OperationRequest();

        operation.type = json.type ?? OperationType.SELECT;

        operation.spreadsheetId =
            json.spreadsheetId;

        operation.worksheet =
            json.worksheet;

        operation.setSkipExisting(
            json.skipExisting === true
        );

        operation.limit =
            json.limit ?? -1;

        operation.offset =
            json.offset ?? 0;

        operation.setId(
            json.id
        );

        if (Array.isArray(json.where)) {

            for (const where of json.where) {

                operation.addPredicate(
                    this.parsePredicate(where)
                );

            }

        }

        if (Array.isArray(json.orderBy)) {

            for (const item of json.orderBy) {

                operation.addOrderBy(

                    new OrderBy(
                        item.column,
                        item.direction
                    )

                );

            }

        }

        if (Array.isArray(json.select)) {

            for (const column of json.select) {

                if (typeof column !== "string") {

                    throw new Error(
                        "select must contain only column names."
                    );

                }

                operation.addProjection(
                    new Projection(column)
                );

            }

        }

        if (Array.isArray(json.columns)) {

            for (const column of json.columns) {

                if (typeof column !== "string") {

                    throw new Error(
                        "columns must contain only column names."
                    );

                }

                operation.addColumn(
                    column
                );

            }

        }

        if (json.values !== undefined) {

            this.parseValues(
                json.values,
                operation
            );

        }

        if (json.rows !== undefined) {

            if (!Array.isArray(json.rows)) {

                throw new Error(
                    "'rows' must be an array."
                );

            }

            this.parseRows(
                json.rows,
                operation
            );

        }

        return operation;

    }

    static parseValues(values,
                       target) {

        for (const column in values) {

            const item =
                values[column];

            //
            // Primitive -> SET
            //
            if (
                item === null ||
                typeof item !== "object" ||
                Array.isArray(item)
            ) {

                target.addValue(

                    new ColumnValue(
                        column,
                        item,
                        ValueOperation.SET
                    )

                );

                continue;
            }

            //
            // Value operation
            //
            target.addValue(

                new ColumnValue(
                    column,
                    item.value,
                    item.operation ||
                    ValueOperation.SET
                )

            );

        }

    }

    static parseRows(rows,
                     operation) {

        for (const row of rows) {

            if (
                !row ||
                typeof row !== "object" ||
                Array.isArray(row)
            ) {

                throw new Error(
                    "Each INSERT row must be an object."
                );

            }

            const insertRow =
                new InsertRow();

            this.parseValues(
                row,
                insertRow
            );

            operation.addRow(
                insertRow
            );

        }

    }

    static parsePredicate(json) {

        switch (json.operator) {

            case "EQUALS":

                return Predicates.equals(
                    json.column,
                    json.value
                );

            case "NOT_EQUALS":

                return Predicates.notEquals(
                    json.column,
                    json.value
                );

            case "GREATER_THAN":

                return Predicates.greaterThan(
                    json.column,
                    json.value
                );

            case "GREATER_THAN_EQUALS":

                return Predicates.greaterThanEquals(
                    json.column,
                    json.value
                );

            case "LESS_THAN":

                return Predicates.lessThan(
                    json.column,
                    json.value
                );

            case "LESS_THAN_EQUALS":

                return Predicates.lessThanEquals(
                    json.column,
                    json.value
                );

            case "STARTS_WITH":

                return Predicates.startsWith(
                    json.column,
                    json.value
                );

            case "ENDS_WITH":

                return Predicates.endsWith(
                    json.column,
                    json.value
                );

            case "CONTAINS":

                return Predicates.contains(
                    json.column,
                    json.value
                );

            case "IN":

                return Predicates.in(
                    json.column,
                    json.values
                );

            case "BETWEEN":

                return Predicates.between(
                    json.column,
                    json.minimum,
                    json.maximum
                );

            case "IS_NULL":

                return Predicates.isNull(
                    json.column
                );

            case "IS_NOT_NULL":

                return Predicates.isNotNull(
                    json.column
                );

            default:

                throw new Error(
                    "Unsupported operator: " +
                    json.operator
                );

        }

    }

}