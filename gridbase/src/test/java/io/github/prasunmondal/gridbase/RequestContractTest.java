package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.prasunmondal.gridbase.mapping.IgnoreUnknownColumns;
import io.github.prasunmondal.gridbase.mapping.OptionalColumn;
import io.github.prasunmondal.gridbase.mapping.Repository;
import io.github.prasunmondal.gridbase.mapping.SheetKey;
import io.github.prasunmondal.gridbase.query.Sort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.prasunmondal.gridbase.FakeTransport.json;
import static io.github.prasunmondal.gridbase.query.Filters.between;
import static io.github.prasunmondal.gridbase.query.Filters.contains;
import static io.github.prasunmondal.gridbase.query.Filters.endsWith;
import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static io.github.prasunmondal.gridbase.query.Filters.gt;
import static io.github.prasunmondal.gridbase.query.Filters.in;
import static io.github.prasunmondal.gridbase.query.Filters.ne;
import static io.github.prasunmondal.gridbase.query.Filters.startsWith;
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
    void insertSendsPlainValuesBareAndObjectsExplicitly() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "A1");
        row.put("qty", 3);
        row.put("ok", true);
        row.put("tags", List.of("a", "b"));
        row.put("meta", Map.of("k", "v"));
        row.put("note", null);
        db.worksheet("T").insert(row).execute();

        // Bare values are SET in every engine version; an object must be wrapped or it reads as a descriptor.
        assertEquals(json("{\"id\":\"op-1\",\"type\":\"INSERT\",\"spreadsheetId\":\"SHEET\",\"worksheet\":\"T\","
                        + "\"rows\":[{\"id\":\"A1\",\"qty\":3,\"ok\":true,\"tags\":[\"a\",\"b\"],"
                        + "\"meta\":{\"value\":{\"k\":\"v\"},\"operation\":\"SET\"},"
                        + "\"note\":null}]}"),
                transport.lastOperation());
    }

    @Test
    void updateCarriesValueOperations() {
        db.worksheet("T").update().set("status", "DONE").append("log", "|closed").prepend("tag", "#")
                .where(eq("id", 42)).execute();
        var op = transport.lastOperation();
        assertEquals("UPDATE", op.path("type").asText());
        assertEquals(json("{\"status\":\"DONE\","
                + "\"log\":{\"value\":\"|closed\",\"operation\":\"APPEND\"},"
                + "\"tag\":{\"value\":\"#\",\"operation\":\"PREPEND\"}}"), op.path("values"));
        assertEquals("42", op.path("where").get(0).path("value").asText());
    }

    @Test
    void upsertKeyAddsFilterAndValue() {
        db.worksheet("T").upsert().key("id", "C-1").set("name", "Asha").execute();
        var op = transport.lastOperation();
        assertEquals("C-1", op.path("where").get(0).path("value").asText());
        assertEquals("C-1", op.path("values").path("id").asText());
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

    public static class Contact {
        @SheetKey public String id;
        public String name;
        @OptionalColumn @JsonProperty("Phone No") public String phone;
    }

    public record Tag(String id, @OptionalColumn String colour) {
    }

    @IgnoreUnknownColumns
    public record Note(String id, @JsonProperty("Body Text") String body) {
    }

    private static Contact contact() {
        Contact c = new Contact();
        c.id = "C-1";
        c.name = "Asha";
        c.phone = "123";
        return c;
    }

    @Test
    void optionalColumnPropertiesAreSentUnderTheirColumnNames() {
        db.worksheet("T").insert(contact()).execute();
        assertEquals(json("[\"Phone No\"]"), transport.lastOperation().path("optionalColumns"));

        db.worksheet("T").insert(new Tag("t", "red")).execute();
        assertEquals(json("[\"colour\"]"), transport.lastOperation().path("optionalColumns"));

        db.worksheet("T").update().where(eq("id", "C-1")).set(contact()).execute();
        assertEquals(json("[\"Phone No\"]"), transport.lastOperation().path("optionalColumns"));
    }

    @Test
    void ignoreUnknownColumnsMakesEveryPropertyOptional() {
        db.worksheet("T").insert(new Note("n", "hi")).execute();
        assertEquals(json("[\"id\",\"Body Text\"]"), transport.lastOperation().path("optionalColumns"));
    }

    @Test
    void optionalColumnsAreOmittedWhenNoneAreMarked() {
        db.worksheet("T").insert(Map.of("a", 1)).execute();
        assertFalse(transport.lastOperation().has("optionalColumns"));
    }

    @Test
    void explicitOptionalColumnsWorkForMaps() {
        db.worksheet("T").insert(Map.of("a", 1)).optionalColumns("a").execute();
        assertEquals(json("[\"a\"]"), transport.lastOperation().path("optionalColumns"));
        db.worksheet("T").upsert().key("id", "1").set("b", 2).optionalColumns("b").execute();
        assertEquals(json("[\"b\"]"), transport.lastOperation().path("optionalColumns"));
    }

    @Test
    void repositoryUpsertKeepsOptionalColumns() {
        new Repository<>(db, Contact.class).upsert(contact());
        assertEquals(json("[\"Phone No\"]"), transport.lastOperation().path("optionalColumns"));
    }

    @Test
    void lastUpdatedSendsBareOperationAndParsesEpochMillis() {
        FakeTransport t = FakeTransport.replying("\"spreadsheetId\":\"S\",\"lastUpdated\":1760000000000");
        GridBase client = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();
        assertEquals(Instant.ofEpochMilli(1760000000000L), client.worksheet("T").lastUpdated().fetch());
        assertEquals(json("{\"id\":\"op-1\",\"type\":\"GET_LAST_UPDATED\",\"spreadsheetId\":\"S\",\"worksheet\":\"T\"}"),
                t.lastOperation());
    }

    @Test
    void cloneSerializesWhereValuesAndLimit() {
        db.worksheet("T").cloneRows().where(eq("id", "A1")).set("id", "A2").append("note", " (copy)").limit(1).execute();
        assertEquals(json("{\"id\":\"op-1\",\"type\":\"CLONE\",\"spreadsheetId\":\"SHEET\",\"worksheet\":\"T\","
                        + "\"where\":[{\"column\":\"id\",\"operator\":\"EQUALS\",\"value\":\"A1\"}],"
                        + "\"values\":{\"id\":\"A2\",\"note\":{\"value\":\" (copy)\",\"operation\":\"APPEND\"}},"
                        + "\"limit\":1}"),
                transport.lastOperation());
    }

    @Test
    void deleteSerializesOrderByLimitAndAll() {
        db.worksheet("T").delete().where(eq("status", "OLD")).orderBy("at", Sort.Direction.DESC).limit(1).offset(2).execute();
        assertEquals(json("{\"id\":\"op-1\",\"type\":\"DELETE\",\"spreadsheetId\":\"SHEET\",\"worksheet\":\"T\","
                        + "\"where\":[{\"column\":\"status\",\"operator\":\"EQUALS\",\"value\":\"OLD\"}],"
                        + "\"orderBy\":[{\"column\":\"at\",\"direction\":\"DESC\"}],\"limit\":1,\"offset\":2}"),
                transport.lastOperation());

        db.worksheet("T").delete().all().limit(3).execute();
        assertEquals(json("{\"id\":\"op-1\",\"type\":\"DELETE\",\"spreadsheetId\":\"SHEET\",\"worksheet\":\"T\",\"limit\":3}"),
                transport.lastOperation());
    }

    @Test
    void textOperatorsUseJsString() {
        db.worksheet("T").select().where(ne("a", 5.0), contains("b", 1.50), startsWith("c", true),
                endsWith("d", 100L), contains("e", new java.math.BigDecimal("2.500")), ne("f", null)).fetch();
        var where = transport.lastOperation().path("where");
        assertEquals(json("{\"column\":\"a\",\"operator\":\"NOT_EQUALS\",\"value\":\"5\"}"), where.get(0));
        assertEquals(json("{\"column\":\"b\",\"operator\":\"CONTAINS\",\"value\":\"1.5\"}"), where.get(1));
        assertEquals(json("{\"column\":\"c\",\"operator\":\"STARTS_WITH\",\"value\":\"true\"}"), where.get(2));
        assertEquals(json("{\"column\":\"d\",\"operator\":\"ENDS_WITH\",\"value\":\"100\"}"), where.get(3));
        assertEquals("2.5", where.get(4).path("value").asText());
        assertEquals(json("{\"column\":\"f\",\"operator\":\"IS_NOT_NULL\"}"), where.get(5));
    }

    @Test
    void betweenWithNumbersAndJavaTime() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 9, 30, 9, 0);
        java.time.ZonedDateTime to = java.time.ZonedDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneId.of("UTC"));
        java.time.OffsetDateTime at = java.time.OffsetDateTime.parse("2026-09-30T10:00:00+02:00");
        db.worksheet("T").select().where(between("qty", 1, 2.5), between("at", from, to), gt("ts", at),
                in("mixed", 1, "a", true, 2.5)).fetch();

        var where = transport.lastOperation().path("where");
        assertEquals(json("{\"column\":\"qty\",\"operator\":\"BETWEEN\",\"minimum\":1,\"maximum\":2.5}"), where.get(0));
        assertEquals(from.atZone(ist).toInstant().toEpochMilli(), where.get(1).path("minimum").asLong());
        assertEquals(to.toInstant().toEpochMilli(), where.get(1).path("maximum").asLong());
        assertEquals(at.toInstant().toEpochMilli(), where.get(2).path("value").asLong());
        assertEquals(json("{\"column\":\"mixed\",\"operator\":\"IN\",\"values\":[1,\"a\",true,2.5]}"), where.get(3));
    }

    @Test
    void schemaOperationsAreBare() {
        db.worksheet("New").create().execute();
        assertEquals(json("{\"id\":\"op-1\",\"type\":\"CREATE_WORKSHEET\",\"spreadsheetId\":\"SHEET\",\"worksheet\":\"New\"}"),
                transport.lastOperation());
        db.worksheet("T").clear().execute();
        assertEquals(json("{\"id\":\"op-1\",\"type\":\"CLEAR_WORKSHEET\",\"spreadsheetId\":\"SHEET\",\"worksheet\":\"T\"}"),
                transport.lastOperation());
        db.worksheet("T").columns().execute();
        assertEquals(json("{\"id\":\"op-1\",\"type\":\"GET_COLUMNS\",\"spreadsheetId\":\"SHEET\",\"worksheet\":\"T\"}"),
                transport.lastOperation());
    }

    @Test
    void multipleOrderByKeys() {
        db.worksheet("T").select().orderBy("dept").orderBy("salary", Sort.Direction.DESC).orderBy(Sort.asc("id")).fetch();
        assertEquals(json("[{\"column\":\"dept\",\"direction\":\"ASC\"},{\"column\":\"salary\",\"direction\":\"DESC\"},"
                        + "{\"column\":\"id\",\"direction\":\"ASC\"}]"),
                transport.lastOperation().path("orderBy"));
    }

    @Test
    void insertAllPerRowOptionalColumnsAndJsonPropertyNames() {
        db.worksheet("T").insertAll(List.of(contact(), new Tag("t1", "red"), Map.of("id", "m1"))).execute();
        var op = transport.lastOperation();
        assertEquals(json("[{\"id\":\"C-1\",\"name\":\"Asha\",\"Phone No\":\"123\"},"
                        + "{\"id\":\"t1\",\"colour\":\"red\"},{\"id\":\"m1\"}]"),
                op.path("rows"));
        assertEquals(json("[\"Phone No\",\"colour\"]"), op.path("optionalColumns"));
    }

    @Test
    void invalidSpecsAreRejected() {
        var ws = db.worksheet("T");
        assertThrows(IllegalArgumentException.class, () -> ws.update().set("a", 1).set("a", 2));
        assertThrows(IllegalArgumentException.class, () -> ws.upsert().key("id", 1).set("id", 2));
        assertThrows(IllegalArgumentException.class, () -> ws.update().set("", 1));
        assertThrows(IllegalArgumentException.class, () -> ws.select().limit(-1));
        assertThrows(IllegalArgumentException.class, () -> ws.select().offset(-1));
        assertThrows(IllegalArgumentException.class, () -> db.worksheet(" ", "T"));
        assertThrows(IllegalArgumentException.class, () -> db.worksheet(" "));
        assertThrows(IllegalArgumentException.class, () -> ws.select().where(gt("a", Map.of("k", 1))).fetch());
        assertThrows(IllegalArgumentException.class, () -> ws.select().where(between("a", List.of(1), 2)).fetch());
        assertEquals(0, transport.requests.size());

        // A boolean is passed through to the engine's relational operators, not rejected.
        ws.select().where(gt("flag", false)).fetch();
        assertEquals(json("{\"column\":\"flag\",\"operator\":\"GREATER_THAN\",\"value\":false}"),
                transport.lastOperation().path("where").get(0));
    }
}
