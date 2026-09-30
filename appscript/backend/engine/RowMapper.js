class RowMapper {

    constructor() {

        this.columnValueMapper =
            new ColumnValueMapper();

    }

    map(row, schema) {

        const object = {};

        const columns =
            schema.getColumns();

        for (let i = 0; i < columns.length; i++) {

            const column = columns[i];

            object[column.name] =
                this.columnValueMapper.map(
                    row.get(i),
                    column
                );

        }

        return object;

    }

}