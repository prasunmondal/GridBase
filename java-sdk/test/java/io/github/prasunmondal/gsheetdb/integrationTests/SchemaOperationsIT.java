package io.github.prasunmondal.gsheetdb.integrationTests;

import io.github.prasunmondal.gsheetdb.SheetDB;
import io.github.prasunmondal.gsheetdb.Worksheet;
import io.github.prasunmondal.gsheetdb.exception.ServerException;
import io.github.prasunmondal.gsheetdb.result.AddColumnsResult;
import io.github.prasunmondal.gsheetdb.result.ClearResult;
import io.github.prasunmondal.gsheetdb.result.WorksheetCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static io.github.prasunmondal.gsheetdb.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CREATE_WORKSHEET, GET_COLUMNS, ADD_COLUMNS, CLEAR_WORKSHEET.
 *
 * <p>{@link #fullLifecycleOnNewWorksheet()} creates one new tab named {@code IT_Created_<timestamp>} per
 * run, because the engine has no operation to delete a worksheet. Delete old ones by hand.</p>
 */
class SchemaOperationsIT {

    private SheetDB db;

    @BeforeEach
    void setUp() {
        TestData.resetAll();
        db = ItConfig.db();
    }

    @Test
    @DisplayName("create -> empty headers -> add columns -> insert -> select -> clear, on a brand-new tab")
    void fullLifecycleOnNewWorksheet() {
        String name = "IT_Created_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        Worksheet ws = db.worksheet(name);

        WorksheetCreated created = ws.create().execute();
        assertEquals(name, created.worksheet());
        assertTrue(created.sheetId() >= 0);

        assertEquals(List.of(), ws.columns().fetch());

        AddColumnsResult added = ws.addColumns("Sku", "Title", "Price").execute();
        assertEquals(List.of("Sku", "Title", "Price"), added.columns());
        assertEquals(1, added.startColumn());
        assertEquals(3, added.count());
        assertEquals(List.of("Sku", "Title", "Price"), ws.columns().fetch());

        ws.insertAll(List.of(
                Map.of("Sku", "P-1", "Title", "Tea", "Price", 120),
                Map.of("Sku", "P-2", "Title", "Coffee", "Price", 250))).execute();
        assertEquals(2, ws.select().execute().rowCount());
        assertEquals(250L, ws.select().where(eq("Sku", "P-2")).fetchFirst().orElseThrow().getLong("Price").longValue());

        ClearResult cleared = ws.clear().execute();
        assertEquals(2, cleared.rowsCleared());
        assertEquals(3, cleared.columnsCleared());
        assertEquals(0, ws.select().execute().rowCount());
        assertEquals(List.of("Sku", "Title", "Price"), ws.columns().fetch());
    }

    @Test
    @DisplayName("create fails if the worksheet already exists")
    void createExistingFails() {
        ServerException e = assertThrows(ServerException.class, () -> TestData.employeesSheet().create().execute());
        assertTrue(e.getServerMessage().contains("Worksheet already exists"));
    }

    @Test
    @DisplayName("getColumns returns the header row in sheet order")
    void getColumns() {
        assertEquals(TestData.EMPLOYEE_COLUMNS, TestData.employeesSheet().columns().fetch());
        assertEquals(TestData.DEPARTMENT_COLUMNS, TestData.departmentsSheet().columns().fetch());
    }

    @Test
    @DisplayName("addColumns fails on an existing column unless skipExisting()")
    void addExistingColumn() {
        Worksheet probe = db.worksheet(TestData.SCHEMA_PROBE);
        ServerException e = assertThrows(ServerException.class, () -> probe.addColumns("Id").execute());
        assertTrue(e.getServerMessage().contains("Column already exists"));

        AddColumnsResult skipped = probe.addColumns("Id", "Label").skipExisting().execute();
        assertEquals(0, skipped.count());
        assertEquals(List.of("Id", "Label"), skipped.skippedColumns());
    }

    @Test
    @DisplayName("existing-column check is case-insensitive")
    void addColumnCaseInsensitive() {
        Worksheet probe = db.worksheet(TestData.SCHEMA_PROBE);
        assertThrows(ServerException.class, () -> probe.addColumns("ID").execute());
        assertThrows(ServerException.class, () -> probe.addColumns("label").execute());
    }

    @Test
    @DisplayName("duplicate names in one request fail")
    void duplicateInRequest() {
        Worksheet probe = db.worksheet(TestData.SCHEMA_PROBE);
        ServerException e = assertThrows(ServerException.class,
                () -> probe.addColumns("Tmp_Dup", "tmp_dup").execute());
        assertTrue(e.getServerMessage().contains("Duplicate column in request"));
        assertTrue(!probe.columns().fetch().contains("Tmp_Dup"));
    }

    @Test
    @DisplayName("blank column names are rejected client-side")
    void blankColumnRejected() {
        Worksheet probe = db.worksheet(TestData.SCHEMA_PROBE);
        assertThrows(IllegalArgumentException.class, () -> probe.addColumns(" ").execute());
        assertThrows(IllegalStateException.class, () -> probe.addColumns().execute());
    }

    @Test
    @DisplayName("clear keeps the header and removes all data rows")
    void clearKeepsHeader() {
        ClearResult result = TestData.employeesSheet().clear().execute();
        assertEquals(TestData.EMPLOYEE_COUNT, result.rowsCleared());
        assertEquals(TestData.EMPLOYEE_COLUMNS.size(), result.columnsCleared());
        assertEquals(0, TestData.employeeCount());
        assertEquals(TestData.EMPLOYEE_COLUMNS, TestData.employeesSheet().columns().fetch());
    }

    @Test
    @DisplayName("clear on an already-empty sheet reports 0 rows")
    void clearEmpty() {
        TestData.employeesSheet().clear().execute();
        assertEquals(0, TestData.employeesSheet().clear().execute().rowsCleared());
    }

    @Test
    @DisplayName("schema operations on a missing worksheet fail with 'Worksheet not found'")
    void missingWorksheet() {
        Worksheet missing = db.worksheet("IT_DoesNotExist");
        for (Runnable op : List.<Runnable>of(
                () -> missing.columns().execute(),
                () -> missing.addColumns("A").execute(),
                () -> missing.clear().execute())) {
            ServerException e = assertThrows(ServerException.class, op::run);
            assertTrue(e.getServerMessage().contains("Worksheet not found"));
        }
    }
}
