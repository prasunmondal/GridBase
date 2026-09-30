class DataRow {

    constructor(sheetRow, values) {

        this.sheetRow = sheetRow;

        this.values = values || [];

        this.state = RowState.CLEAN;

    }

    get(index) {

        return this.values[index];

    }

    set(index, value) {

        this.values[index] = value;

        if (this.state === RowState.CLEAN) {
            this.state = RowState.MODIFIED;
        }

    }

    markDeleted() {

        this.state = RowState.DELETED;

    }

    markNew() {

        this.state = RowState.NEW;

    }

    isDirty() {

        return this.state !== RowState.CLEAN;

    }

}