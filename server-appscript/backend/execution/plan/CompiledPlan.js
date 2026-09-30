class CompiledPlan {

    constructor() {
        this.id = "";

        this.predicates = [];
        this.orderBy = [];

        this.limit = -1;

        this.offset = 0;

        this.projections = [];
        this.values = [];
        this.type = OperationType.SELECT;
        this.rows = [];
    }

    addRow(row) {

        this.rows.push(row);

        return this;
    }

    getRows() {

        return this.rows;
    }

    setType(type) {
        this.type = type;
        return this;
    }

    getType() {
        return this.type;
    }

    setOrderBy(orderBy) {

        this.orderBy = orderBy || [];

    }

    addOrderBy(order) {

        this.orderBy.push(order);

        return this;

    }

    addValue(value) {
        this.values.push(value)
        return this
    }

    getValues() {
        return this.values;
    }

    getOrderBy() {

        return this.orderBy;

    }

    addPredicate(predicate) {

        this.predicates.push(predicate);

        return this;

    }

    getPredicates() {

        return this.predicates;

    }

    setLimit(limit) {

        this.limit = limit;

    }

    getLimit() {

        return this.limit;

    }

    setOffset(offset) {

        this.offset = offset;

    }

    getOffset() {

        return this.offset;

    }

    addProjection(projection) {

        this.projections.push(projection);

        return this;

    }

    getProjections() {
        return this.projections
    }

    setId(id) {

        this.id = id;
        return this;

    }

    getId() {

        return this.id;

    }

}