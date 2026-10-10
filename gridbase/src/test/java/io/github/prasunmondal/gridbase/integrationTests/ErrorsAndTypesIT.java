package io.github.prasunmondal.gridbase.integrationTests;

import io.github.prasunmondal.gridbase.GridBase;
import io.github.prasunmondal.gridbase.RetryPolicy;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.result.Row;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static io.github.prasunmondal.gridbase.query.Filters.gt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Error reporting and typed value access against the live engine. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorsAndTypesIT {

    @BeforeAll
    void seed() {
        TestData.resetAll();
    }

    // ------------------------------------------------------------------ errors

    @Test
    @DisplayName("unknown worksheet -> ServerException 'Worksheet not found'")
    void unknownWorksheet() {
        ServerException e = assertThrows(ServerException.class,
                () -> ItConfig.db().worksheet("IT_NoSuchSheet").select().fetch());
        assertTrue(e.getServerMessage().contains("Worksheet not found: IT_NoSuchSheet"));
        assertFalse(e.isRetryable());
        assertFalse(e.getServerStackTrace().isEmpty());
    }

    @Test
    @DisplayName("unknown column in where / orderBy / select -> 'Unknown column'")
    void unknownColumn() {
        var ws = TestData.employeesSheet();
        assertTrue(assertThrows(ServerException.class, () -> ws.select().where(eq("Salry", 1)).fetch())
                .getServerMessage().contains("Unknown column: Salry"));
        assertTrue(assertThrows(ServerException.class, () -> ws.select().orderBy("Nme").fetch())
                .getServerMessage().contains("Unknown column: Nme"));
        assertTrue(assertThrows(ServerException.class, () -> ws.select("Email", "Phone").fetch())
                .getServerMessage().contains("Unknown column: Phone"));
    }

    @Test
    @DisplayName("column names are case-sensitive in queries")
    void columnNamesCaseSensitive() {
        assertThrows(ServerException.class, () -> TestData.employeesSheet().select("name").fetch());
    }

    @Test
    @DisplayName("unknown spreadsheet id -> ServerException")
    void unknownSpreadsheet() {
        assertThrows(ServerException.class,
                () -> ItConfig.db().worksheet("1NoSuchSpreadsheetIdXXXXXXXXXXXXXXXXXXXXXXXX", "Sheet1").select().fetch());
    }

    @Test
    @DisplayName("wrong endpoint -> TransportException, not a JSON parse error")
    void wrongEndpoint() {
        String bad = ItConfig.ENDPOINT.replaceAll("/macros/s/[^/]+/exec", "/macros/s/NO_SUCH_DEPLOYMENT_ID/exec");
        GridBase broken = GridBase.builder()
                .endpoint(bad)
                .defaultSpreadsheetId(ItConfig.SPREADSHEET_ID)
                .retryPolicy(RetryPolicy.none())
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        assertThrows(TransportException.class, () -> broken.worksheet(TestData.EMPLOYEES).select().fetch());
    }

    @Test
    @DisplayName("default spreadsheet missing -> clear client-side error")
    void noDefaultSpreadsheet() {
        GridBase noDefault = GridBase.builder().endpoint(ItConfig.ENDPOINT).build();
        assertThrows(IllegalStateException.class, () -> noDefault.worksheet(TestData.EMPLOYEES));
    }

    // ------------------------------------------------------------------ typed access

    @Test
    @DisplayName("typed getters on a fully populated row")
    void typedGetters() {
        Row r = TestData.employee("E004");
        assertEquals("Sneha Kapoor", r.getString("Name"));
        assertEquals(120000, r.getInteger("Salary").intValue());
        assertEquals(120000L, r.getLong("Salary").longValue());
        assertEquals(120000.0, r.getDouble("Salary"));
        assertEquals(new java.math.BigDecimal("4.8"), r.getBigDecimal("Rating"));
        assertEquals(Boolean.TRUE, r.getBoolean("IsActive"));
        assertEquals(LocalDate.of(2017, 11, 20), r.getLocalDate("JoiningDate"));
        assertEquals(LocalDate.of(2017, 11, 20).atStartOfDay(ItConfig.ZONE).toInstant(), r.getInstant("JoiningDate"));
    }

    @Test
    @DisplayName("typed getters on blank cells return null")
    void blankGetters() {
        Row r = TestData.employee("E008");
        assertTrue(r.isBlank("Rating"));
        assertNull(r.getBigDecimal("Rating"));
        assertNull(r.getDouble("Rating"));
        assertEquals("", r.getString("Rating"));
    }

    @Test
    @DisplayName("reading a text column as a number fails with the column name")
    void wrongTypeGetter() {
        Row r = TestData.employee("E001");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> r.getLong("Name"));
        assertTrue(e.getMessage().contains("Name"));
    }

    @Test
    @DisplayName("row.as(Map-compatible POJO) and asMap()")
    void mapping() {
        Row r = TestData.employee("E006");
        Employee e = r.as(Employee.class);
        assertEquals("Ananya Ghosh", e.name);
        Map<String, Object> raw = r.asMap();
        assertEquals("E006", raw.get("EmployeeId"));
        assertThrows(UnsupportedOperationException.class, () -> raw.put("x", 1));
    }

    @Test
    @DisplayName("numbers passed as numbers match; numbers formatted as text do not")
    void numberVsFormattedText() {
        var ws = TestData.employeesSheet();
        assertEquals(1, ws.select().where(eq("Salary", 150000)).execute().rowCount());
        assertEquals(1, ws.select().where(eq("Salary", "150000")).execute().rowCount());
        assertEquals(0, ws.select().where(eq("Salary", "1,50,000")).execute().rowCount());
        assertEquals(0, ws.select().where(eq("Salary", "150000.00")).execute().rowCount());
        assertEquals(4, ws.select().where(gt("Salary", "100000")).execute().rowCount());   // Number("100000")
    }
}
