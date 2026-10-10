package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.result.RowsResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing engine replies, typed row access, batching and retries. */
class ResponseHandlingTest {

    public static class Order {
        public String id;
        public Integer qty;
        public BigDecimal price;
        @JsonProperty("Order Date")
        public LocalDate orderDate;
        public Instant updatedAt;
        public Boolean paid;
    }

    private static HibernateSheets client(FakeTransport t) {
        return HibernateSheets.builder().transport(t).defaultSpreadsheetId("S")
                .timeZone(ZoneId.of("Asia/Kolkata"))
                .retryPolicy(RetryPolicy.of(3, Duration.ZERO))
                .build();
    }

    // "2026-09-29T18:30:00.000Z" is how Apps Script serializes a 30-Sep-2026 date cell in Asia/Kolkata.
    private static final String ORDER_ROWS = "\"rowCount\":2,\"rows\":["
            + "{\"id\":\"A1\",\"qty\":3,\"price\":12.5,\"Order Date\":\"2026-09-29T18:30:00.000Z\","
            + "\"updatedAt\":\"2026-09-30T07:45:10.000Z\",\"paid\":true},"
            + "{\"id\":\"A2\",\"qty\":\"\",\"price\":\"\",\"Order Date\":\"\",\"updatedAt\":\"\",\"paid\":\"\"}]";

    @Test
    void rowsMapToPojosWithSheetZoneDatesAndBlankCellsAsNull() {
        List<Order> orders = client(FakeTransport.replying(ORDER_ROWS)).worksheet("Orders").select().fetch(Order.class);

        Order a1 = orders.get(0);
        assertEquals("A1", a1.id);
        assertEquals(3, a1.qty.intValue());
        assertEquals(new BigDecimal("12.5"), a1.price);
        assertEquals(LocalDate.of(2026, 9, 30), a1.orderDate);
        assertEquals(Instant.parse("2026-09-30T07:45:10Z"), a1.updatedAt);
        assertTrue(a1.paid);

        Order a2 = orders.get(1);
        assertNull(a2.qty);
        assertNull(a2.price);
        assertNull(a2.orderDate);
        assertNull(a2.updatedAt);
        assertNull(a2.paid);
    }

    @Test
    void typedRowGetters() {
        Row row = client(FakeTransport.replying(ORDER_ROWS)).worksheet("Orders").select().fetch().get(0);
        assertEquals("12.5", row.getString("price"));
        assertEquals(3L, row.getLong("qty").longValue());
        assertEquals(LocalDate.of(2026, 9, 30), row.getLocalDate("Order Date"));
        assertTrue(row.getBoolean("paid"));
        assertFalse(row.isBlank("id"));

        Row blank = client(FakeTransport.replying(ORDER_ROWS)).worksheet("Orders").select().fetch().get(1);
        assertTrue(blank.isBlank("qty"));
        assertNull(blank.getInteger("qty"));
        assertEquals("", blank.get("qty"));
    }

    @Test
    void engineErrorBecomesServerException() {
        FakeTransport t = new FakeTransport(req -> "{\"success\":false,\"error\":\"Unknown column: stauts\","
                + "\"exceptionType\":\"Error\",\"debug\":[],\"stackTrace\":[\"Error: Unknown column\",\"at x\"]}");
        ServerException e = assertThrows(ServerException.class,
                () -> client(t).worksheet("Orders").select().where(eq("stauts", "x")).fetch());
        assertEquals("Unknown column: stauts", e.getServerMessage());
        assertEquals("Error", e.getExceptionType());
        assertEquals(2, e.getServerStackTrace().size());
        assertFalse(e.isRetryable());
        assertEquals(1, t.requests.size());
    }

    @Test
    void nonJsonReplyIsReportedClearly() {
        FakeTransport t = new FakeTransport(req -> "Script function not found: doPost");
        TransportException e = assertThrows(TransportException.class,
                () -> client(t).worksheet("Orders").select().fetch());
        assertTrue(e.getMessage().contains("not valid JSON"));
    }

    @Test
    void batchSendsOneRequestAndResolvesRefs() {
        FakeTransport t = FakeTransport.replying(
                "\"rowCount\":1,\"rows\":[{\"id\":\"N1\"}]",
                "\"worksheet\":\"Orders\",\"columns\":[\"id\",\"qty\"]",
                "\"rowCount\":1,\"rows\":[{\"id\":\"N1\"}]");
        HibernateSheets db = client(t);
        Worksheet orders = db.worksheet("Orders");

        Batch batch = db.batch();
        Ref<RowsResult> inserted = batch.add(orders.insert(java.util.Map.of("id", "N1")));
        var columns = batch.add(orders.columns());
        Ref<RowsResult> selected = batch.add(orders.select().where(eq("id", "N1")));
        BatchResult result = batch.execute();

        assertEquals(1, t.requests.size());
        assertEquals(3, t.requests.get(0).path("operations").size());
        assertEquals("op-3", t.requests.get(0).path("operations").get(2).path("id").asText());
        assertEquals(1, inserted.get().count());
        assertEquals(List.of("id", "qty"), columns.get().columns());
        assertEquals("N1", result.get(selected).rows().get(0).getString("id"));
        assertEquals(12L, result.executionTimeMillis());
        assertThrows(IllegalStateException.class, batch::execute);
    }

    @Test
    void readsAreRetriedOnTransientFailures() {
        AtomicInteger calls = new AtomicInteger();
        FakeTransport ok = FakeTransport.replying("\"rowCount\":0,\"rows\":[]");
        FakeTransport t = new FakeTransport(req -> {
            if (calls.incrementAndGet() < 3) {
                return "{\"success\":false,\"error\":\"Service invoked too many times in a short time: exec. Try Utilities.sleep(1000) between calls.\"}";
            }
            return ok.send(req.toString());
        });
        client(t).worksheet("Orders").select().fetch();
        assertEquals(3, calls.get());
    }

    @Test
    void writesAreNotRetriedByDefault() {
        AtomicInteger calls = new AtomicInteger();
        HibernateSheets db = client(new FakeTransport(req -> {
            calls.incrementAndGet();
            throw new TransportException("timeout", -1, null, true);
        }));
        assertThrows(TransportException.class, () -> db.worksheet("Orders").insert(java.util.Map.of("id", 1)).execute());
        assertEquals(1, calls.get());
    }

    @Test
    void writesAreRetriedWhenOptedIn() {
        AtomicInteger calls = new AtomicInteger();
        HibernateSheets db = HibernateSheets.builder().defaultSpreadsheetId("S")
                .retryPolicy(RetryPolicy.of(2, Duration.ZERO).retryingWrites(true))
                .transport(new FakeTransport(req -> {
                    calls.incrementAndGet();
                    throw new TransportException("timeout", -1, null, true);
                })).build();
        assertThrows(TransportException.class, () -> db.worksheet("Orders").insert(java.util.Map.of("id", 1)).execute());
        assertEquals(2, calls.get());
    }

    @Test
    void dailyQuotaIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        FakeTransport t = new FakeTransport(req -> {
            calls.incrementAndGet();
            return "{\"success\":false,\"error\":\"Service invoked too many times for one day: urlfetch.\"}";
        });
        ServerException e = assertThrows(ServerException.class, () -> client(t).worksheet("Orders").select().fetch());
        assertFalse(e.isRetryable());
        assertEquals(1, calls.get());
    }

    @Test
    void missingResultIsDetected() {
        FakeTransport t = new FakeTransport(req -> "{\"success\":true,\"results\":[]}");
        assertThrows(TransportException.class, () -> client(t).worksheet("Orders").select().fetch());
    }
}
