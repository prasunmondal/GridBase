class GoogleSheetsProvider {

    constructor(resources) {

        this.resources = resources;

        this.loader =
            new WorksheetLoader(resources);

        this.committer =
            new WorksheetCommitter();

    }

    getWorksheetData(context,
                     spreadsheetId,
                     worksheet) {

        const reference =
            this.resources.data.get(
                spreadsheetId,
                worksheet
            );

        if (!reference.isLoaded()) {

            this.loader.load(
                context,
                reference
            );

        }

        return reference.data;

    }

    commit(worksheetData) {

        this.committer.commit(
            worksheetData
        );

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