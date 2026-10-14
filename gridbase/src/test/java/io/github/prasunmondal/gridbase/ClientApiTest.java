package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.cache.CacheExpiry;
import io.github.prasunmondal.gridbase.cache.CacheStrategy;
import io.github.prasunmondal.gridbase.cache.InMemoryResponseCache;
import io.github.prasunmondal.gridbase.query.Filters;
import io.github.prasunmondal.gridbase.query.Sort;
import io.github.prasunmondal.gridbase.result.ColumnsResult;
import io.github.prasunmondal.gridbase.result.RowsResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link GridBase} builder, execute variants, filter validation and {@link Worksheet} identity. */
class ClientApiTest {

    private static final String ROWS = "\"rowCount\":1,\"rows\":[{\"id\":\"1\"}]";

    @Test
    void builderNeedsEndpointOrTransport() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> GridBase.builder().build());
        assertEquals("Either endpoint(...) or transport(...) is required", e.getMessage());
    }

    @Test
    void worksheetWithoutDefaultSpreadsheet() {
        GridBase db = GridBase.builder().transport(FakeTransport.replying(ROWS)).build();
        assertThrows(IllegalStateException.class, () -> db.worksheet("T"));
        assertEquals("S2", db.worksheet("S2", "T").spreadsheetId());
    }

    @Test
    void executeOneAndAsyncVariants() {
        List<String> threads = Collections.synchronizedList(new ArrayList<>());
        FakeTransport replying = FakeTransport.replying("\"worksheet\":\"T\",\"columns\":[\"id\"]");
        FakeTransport t = new FakeTransport(req -> {
            threads.add(Thread.currentThread().getName());
            return replying.send(req.toString());
        });
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();
        Worksheet ws = db.worksheet("T");

        ColumnsResult now = db.executeOne(ws.columns().toOperation(), ColumnsResult.class);
        assertEquals(List.of("id"), now.columns());
        assertEquals(Thread.currentThread().getName(), threads.get(0));

        ColumnsResult later = db.executeOneAsync(ws.columns().toOperation(), ColumnsResult.class).join();
        assertEquals(List.of("id"), later.columns());
        assertEquals(List.of("id"), ws.columns().request().executeAsync().join().columns());
        assertEquals(List.of("id"), ((ColumnsResult) db.executeAsync(List.of(ws.columns().toOperation()))
                .join().results().get(0)).columns());
        assertEquals(List.of(Thread.currentThread().getName(), "gridbase-async", "gridbase-async", "gridbase-async"), threads);

        assertThrows(IllegalArgumentException.class, () -> db.execute(List.of()));
        assertThrows(ClassCastException.class, () -> db.executeOne(ws.columns().toOperation(), RowsResult.class));
    }

    @Test
    void filterAndSortRejectBlankColumns() {
        assertThrows(IllegalArgumentException.class, () -> eq("", 1));
        assertThrows(IllegalArgumentException.class, () -> eq(null, 1));
        assertThrows(IllegalArgumentException.class, () -> Filters.isNull(""));
        assertThrows(IllegalArgumentException.class, () -> Filters.in("", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> Filters.between("", 1, 2));
        assertThrows(NullPointerException.class, () -> Filters.between("a", null, 2));
        assertThrows(NullPointerException.class, () -> Filters.gt("a", null));
        assertThrows(IllegalArgumentException.class, () -> Sort.asc(""));
        assertThrows(NullPointerException.class, () -> new Sort("a", null));
        GridBase db = GridBase.builder().transport(FakeTransport.replying(ROWS)).defaultSpreadsheetId("S").build();
        assertThrows(IllegalArgumentException.class, () -> db.worksheet("T").select().orderBy(""));
        assertThrows(NullPointerException.class, () -> db.worksheet("T").select().where((io.github.prasunmondal.gridbase.query.Filter) null));
    }

    @Test
    void worksheetEqualsHashCodeAndClearCacheCount() {
        FakeTransport t = FakeTransport.replying(ROWS);
        GridBase cached = GridBase.builder().transport(t).defaultSpreadsheetId("S")
                .cache(new InMemoryResponseCache(), CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10)).build();
        GridBase other = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();

        assertEquals(cached.worksheet("T"), cached.worksheet("S", "T"));
        assertEquals(cached.worksheet("T").hashCode(), cached.worksheet("S", "T").hashCode());
        assertNotEquals(cached.worksheet("T"), other.worksheet("T"));
        assertNotEquals(cached.worksheet("T"), cached.worksheet("U"));
        assertNotEquals(cached.worksheet("T"), cached.worksheet("S2", "T"));
        assertEquals("S/T", cached.worksheet("T").toString());

        cached.worksheet("T").select().fetch();
        cached.worksheet("T").select().where(eq("id", "1")).fetch();
        cached.worksheet("U").select().fetch();
        assertEquals(2, cached.worksheet("T").clearCache());
        assertEquals(0, cached.worksheet("T").clearCache());
        assertEquals(1, cached.worksheet("U").clearCache());
        assertEquals(0, other.worksheet("T").clearCache());
    }
}
