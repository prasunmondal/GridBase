class RowIndex {

    constructor(primaryKeyIndex) {

        this.primaryKeyIndex = primaryKeyIndex;

        this.rows = new Map();

    }

    put(row) {

        const key = row.get(this.primaryKeyIndex);

        if (key === null || key === undefined || key === "") {
            throw new Error(
                "Primary key cannot be empty (sheet row " +
                row.sheetRow +
                ")"
            );
        }

        if (this.rows.has(key)) {

            const existing = this.rows.get(key);

            throw new Error(
                "Duplicate primary key '" +
                key +
                "' found at sheet rows " +
                existing.sheetRow +
                " and " +
                row.sheetRow
            );

        }

        this.rows.set(key, row);

    }

    get(key) {

        return this.rows.get(key);

    }

    contains(key) {

        return this.rows.has(key);

    }

    remove(key) {

        this.rows.delete(key);

    }

}