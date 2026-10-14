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
     * @param {boolean} withRows false reads only the header row
     * @returns {WorksheetData}
     */
    load(context, reference, withRows) {

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
            // Read worksheet: everything in one read, or the header row
            //
            const read = Stopwatch.measure(() => {
                return withRows
                    ? this.valueReader.read(reference)
                    : this.valueReader.readHeaders(reference);
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

            loaderStats.columnsRead =
                worksheetData.getColumnCount();

            if (withRows) {

                this.buildRows(
                    context,
                    worksheetData,
                    rawWorksheet.getRows()
                );

            }

            //
            // Finalize
            //
            worksheetData.loaded = true;

            reference.data = worksheetData;

            return worksheetData;

        });

        loaderStats.totalTime = total.elapsed;

        return total.result;

    }

    /**
     * Reads the data rows of a worksheet loaded without them.
     */
    loadRows(context, worksheetData) {

        const read = Stopwatch.measure(() => {
            return this.valueReader.readRows(
                worksheetData,
                worksheetData.getColumnCount()
            );
        });

        context.statistics.loader.readTime += read.elapsed;

        this.buildRows(
            context,
            worksheetData,
            read.result
        );

    }

    buildRows(context, worksheetData, values) {

        const loaderStats = context.statistics.loader;

        //
        // Build Rows
        //
        loaderStats.rowBuildTime =
            Stopwatch.measure(() => {

                worksheetData.addLoadedRows(
                    this.rowBuilder.build(values)
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

        loaderStats.rowsRead = values.length;

        loaderStats.cellsRead =
            loaderStats.rowsRead *
            loaderStats.columnsRead;

        context.getDebug().add("Rows Loaded", values.length);

    }

}
