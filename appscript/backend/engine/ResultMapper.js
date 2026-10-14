class ResultMapper {

    constructor() {

        this.columnValueMapper =
            new ColumnValueMapper();

    }

    map(compiledPlan,
        result,
        worksheetData) {

        return {

            operationId:
                compiledPlan.getId(),

            rowCount:
                result.getRowCount(),

            rows:
                this.mapRows(
                    result.getRows(),
                    worksheetData.getSchema(),
                    compiledPlan.getProjections()
                )

        };

    }

    mapRows(rows,
            schema,
            projections) {

        //
        // Resolved once, not per row: the indexes and
        // schemas of the columns to return.
        //
        const indexes = [];

        const columns = [];

        if (!projections ||
            projections.length === 0) {

            //
            // SELECT *
            //
            const all =
                schema.getColumns();

            for (let i = 0; i < all.length; i++) {

                indexes.push(i);

                columns.push(all[i]);

            }

        } else {

            //
            // SELECT col1,col2...
            //
            for (let i = 0; i < projections.length; i++) {

                const column =
                    schema.getColumns()[
                        projections[i].columnIndex
                        ];

                if (!column) {

                    throw new Error(
                        "Invalid projection columnIndex: " +
                        projections[i].columnIndex
                    );

                }

                indexes.push(projections[i].columnIndex);

                columns.push(column);

            }

        }

        const mapped =
            new Array(rows.length);

        for (let r = 0; r < rows.length; r++) {

            const values =
                rows[r].values;

            const object = {};

            for (let c = 0; c < columns.length; c++) {

                object[columns[c].name] =
                    this.columnValueMapper.map(
                        values[indexes[c]],
                        columns[c]
                    );

            }

            mapped[r] = object;

        }

        return mapped;

    }

}
