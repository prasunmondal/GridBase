class IndexBuilder {

    build(worksheetData) {

        const schema =
            worksheetData.getSchema();

        if (schema.primaryKeyIndex < 0) {
            return;
        }

        const index =
            new RowIndex(
                schema.primaryKeyIndex
            );

        for (const row of worksheetData.getRows()) {
            index.put(row);
        }

        worksheetData.getIndexes().push(index);

    }

}