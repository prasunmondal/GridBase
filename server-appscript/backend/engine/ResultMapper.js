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

        const mapped = [];

        for (let i = 0; i < rows.length; i++) {

            mapped.push(

                this.mapRow(
                    rows[i],
                    schema,
                    projections
                )

            );

        }

        return mapped;

    }

    mapRow(row,
           schema,
           projections) {

        //
        // SELECT *
        //
        if (!projections ||
            projections.length === 0) {

            return this.mapAllColumns(
                row,
                schema
            );

        }

        //
        // SELECT col1,col2...
        //
        const object = {};

        for (let i = 0; i < projections.length; i++) {

            const projection =
                projections[i];

            // context.getDebug().add(
            //     "Projection",
            //     projection
            // );

            const column =
                schema.getColumns()[
                    projection.columnIndex
                    ];

            if (!column) {

                throw new Error(
                    "Invalid projection columnIndex: " +
                    projection.columnIndex
                );

            }

            object[column.name] =
                this.columnValueMapper.map(
                    row.get(projection.columnIndex),
                    column
                );

        }

        return object;

    }

    mapAllColumns(row,
                  schema) {

        const object = {};

        const columns =
            schema.getColumns();

        for (let i = 0; i < columns.length; i++) {

            const column =
                columns[i];

            object[column.name] =
                this.columnValueMapper.map(
                    row.get(i),
                    column
                );

        }

        return object;

    }

}