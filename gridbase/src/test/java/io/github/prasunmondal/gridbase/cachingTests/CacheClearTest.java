package io.github.prasunmondal.gridbase.cachingTests;

import io.github.prasunmondal.gridbase.GridBaseTable;
import io.github.prasunmondal.gridbase.SheetProperties;
import io.github.prasunmondal.gridbase.Worksheet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Erasing cached data on demand: one tab ({@code clearCache} / {@code clearTableCache}) or everything ({@code clearAllCache}). */
class CacheClearTest extends CachingTestBase {

    private static final class Products extends GridBaseTable<Product> {
        Products(SheetProperties properties) {
            super(properties, Product.class);
        }
    }

    private void readEverything(SheetProperties p) {
        nameOf(p, "P1");
        p.client().worksheet(SHEET, ORDERS).select().fetch();
        p.client().worksheet(OTHER_SHEET, PRODUCTS).select().fetch();
    }

    @Test
    @DisplayName("Worksheet.clearCache erases only that tab of that spreadsheet")
    void worksheetClearCacheErasesOneTab() {
        SheetProperties p = cached();
        readEverything(p);
        renameDirectly("P1", "Green Tea");

        assertTrue(p.worksheet().clearCache() > 0);
        assertEquals("Green Tea", nameOf(p, "P1"));                       // fresh from the network
        assertCalls(4);

        readEverything(p);                                                 // Orders and SS2/Products still cached
        assertCalls(4);
    }

    @Test
    @DisplayName("clearAllCache erases every tab of every spreadsheet")
    void clearAllCacheErasesEverything() {
        SheetProperties p = cached();
        readEverything(p);
        renameDirectly("P1", "Green Tea");

        p.clearAllCache();
        readEverything(p);
        assertCalls(6);
        assertEquals("Green Tea", nameOf(p, "P1"));
        assertCalls(6);
    }

    @Test
    @DisplayName("GridBaseTable.clearTableCache erases its tab; clearAllCache erases the rest too")
    void gridBaseTable() {
        SheetProperties p = cached();
        Products products = new Products(p);
        products.repository().findById("P1");
        readEverything(p);                                                 // its P1 read is the same query: a hit
        assertCalls(3);
        renameDirectly("P1", "Green Tea");

        assertTrue(products.clearTableCache() > 0);
        assertEquals("Green Tea", products.repository().findById("P1").orElseThrow().name);
        p.client().worksheet(SHEET, ORDERS).select().fetch();              // untouched
        assertCalls(4);

        products.clearAllCache();
        p.client().worksheet(SHEET, ORDERS).select().fetch();
        assertCalls(5);
    }

    @Test
    @DisplayName("without a cache, clearing does nothing")
    void withoutCache() {
        SheetProperties p = track(base().build());
        Worksheet products = p.worksheet();

        assertEquals(0, products.clearCache());
        p.clearAllCache();
        assertEquals(0, new Products(p).clearTableCache());
        assertCalls(0);
    }
}
