/**
 * SheetEngine Entry Point
 */

function doPost(e) {
    return SheetEngine.instance().handlePost(e);
}

function doGet(e) {
    return SheetEngine.instance().handleGet(e);
}

function authorize() {
  Drive.Files.list({
    pageSize: 1,
    fields: "files(id, name)"
  });
}


// csmments