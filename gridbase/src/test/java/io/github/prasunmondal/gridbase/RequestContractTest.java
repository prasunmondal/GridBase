package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.query.Sort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.prasunmondal.gridbase.FakeTransport.json;
import static io.github.prasunmondal.gridbase.query.Filters.between;
import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static io.github.prasunmondal.gridbase.query.Filters.gt;
import static io.github.prasunmondal.gridbase.query.Filters.in;
import static io.github.prasunmondal.gridbase.query.Filters.ne;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The request JSON must match what the engine's RequestParser reads. */
class RequestContractTest {

    private final FakeTransport transport = FakeTransport.replying("\"rowCount\":0,\"rows\":[]");
    private final GridBase db = GridBase.builder()
            .transport(transport)
            .defaultSpreadsheetId("SHEET")
            .timeZone(ZoneId.of("Asia/Kolkata"))
            .build();

    @Test
    void selectSerializesWhereOrderByProjectionAndPaging() {
        db.worksheet("Orders").select("id", "qty")
                .where(eq("status", "OPEN"), gt("qty", 5))
                .orderBy("createdAt", Sort.Direction.DESC)
                .limit(10)
                .offset(20)
                .fetch();

        assertEquals(json("{\"id\":\"op-1\",\"type\":\"SELECT\",\"spreadsheetId\":\"SHEET\",\"worksheet\":\"Orders\","
                        + "\"where\":[{\"column\":\"status\",\"operator\":\"EQUALS\",\"value\":\"OPEN\"},"
                        + "{\"column\":\"qty\",\"operator\":\"GREATER_THAN\",\"value\":5}],"
                        + "\"orderBy\":[{\"column\":\"createdAt\",\"direction\":\"DESC\"}],"
                        + "\"select\":[\"id\",\"qty\"],\"limit\":10,\"offset\":20}"),
                transport.lastOperation());
    }

    @Test
    void defaultsOmitLimitAndOffset() {
        db.worksheet("Orders").select().fetch();
        assertFalse(transport.lastOperation().has("limit"));
        assertFalse(transport.lastOperation().has("offset"));
        assertFalse(transport.lastOperation().has("where"));
    }

    @Test
    void equalsValuesAreRenderedLikeJavaScriptString() {
        db.worksheet("T").select().where(eq("a", 5.0), eq("b", true), eq("c", 12.50), ne("d", 7L)).fetch();
        var where = transport.lastOperation().path("where");
        assertEquals("5", where.get(0).path("value").asText());
        assertEquals("true", where.get(1).path("value").asText());
        assertEquals("12.5", where.get(2).path("value").asText());
        assertEquals("7", where.get(3).path("value").asText());
        assertTrue(where.get(0).path("value").isTextual());
    }

    @Test
    void eqNullBecomesIsNull() {
        db.worksheet("T").select().where(eq("a", null), ne("b", null)).fetch();
        var where = transport.lastOperation().path("where");
        assertEquals(json("{\"column\":\"a\",\"operator\":\"IS_NULL\"}"), where.get(0));
        assertEquals(json("{\"column\":\"b\",\"operator\":\"IS_NOT_NULL\"}"), where.get(1));
    }

    @Test
    void temporalComparisonsUseEpochMillisInSheetZone() {
        Instant instant = Instant.parse("2026-09-30T00:00:00Z");
        LocalDate date = LocalDate.of(2026, 9, 30);
        db.worksheet("T").select().where(gt("at", instant), between("day", date, date.plusDays(1))).fetch();
        var where = transport.lastOperation().path("where");
        assertEquals(instant.toEpochMilli(), where.get(0).path("value").asLong());
        long istMidnight = date.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();
        assertEquals(istMidnight, where.get(1).path("minimum").asLong());
        assertEquals(istMidnight + 86_400_000L, where.get(1).path("maximum").asLong());
    }

    @Test
    void inKeepsRawJsonTypes() {
        db.worksheet("T").select().where(in("qty", 1, 2, "x")).fetch();
        assertEquals(json("{\"column\":\"qty\",\"operator\":\"IN\",\"values\":[1,2,\"x\"]}"),
                transport.lastOperation().path("where").get(0));
    }

    @Test
    void insertAlwaysUsesExplicitRowsForm() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "A1");
        row.put("meta", Map.of("k", "v"));
        row.put("note", null);
        db.worksheet("T").insert(row).execute();

        assertEquals(json("{\"id\":\"op-1\",\"type\":\"INSERT\",\"spreadsheetId\":\"SHEET\",\"worksheet\":\"T\","
                        + "\"rows\":[{\"id\":{\"value\":\"A1\",\"operation\":\"SET\"},"
                        + "\"meta\":{\"value\":{\"k\":\"v\"},\"operation\":\"SET\"},"
                        + "\"note\":{\"value\":null,\"operation\":\"SET\"}}]}"),
                transport.lastOperation());
    }

    @Test
    void updateCarriesValueOperations() {
        db.worksheet("T").update().set("status", "DONE").append("log", "|closed").prepend("tag", "#")
                .where(eq("id", 42)).execute();
        var op = transport.lastOperation();
        assertEquals("UPDATE", op.path("type").asText());
        assertEquals(json("{\"status\":{\"value\":\"DONE\",\"operation\":\"SET\"},"
                + "\"log\":{\"value\":\"|closed\",\"operation\":\"APPEND\"},"
                + "\"tag\":{\"value\":\"#\",\"operation\":\"PREPEND\"}}"), op.path("values"));
        assertEquals("42", op.path("where").get(0).path("value").asText());
    }

    @Test
    void upsertKeyAddsFilterAndValue() {
        db.worksheet("T").upsert().key("id", "C-1").set("name", "Asha").execute();
        var op = transport.lastOperation();
        assertEquals("C-1", op.path("where").get(0).path("value").asText());
        assertEquals("C-1", op.path("values").path("id").path("value").asText());
    }

    @Test
    void addColumnsSendsColumnsAndSkipExisting() {
        FakeTransport t = FakeTransport.replying(
                "\"worksheet\":\"T\",\"columns\":[\"b\"],\"skippedColumns\":[\"a\"],\"startColumn\":3,\"count\":1");
        GridBase client = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();
        var result = client.worksheet("T").addColumns("a", "b").skipExisting().execute();
        assertEquals(json("[\"a\",\"b\"]"), t.lastOperation().path("columns"));
        assertTrue(t.lastOperation().path("skipExisting").asBoolean());
        assertEquals(3, result.startColumn());
        assertEquals(java.util.List.of("a"), result.skippedColumns());
    }

    @Test
    void unsafeWritesAreRejectedBeforeSending() {
        var ws = db.worksheet("T");
        assertThrows(IllegalStateException.class, () -> ws.update().set("a", 1).execute());
        assertThrows(IllegalStateException.class, () -> ws.delete().execute());
        assertThrows(IllegalStateException.class, () -> ws.upsert().set("a", 1).execute());
        assertThrows(IllegalStateException.class, () -> ws.cloneRows().execute());
        assertThrows(IllegalArgumentException.class, () -> ws.update().set("a", 1).append("a", "x"));
        assertEquals(0, transport.requests.size());
    }

    @Test
    void explicitAllAllowsUnfilteredWrites() {
        db.worksheet("T").delete().all().execute();
        assertFalse(transport.lastOperation().has("where"));
    }
}
