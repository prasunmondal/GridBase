class WorksheetColumnExecutor {

    getColumns(operation) {

        const worksheet =
            this.getWorksheet(
                operation
            );

        const lastColumn =
            worksheet.getLastColumn();

        if (lastColumn === 0) {

            return [];

        }

        return worksheet
            .getRange(
                1,
                1,
                1,
                lastColumn
            )
            .getValues()[0];

    }

    addColumns(operation) {

        const worksheet =
            this.getWorksheet(
                operation
            );

        const columns =
            operation.getColumns();

        if (!columns ||
            columns.length === 0) {

            throw new Error(
                "At least one column is required."
            );

        }

        const existingColumns =
            this.getColumns(
                operation
            );

        const existing =
            new Set();

        for (const column of existingColumns) {

            existing.add(
                String(column)
                    .trim()
                    .toLowerCase()
            );

        }

        const newColumns = [];
        const skippedColumns = [];
        const requestColumns = new Set();

        for (const column of columns) {

            const name =
                String(column).trim();

            if (!name) {

                throw new Error(
                    "Column name cannot be empty."
                );

            }

            const key =
                name.toLowerCase();

            //
            // Duplicate within the same request
            //
            if (requestColumns.has(key)) {

                if (operation.isSkipExisting()) {

                    skippedColumns.push(
                        name
                    );

                    continue;

                }

                throw new Error(
                    "Duplicate column in request: " +
                    name
                );

            }

            requestColumns.add(key);

            //
            // Already exists in worksheet
            //
            if (existing.has(key)) {

                if (operation.isSkipExisting()) {

                    skippedColumns.push(
                        name
                    );

                    continue;

                }

                throw new Error(
                    "Column already exists: " +
                    name
                );

            }

            newColumns.push(name);
            existing.add(key);

        }

        //
        // Nothing new to add
        //
        if (newColumns.length === 0) {

            return {

                columns: [],

                skippedColumns:
                skippedColumns,

                startColumn:
                    existingColumns.length + 1,

                count: 0

            };

        }

        const startColumn =
            existingColumns.length + 1;

        worksheet
            .getRange(
                1,
                startColumn,
                1,
                newColumns.length
            )
            .setValues([
                newColumns
            ]);

        return {

            columns:
            newColumns,

            skippedColumns:
            skippedColumns,

            startColumn:
            startColumn,

            count:
            newColumns.length

        };

    }

    getWorksheet(operation) {

        const spreadsheet =
            SpreadsheetApp.openById(
                operation.getSpreadsheetId()
            );

        const worksheet =
            spreadsheet.getSheetByName(
                operation.getWorksheet()
            );

        if (!worksheet) {

            throw new Error(
                "Worksheet not found: " +
                operation.getWorksheet()
            );

        }

        return worksheet;

    }

}