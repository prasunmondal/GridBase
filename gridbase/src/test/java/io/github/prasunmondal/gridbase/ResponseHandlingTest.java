package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.internal.ResponseParser;
import io.github.prasunmondal.gridbase.result.AddColumnsResult;
import io.github.prasunmondal.gridbase.result.ClearResult;
import io.github.prasunmondal.gridbase.result.ColumnsResult;
import io.github.prasunmondal.gridbase.result.ExecutionResponse;
import io.github.prasunmondal.gridbase.result.LastUpdatedResult;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.result.RowsResult;
import io.github.prasunmondal.gridbase.result.WorksheetCreated;
import io.github.prasunmondal.gridbase.spec.Operation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
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

    private static GridBase client(FakeTransport t) {
        return GridBase.builder().transport(t).defaultSpreadsheetId("S")
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
        GridBase db = client(t);
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
        GridBase db = client(new FakeTransport(req -> {
            calls.incrementAndGet();
            throw new TransportException("timeout", -1, null, true);
        }));
        assertThrows(TransportException.class, () -> db.worksheet("Orders").insert(java.util.Map.of("id", 1)).execute());
        assertEquals(1, calls.get());
    }

    @Test
    void writesAreRetriedWhenOptedIn() {
        AtomicInteger calls = new AtomicInteger();
        GridBase db = GridBase.builder().defaultSpreadsheetId("S")
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

    // ------------------------------------------------------------------ parser and error classification

    private static final ResponseParser PARSER = new ResponseParser(new ObjectMapper(), ZoneOffset.UTC);

    @Test
    void sliceRenumbersAndRejectsOutOfRange() {
        String combined = "{\"success\":true,\"requestId\":\"R\",\"executionTime\":7,\"debug\":[\"d\"],\"results\":["
                + "{\"operationId\":\"op-1\",\"rowCount\":1,\"rows\":[{\"n\":1}]},"
                + "{\"operationId\":\"op-2\",\"rowCount\":1,\"rows\":[{\"n\":2}]},"
                + "{\"operationId\":\"op-3\",\"rowCount\":1,\"rows\":[{\"n\":3}]}]}";

        JsonNode slice = FakeTransport.json(PARSER.slice(combined, 1, 2));
        assertEquals("R", slice.path("requestId").asText());
        assertEquals(7, slice.path("executionTime").asInt());
        assertEquals(FakeTransport.json("[\"d\"]"), slice.path("debug"));
        assertEquals(2, slice.path("results").size());
        assertEquals("op-1", slice.path("results").get(0).path("operationId").asText());
        assertEquals(2, slice.path("results").get(0).path("rows").get(0).path("n").asInt());
        assertEquals("op-2", slice.path("results").get(1).path("operationId").asText());
        assertEquals(3, slice.path("results").get(1).path("rows").get(0).path("n").asInt());

        assertThrows(TransportException.class, () -> PARSER.slice(combined, 2, 2));
        assertThrows(TransportException.class, () -> PARSER.slice("not json", 0, 1));
    }

    @Test
    void everyResultTypeParses() {
        GridBase db = client(FakeTransport.replying());
        Worksheet t = db.worksheet("T");
        List<Operation> ops = List.of(t.select().toOperation(), t.insert(Map.of("a", 1)).toOperation(),
                t.create().toOperation(), t.columns().toOperation(), t.addColumns("x").toOperation(),
                t.clear().toOperation(), t.lastUpdated().toOperation());
        String body = "{\"success\":true,\"requestId\":\"R\",\"results\":["
                + "{\"operationId\":\"op-1\",\"rows\":[{\"a\":1},{\"a\":2}]},"
                + "{\"operationId\":\"op-2\",\"rowCount\":1,\"rows\":[{\"a\":1}]},"
                + "{\"operationId\":\"op-3\",\"sheetId\":9},"
                + "{\"operationId\":\"op-4\",\"worksheet\":\"T\",\"columns\":[\"a\",null]},"
                + "{\"operationId\":\"op-5\",\"columns\":[\"x\"],\"skippedColumns\":[],\"startColumn\":2,\"count\":1},"
                + "{\"operationId\":\"op-6\",\"rowsCleared\":2,\"columnsCleared\":1},"
                + "{\"operationId\":\"op-7\",\"spreadsheetId\":\"S\",\"lastUpdated\":1760000000000}]}";

        ExecutionResponse response = PARSER.parse(body, ops);

        assertEquals("R", response.requestId());
        assertEquals(2, ((RowsResult) response.results().get(0)).count());   // rowCount defaults to rows
        assertEquals(1, ((RowsResult) response.results().get(1)).count());
        WorksheetCreated created = (WorksheetCreated) response.results().get(2);
        assertEquals("T", created.worksheet());                              // defaults to the operation's
        assertEquals(9L, created.sheetId());
        assertEquals(List.of("a", ""), ((ColumnsResult) response.results().get(3)).columns());
        assertEquals(2, ((AddColumnsResult) response.results().get(4)).startColumn());
        assertEquals(2, ((ClearResult) response.results().get(5)).rowsCleared());
        assertEquals(Instant.ofEpochMilli(1760000000000L), ((LastUpdatedResult) response.results().get(6)).lastUpdated());

        String noTime = "{\"success\":true,\"results\":[{\"operationId\":\"op-1\",\"spreadsheetId\":\"S\"}]}";
        assertThrows(IllegalStateException.class, () -> PARSER.parse(noTime, List.of(t.lastUpdated().toOperation())));
    }

    @Test
    void transientMarkersAreRetryableDailyQuotaIsNot() {
        for (String message : List.of(
                "Service invoked too many times in a short time: exec.",
                "Lock timeout: another process was holding the lock for too long.",
                "Exceeded maximum execution time: timed out",
                "Service unavailable: Spreadsheets",
                "Please try again later.",
                "Rate Limit Exceeded",
                "LOCK TIMEOUT")) {
            assertTrue(new ServerException(message, "Error", null, null).isRetryable(), message);
        }
        assertFalse(new ServerException("Service invoked too many times for one day: urlfetch.", "Error", null, null)
                .isRetryable());
        assertFalse(new ServerException("Unknown column: x", "Error", null, null).isRetryable());
        ServerException noMessage = new ServerException(null, null, null, null);
        assertFalse(noMessage.isRetryable());
        assertTrue(noMessage.getServerStackTrace().isEmpty());
        assertEquals("gridbase engine error: null", noMessage.getMessage());
    }

    @Test
    void nonObjectJsonReplyIsATransportError() {
        for (String reply : List.of("[]", "42", "\"text\"")) {
            TransportException e = assertThrows(TransportException.class,
                    () -> client(new FakeTransport(req -> reply)).worksheet("T").select().fetch());
            assertTrue(e.getMessage().contains("not a JSON object"), e.getMessage());
            assertFalse(e.isRetryable());
        }
    }

    public enum Status { OPEN, CLOSED }

    public static class Typed {
        public Instant at;
        public OffsetDateTime offset;
        public ZonedDateTime zoned;
        public LocalDateTime local;
        public LocalDateTime localFromMillis;
        public LocalDate day;
        public Status status;
        public BigDecimal amount;
        public Boolean flag;
        public Integer count;
        public Long big;
        public Double ratio;
    }

    @Test
    void javaTimeAndNumberMapping() {
        // 2026-09-29T18:30:00Z is 30 Sep 2026 00:00 in Asia/Kolkata.
        Instant midnightIst = Instant.parse("2026-09-29T18:30:00Z");
        String cell = "\"2026-09-29T18:30:00.000Z\"";
        FakeTransport t = FakeTransport.replying("\"rowCount\":1,\"rows\":[{"
                + "\"at\":" + cell + ",\"offset\":" + cell + ",\"zoned\":" + cell + ",\"local\":" + cell
                + ",\"localFromMillis\":" + midnightIst.toEpochMilli() + ",\"day\":\"2026-09-30\""
                + ",\"status\":\"OPEN\",\"amount\":\"12.50\",\"flag\":\"TRUE\",\"count\":\"42\""
                + ",\"big\":1760000000000,\"ratio\":0.25}]");
        Typed typed = client(t).worksheet("T").select().fetch(Typed.class).get(0);

        assertEquals(midnightIst, typed.at);
        assertEquals(midnightIst, typed.offset.toInstant());
        assertEquals(midnightIst, typed.zoned.toInstant());
        assertEquals(LocalDateTime.of(2026, 9, 30, 0, 0), typed.local);
        assertEquals(LocalDateTime.of(2026, 9, 30, 0, 0), typed.localFromMillis);
        assertEquals(LocalDate.of(2026, 9, 30), typed.day);
        assertEquals(Status.OPEN, typed.status);
        assertEquals(new BigDecimal("12.50"), typed.amount);
        assertTrue(typed.flag);
        assertEquals(42, typed.count);
        assertEquals(1760000000000L, typed.big);
        assertEquals(0.25, typed.ratio);

        Row row = client(t).worksheet("T").select().fetch().get(0);
        assertEquals(midnightIst, row.getInstant("at"));
        assertEquals(LocalDateTime.of(2026, 9, 30, 0, 0), row.getLocalDateTime("local"));
        assertEquals(42, row.getInteger("count"));
        assertEquals(new BigDecimal("12.50"), row.getBigDecimal("amount"));
    }
}
