class GoogleSheetsProvider {

    constructor(resources) {

        this.resources = resources;

        this.loader =
            new WorksheetLoader(resources);

        this.committer =
            new WorksheetCommitter();

    }

    /**
     * @param {boolean} withRows false loads only the header row,
     *        which is all INSERT needs.
     */
    getWorksheetData(context,
                     spreadsheetId,
                     worksheet,
                     withRows) {

        const reference =
            this.resources.data.get(
                spreadsheetId,
                worksheet
            );

        if (!reference.isLoaded()) {

            this.loader.load(
                context,
                reference,
                withRows
            );

        } else if (withRows &&
            !reference.data.rowsLoaded) {

            this.loader.loadRows(
                context,
                reference.data
            );

        }

        return reference.data;

    }

    //
    // Every sheet read of the commit happens before its first write
    // (see WorksheetCommitter.prepare). Returns the commit timing for
    // the response; the flush is timed by SheetEngine. Writes are
    // buffered by Apps Script, so a worksheet's time is mostly how
    // long the calls took to queue: the spreadsheet applies them
    // during the flush.
    //
    commitAll() {

        const references =
            this.resources.data.getAll();

        const plans = [];

        const prepared = Stopwatch.measure(() => {

            for (const reference of references) {

                if (!reference.isLoaded()) {
                    continue;
                }

                const plan =
                    this.committer.prepare(
                        reference.data,
                        this.resources
                    );

                if (plan) {
                    plans.push(plan);
                }

            }

        });

        const timing = {
            prepareTime: prepared.elapsed,
            writeTime: 0,
            flushTime: 0,
            worksheets: []
        };

        for (const plan of plans) {

            const run = Stopwatch.measure(() =>
                this.committer.write(plan)
            );

            const worksheet = run.result;

            worksheet.time = run.elapsed;

            timing.writeTime += run.elapsed;

            timing.worksheets.push(worksheet);

        }

        return timing;

    }

}
