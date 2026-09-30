class ExecutionPlan {

    constructor() {

        this.id = "";
        this.predicates = [];
        this.limit = -1;
        this.offset = 0;

        this.orderBy = [];
        this.projections = [];
        this.values = [];
        this.rows = [];

        this.useIndexes = true;
        this.type = OperationType.SELECT;

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

    setType(type) {
        this.type = type
        return this
    }

    getType() {
        return this.type
    }

    addValue(value) {
        this.values.push(value);
        return this;
    }

    getValues() {
        return this.values;
    }

    getPredicates() {

        return this.predicates;

    }

    setLimit(limit) {

        this.limit = limit;
        return this;

    }

    getLimit() {

        return this.limit;

    }

    setOffset(offset) {

        this.offset = offset;
        return this;

    }

    getOffset() {

        return this.offset;

    }

    setUseIndexes(useIndexes) {

        this.useIndexes = useIndexes;
        return this;

    }

    isUseIndexes() {

        return this.useIndexes;

    }

    addOrderBy(order) {

        this.orderBy.push(order);

        return this;

    }

    getOrderBy() {

        return this.orderBy;

    }

    setOrderBy(orderBy) {

        this.orderBy = orderBy || [];

        return this;

    }

    getId() {

        return this.id;

    }

    setId(id) {
        this.id = id;
        return this
    }

    addProjection(projection) {

        this.projections.push(projection);

        return this;

    }

    getProjections() {
        return this.projections
    }

}