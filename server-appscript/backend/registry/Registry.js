class Registry {

    constructor() {

        this.spreadsheets = {

            MASTER: "1C8rsAWa0XfpxfHSb-F-FALSvmCT1knQ5lBoegQ8Phwc",

            HR: "",

            SALES: "",

            CONFIG: ""

        };

    }

    getSpreadsheetId(alias) {

        const id = this.spreadsheets[alias];

        if (!id) {

            throw new Error("Unknown spreadsheet : " + alias);

        }

        return id;

    }

}