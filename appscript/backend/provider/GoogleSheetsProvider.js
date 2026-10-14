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

    commitAll() {

        const references =
            this.resources.data.getAll();

        for (const reference of references) {

            if (!reference.isLoaded()) {
                continue;
            }

            this.committer.commit(
                reference.data,
                this.resources
            );

        }

    }

}
