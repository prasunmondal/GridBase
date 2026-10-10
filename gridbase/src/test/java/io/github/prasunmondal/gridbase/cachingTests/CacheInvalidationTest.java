package io.github.prasunmondal.gridbase.cachingTests;

import io.github.prasunmondal.gridbase.APIRequestsQueue;
import io.github.prasunmondal.gridbase.Batch;
import io.github.prasunmondal.gridbase.Queued;
import io.github.prasunmondal.gridbase.SheetProperties;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.mapping.Repository;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.result.RowsResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static io.github.prasunmondal.gridbase.query.Filters.gt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Any write through a cache-enabled client drops the cached reads of the worksheet it touched. */
class CacheInvalidationTest extends CachingTestBase {

    static Stream<Arguments> writes() {
        return Stream.of(
                write("insert", p -> p.worksheet().insert(Map.of("sku", "P9", "name", "New")).execute()),
                write("insertAll", p -> p.worksheet().insertAll(List.of(Map.of("sku", "P9"), Map.of("sku", "P10"))).execute()),
                write("update", p -> p.worksheet().update().set("name", "Renamed").where(eq("sku", "P1")).execute()),
                write("update with append", p -> p.worksheet().update().append("notes", "!").where(eq("sku", "P2")).execute()),
                write("delete", p -> p.worksheet().delete().where(eq("sku", "P5")).execute()),
                write("upsert (existing row)", p -> p.worksheet().upsert().key("sku", "P1").set("name", "Upserted").execute()),
                write("upsert (new row)", p -> p.worksheet().upsert().key("sku", "P8").set("name", "Upserted").execute()),
                write("clone", p -> p.worksheet().cloneRows().where(eq("sku", "P1")).set("sku", "P1-copy").execute()),
                write("clear", p -> p.worksheet().clear().execute()),
                write("addColumns", p -> p.worksheet().addColumns("discount").execute()),
                write("repository upsert", p -> products(p).upsert(Product.of("P1", "Saved", 1, "1.00"))),
                write("repository upsertAll", p -> products(p).upsertAll(List.of(
                        Product.of("P1", "Saved", 1, "1.00"), Product.of("P6", "Jam", 3, "3.00")))),
                write("repository save", p -> products(p).save(Product.of("P1", "Saved", 1, "1.00"))),
                write("repository saveAll", p -> products(p).saveAll(List.of(
                        Product.of("P1", "Saved", 1, "1.00"), Product.of("P6", "Jam", 3, "3.00")))),
                write("repository insert", p -> products(p).insert(Product.of("P7", "Salt", 9, "0.50"))),
                write("repository deleteById", p -> products(p).deleteById("P2")),
                write("batch", p -> {
                    Batch batch = p.client().batch();
                    batch.add(p.worksheet().update().set("stock", 99).where(eq("sku", "P3")));
                    batch.add(p.worksheet().select().where(eq("sku", "P3")));
                    batch.execute();
                }),
                write("async update", p -> p.worksheet().update().set("name", "Async").where(eq("sku", "P1"))
                        .executeAsync().join()),
                write("queued update", p -> {
                    APIRequestsQueue reqQ = new APIRequestsQueue();
                    p.worksheet().update().set("name", "Queued").where(eq("sku", "P1")).queue(reqQ);
                    reqQ.execute();
                }));
    }

    private static Arguments write(String name, Consumer<SheetProperties> action) {
        return Arguments.of(name, action);
    }

    @ParameterizedTest(name = "{0} invalidates cached reads of the worksheet")
    @MethodSource("writes")
    void everyWriteInvalidates(String name, Consumer<SheetProperties> write) {
        SheetProperties p = cached();
        List<Row> before = allProducts(p);
        allProducts(p);
        assertCalls(1);

        write.accept(p);
        int afterWrite = engine.calls();

        List<Row> after = allProducts(p);
        assertEquals(afterWrite + 1, engine.calls(), "the read after the write must go to the network");
        assertNotEquals(before, after, "the read after the write must return the changed data");

        allProducts(p);
        assertEquals(afterWrite + 1, engine.calls(), "the fresh reply is cached again");
    }

    @Test
    @DisplayName("every cached query on the written worksheet is dropped, not only the identical one")
    void allQueriesOnWorksheetDropped() {
        SheetProperties p = cached();
        allProducts(p);
        nameOf(p, "P4");
        p.worksheet().select().where(gt("stock", 10)).fetch();
        assertCalls(3);

        p.worksheet().update().set("stock", 1).where(eq("sku", "P1")).execute();
        assertCalls(4);

        allProducts(p);
        nameOf(p, "P4");
        p.worksheet().select().where(gt("stock", 10)).fetch();
        assertCalls(7);
    }

    @Test
    @DisplayName("other worksheets stay cached")
    void otherWorksheetsStayCached() {
        SheetProperties p = cached();
        allProducts(p);
        p.client().worksheet(SHEET, ORDERS).select().fetch();

        p.worksheet().delete().where(eq("sku", "P5")).execute();
        p.client().worksheet(SHEET, ORDERS).select().fetch();
        assertCalls(3);
        allProducts(p);
        assertCalls(4);
    }

    @Test
    @DisplayName("the same tab name in another spreadsheet stays cached")
    void sameTabOtherSpreadsheetStaysCached() {
        SheetProperties p = cached();
        p.client().worksheet(OTHER_SHEET, PRODUCTS).select().fetch();

        p.worksheet().update().set("name", "x").where(eq("sku", "P1")).execute();
        p.client().worksheet(OTHER_SHEET, PRODUCTS).select().fetch();
        assertCalls(2);
    }

    @Test
    @DisplayName("a write that times out still invalidates (it may have been committed)")
    void failedWriteStillInvalidates() {
        SheetProperties p = cached();
        allProducts(p);

        engine.goOffline();
        assertThrows(TransportException.class,
                () -> p.worksheet().update().set("name", "x").where(eq("sku", "P1")).execute());
        engine.goOnline();

        allProducts(p);
        assertCalls(3);
    }

    @Test
    @DisplayName("a write rejected by the engine also invalidates")
    void rejectedWriteStillInvalidates() {
        SheetProperties p = cached();
        allProducts(p);

        assertThrows(ServerException.class,
                () -> p.worksheet().cloneRows().where(eq("sku", "NOPE")).set("sku", "x").execute());
        allProducts(p);
        assertCalls(3);
    }

    @Test
    @DisplayName("a write through another client sharing the cache file invalidates this client's entries")
    void otherClientSameFileInvalidates() {
        SheetProperties reader = cached();
        SheetProperties writer = cached(b -> b.preNetworkCall(c -> { }));
        assertNotSame(reader.client(), writer.client());

        assertEquals("Tea", nameOf(reader, "P1"));
        writer.worksheet().update().set("name", "Green Tea").where(eq("sku", "P1")).execute();
        assertEquals("Green Tea", nameOf(reader, "P1"));
        assertCalls(3);
    }

    @Test
    @DisplayName("writes that bypass the cache (no cache / Sheets UI) stay invisible until invalidated or expired")
    void writesBypassingCacheNeedManualInvalidation() {
        SheetProperties reader = cached();
        SheetProperties uncached = track(base().build());

        assertEquals("Tea", nameOf(reader, "P1"));
        uncached.worksheet().update().set("name", "Green Tea").where(eq("sku", "P1")).execute();
        assertEquals("Tea", nameOf(reader, "P1"));                 // stale: this client never saw the write

        reader.cache().orElseThrow().invalidate(SHEET, PRODUCTS);
        assertEquals("Green Tea", nameOf(reader, "P1"));
    }

    @Test
    @DisplayName("read-your-own-writes: save then findById returns the saved data")
    void readYourOwnWrites() {
        SheetProperties p = cached();
        Repository<Product> repo = products(p);

        assertEquals("Tea", repo.findById("P1").orElseThrow().name);
        repo.upsert(Product.of("P1", "Masala Tea", 40, "5.00"));
        assertEquals("Masala Tea", repo.findById("P1").orElseThrow().name);
        repo.deleteById("P1");
        assertEquals(false, repo.findById("P1").isPresent());
    }

    @Test
    @DisplayName("queue [read, write] on one worksheet: the read is not left behind as a stale entry")
    void readThenWriteInOneQueue() {
        SheetProperties p = cached();

        APIRequestsQueue reqQ = new APIRequestsQueue();
        // limit(1) makes this the same query nameOf(...) sends, so a leftover entry would be hit below
        Queued<RowsResult> read = p.worksheet().select().where(eq("sku", "P1")).limit(1).queue(reqQ);
        p.worksheet().update().set("name", "Renamed").where(eq("sku", "P1")).queue(reqQ);
        reqQ.execute();
        assertCalls(1);
        assertEquals("Tea", read.get().rows().get(0).getString("name"));   // read ran before the write

        assertEquals("Renamed", nameOf(p, "P1"));
        assertCalls(2);
    }

    @Test
    @DisplayName("queue [write, read] on one worksheet: the read sees the write and is cached")
    void writeThenReadInOneQueue() {
        SheetProperties p = cached();

        APIRequestsQueue reqQ = new APIRequestsQueue();
        p.worksheet().update().set("name", "Renamed").where(eq("sku", "P1")).queue(reqQ);
        Queued<RowsResult> read = p.worksheet().select().where(eq("sku", "P1")).limit(1).queue(reqQ);
        reqQ.execute();
        assertEquals("Renamed", read.get().rows().get(0).getString("name"));

        assertEquals("Renamed", nameOf(p, "P1"));
        assertCalls(1);
    }

    @Test
    @DisplayName("invalidate() reports how many entries it dropped and leaves other worksheets alone")
    void invalidateCountsAndScope() {
        SheetProperties p = cached();
        allProducts(p);
        nameOf(p, "P1");
        p.client().worksheet(SHEET, ORDERS).select().fetch();

        assertEquals(2, p.cache().orElseThrow().invalidate(SHEET, PRODUCTS));
        assertEquals(0, p.cache().orElseThrow().invalidate(SHEET, PRODUCTS));
        p.client().worksheet(SHEET, ORDERS).select().fetch();
        assertCalls(3);
    }

    @Test
    @DisplayName("writes are never served from the cache: repeating an insert inserts twice")
    void writesAreNeverCached() {
        SheetProperties p = cached();
        p.worksheet().insert(Map.of("sku", "P9")).execute();
        p.worksheet().insert(Map.of("sku", "P9")).execute();
        assertCalls(2);
        assertEquals(7, engine.rowCount(SHEET, PRODUCTS));
    }
}
