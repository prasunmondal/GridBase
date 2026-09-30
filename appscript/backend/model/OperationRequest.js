class OperationRequest {

    constructor() {

        this.id = "";
        this.type = OperationType.SELECT;
        this.spreadsheetId = "";
        this.worksheet = "";

        this.predicates = [];
        this.orderBy = [];

        this.limit = -1;
        this.offset = 0;

        this.projections = [];
        this.values = [];
        this.columns = [];

        this.rows = [];
        this.skipExisting = false;
    }

    setSkipExisting(skipExisting) {

        this.skipExisting =
            skipExisting === true;

        return this;

    }

    isSkipExisting() {

        return this.skipExisting;

    }

    addColumn(column) {

        this.columns.push(
            column
        );

        return this;

    }

    getColumns() {

        return this.columns;

    }

    addValue(value) {

        this.values.push(value);

        return this;
    }

    getValues() {

        return this.values;
    }

    addRow(row) {

        this.rows.push(row);

        return this;
    }

    getRows() {

        return this.rows;
    }

    addPredicate(predicate) {

        this.predicates.push(predicate);

        return this;
    }

    getPredicates() {

        return this.predicates;
    }

    addOrderBy(order) {

        this.orderBy.push(order);

        return this;
    }

    getOrderBy() {

        return this.orderBy;
    }

    getLimit() {

        return this.limit;
    }

    getOffset() {

        return this.offset;
    }

    getId() {

        return this.id;
    }

    setId(id) {

        this.id = id;

        return this;
    }

    getType() {

        return this.type;
    }

    getSpreadsheetId() {

        return this.spreadsheetId;
    }

    getWorksheet() {

        return this.worksheet;
    }

    addProjection(projection) {

        this.projections.push(projection);

        return this;
    }

    getProjections() {

        return this.projections;
    }
}