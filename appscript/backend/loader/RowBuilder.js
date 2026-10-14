class RowBuilder {

    constructor() {
        this.factory = new DataRowFactory();
    }

    /**
     * @param {Array[]} values data rows, the first one at sheet row 2
     * @returns {DataRow[]}
     */
    build(values) {

        const rows = new Array(values.length);

        for (let i = 0; i < values.length; i++) {

            rows[i] = this.factory.create(
                i + 2,
                values[i]
            );

        }

        return rows;

    }

}
