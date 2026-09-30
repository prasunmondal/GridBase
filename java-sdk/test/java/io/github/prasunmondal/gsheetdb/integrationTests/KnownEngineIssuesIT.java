package io.github.prasunmondal.gsheetdb.integrationTests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.prasunmondal.gsheetdb.Batch;
import io.github.prasunmondal.gsheetdb.Ref;
import io.github.prasunmondal.gsheetdb.Worksheet;
import io.github.prasunmondal.gsheetdb.exception.ServerException;
import io.github.prasunmondal.gsheetdb.result.RowsResult;
import io.github.prasunmondal.gsheetdb.transport.HttpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.prasunmondal.gsheetdb.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.gsheetdb.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.gsheetdb.query.Filters.between;
import static io.github.prasunmondal.gsheetdb.query.Filters.eq;
import static io.github.prasunmondal.gsheetdb.query.Filters.in;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down known engine (Apps Script) bugs so their current behaviour is visible.
 *
 * <p>Each test asserts the <b>buggy</b> behaviour. When you fix the engine, the matching test here
 * will start failing — that is the signal to flip its assertion to the correct behaviour
 * (written in each test's "After the fix" comment) and move it into the regular suite.</p>
 */
class KnownEngineIssuesIT {

    private Worksheet employees;

    @BeforeEach
    void reset() {
        TestData.resetAll();
        employees = TestData.employeesSheet();
    }

    @Test
    @DisplayName("BUG 1: IN is rejected on SELECT by RequestValidator")
    void inRejectedOnSelect() {
        ServerException e = assertThrows(ServerException.class,
                () -> employees.select().where(in("EmployeeId", "E001", "E002")).fetch());
        assertTrue(e.getServerMessage().contains("Unsupported predicate"));
        // After the fix: assertEquals(2, employees.select().where(in("EmployeeId", "E001", "E002")).fetch().size());
    }

    @Test
    @DisplayName("BUG 1: BETWEEN is rejected on SELECT by RequestValidator")
    void betweenRejectedOnSelect() {
        ServerException e = assertThrows(ServerException.class,
                () -> employees.select().where(between("Age", 22, 26)).fetch());
        assertTrue(e.getServerMessage().contains("Unsupported predicate"));
        // After the fix: 3 rows (E003, E008, E010)
    }

    @Test
    @DisplayName("BUG 2: raw EQUALS with a JSON number never matches (the SDK sends text to avoid it)")
    void rawNumericEqualsNeverMatches() throws Exception {
        ObjectMapper json = new ObjectMapper();
        HttpTransport raw = HttpTransport.builder(ItConfig.ENDPOINT).build();
        String body = "{\"requestId\":\"raw\",\"operations\":[{\"id\":\"1\",\"type\":\"SELECT\","
                + "\"spreadsheetId\":\"" + ItConfig.SPREADSHEET_ID + "\",\"worksheet\":\"" + TestData.EMPLOYEES + "\","
                + "\"where\":[{\"column\":\"Age\",\"operator\":\"EQUALS\",\"value\":25}]}]}";
        JsonNode reply = json.readTree(raw.send(body));

        assertTrue(reply.path("success").asBoolean());
        assertEquals(0, reply.path("results").get(0).path("rowCount").asInt());
        // After the fix: 1. The SDK path already returns 1:
        assertEquals(1, employees.select().where(eq("Age", 25)).fetch().size());
    }

    @Test
    @DisplayName("BUG 3a: a row deleted earlier in a batch is still returned by a later SELECT")
    void deletedRowStillVisibleInBatch() {
        Batch batch = ItConfig.db().batch();
        batch.add(employees.delete().where(eq("EmployeeId", "E005")));
        Ref<RowsResult> after = batch.add(employees.select().where(eq("EmployeeId", "E005")));
        batch.execute();

        assertEquals(1, after.get().count());                       // After the fix: 0
        assertEquals(EMPLOYEE_COUNT - 1, employeeCount());          // the delete itself was committed
    }

    @Test
    @DisplayName("BUG 3b (data loss): deleting the same row twice in one batch also deletes the NEXT row")
    void doubleDeleteRemovesNeighbour() {
        Batch batch = ItConfig.db().batch();
        batch.add(employees.delete().where(eq("EmployeeId", "E005")));
        batch.add(employees.delete().where(eq("EmployeeId", "E005")));
        batch.execute();

        // E005 sits on sheet row 6; deleteRow(6) runs twice, so E006 (shifted up into row 6) is lost.
        assertEquals(EMPLOYEE_COUNT - 2, employeeCount());          // After the fix: EMPLOYEE_COUNT - 1
        assertFalse(employees.select().where(eq("EmployeeId", "E006")).fetchFirst().isPresent());
        // After the fix: E006 is still present.
    }

    @Test
    @DisplayName("NOT A BUG: plain UPSERT does not copy filter values into the inserted row (use key())")
    void upsertWithoutKeyLeavesLookupColumnBlank() {
        employees.upsert().where(eq("EmployeeId", "E011")).set("Name", "Keyless").execute();
        var row = employees.select().where(eq("Name", "Keyless")).fetchFirst().orElseThrow();
        assertTrue(row.isBlank("EmployeeId"));
    }
}
