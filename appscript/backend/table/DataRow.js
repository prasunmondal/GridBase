class DataRow {

    constructor(sheetRow, values) {

        this.sheetRow = sheetRow;

        this.values = values || [];

        this.state = RowState.CLEAN;

        //
        // Columns set() changed, as a span: the commit writes
        // only these cells back, not the whole row.
        //
        this.firstChanged = -1;

        this.lastChanged = -1;

    }

    get(index) {

        return this.values[index];

    }

    set(index, value) {

        this.values[index] = value;

        if (this.state === RowState.CLEAN) {
            this.state = RowState.MODIFIED;
        }

        if (this.firstChanged < 0 || index < this.firstChanged) {
            this.firstChanged = index;
        }

        if (index > this.lastChanged) {
            this.lastChanged = index;
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
