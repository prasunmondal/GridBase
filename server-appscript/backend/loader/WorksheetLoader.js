class WorksheetLoader {

    constructor(resourceManager) {

        this.resources = resourceManager;

        this.valueReader = new ValueReader(resourceManager);
        this.schemaBuilder = new SchemaBuilder();
        this.rowBuilder = new RowBuilder();
        this.indexBuilder = new IndexBuilder();

    }

    /**
     * Loads a worksheet into memory.
     *
     * @param {ExecutionContext} context
     * @param {WorksheetDataReference} reference
     * @returns {WorksheetData}
     */
    load(context, reference) {

        if (reference.isLoaded()) {
            return reference.data;
        }

        const loaderStats = context.statistics.loader;

        const total = Stopwatch.measure(() => {

            const worksheetData = new WorksheetData(
                reference.spreadsheetId,
                reference.worksheet
            );

            //
            // Read worksheet
            //
            const read = Stopwatch.measure(() => {
                return this.valueReader.read(reference);
            });

            const rawWorksheet = read.result;

            loaderStats.readTime = read.elapsed;

            //
            // Build Schema (always)
            //
            loaderStats.schemaTime =
                Stopwatch.measure(() => {

                    this.schemaBuilder.build(
                        context,
                        worksheetData,
                        rawWorksheet
                    );

                }).elapsed;

            //
            // No data rows
            //
            if (!rawWorksheet.hasDataRows()) {

                worksheetData.loaded = true;

                reference.data = worksheetData;

                loaderStats.rowsRead = 0;
                loaderStats.columnsRead =
                    worksheetData.getSchema().getColumns().length;
                loaderStats.cellsRead = 0;

                return worksheetData;

            }

            //
            // Build Rows
            //
            loaderStats.rowBuildTime =
                Stopwatch.measure(() => {

                    this.rowBuilder.build(
                        worksheetData,
                        rawWorksheet
                    );

                }).elapsed;

            //
            // Build Indexes
            //
            loaderStats.indexBuildTime =
                Stopwatch.measure(() => {

                    this.indexBuilder.build(
                        worksheetData
                    );

                }).elapsed;

            //
            // Finalize
            //
            worksheetData.loaded = true;

            reference.data = worksheetData;

            loaderStats.rowsRead =
                worksheetData.getRows().length;

            context.getDebug().add("Rows Loaded", worksheetData.getRows().length);

            loaderStats.columnsRead =
                worksheetData.getSchema()
                    .getColumns()
                    .length;

            loaderStats.cellsRead =
                loaderStats.rowsRead *
                loaderStats.columnsRead;

            return worksheetData;

        });

        loaderStats.totalTime = total.elapsed;

        return total.result;

    }

}