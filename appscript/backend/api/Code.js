/**
 * SheetEngine Entry Point
 */

function doPost(e) {
    return SheetEngine.instance().handlePost(e);
}

function doGet(e) {
    return SheetEngine.instance().handleGet(e);
}

// csmments