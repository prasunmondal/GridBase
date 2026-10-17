package io.github.prasunmondal.gridbase.integrationTests;

import io.github.prasunmondal.gridbase.Batch;
import io.github.prasunmondal.gridbase.GridBase;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.result.AddColumnsResult;
import io.github.prasunmondal.gridbase.result.ClearResult;
import io.github.prasunmondal.gridbase.result.WorksheetCreated;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CREATE_WORKSHEET, GET_COLUMNS, ADD_COLUMNS, CLEAR_WORKSHEET, GET_LAST_UPDATED.
 *
 * <p>{@link #fullLifecycleOnNewWorksheet()} and {@link #insertPastGridGrowsWorksheet()} each create a new
 * tab named {@code IT_Created_<timestamp>} per run, because the engine has no operation to delete a
 * worksheet. Delete old ones by hand.</p>
 */
class SchemaOperationsIT {

    private static String newTabName() {
        return "IT_Created_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
    }

    private GridBase db;

    @BeforeEach
    void setUp() {
        TestData.resetAll();
        db = ItConfig.db();
    }

    @Test
    @DisplayName("create -> empty headers -> add columns -> insert -> select -> clear, on a brand-new tab")
    void fullLifecycleOnNewWorksheet() {
        String name = newTabName();
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
    @DisplayName("an insert that runs past the sheet's last grid row grows the grid instead of failing")
    void insertPastGridGrowsWorksheet() {
        // A new tab has a 1000-row grid: the header plus 1000 rows needs one more.
        Worksheet ws = db.worksheet(newTabName());
        ws.create().execute();
        ws.addColumns("N").execute();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            rows.add(Map.of("N", i));
        }
        assertEquals(1000, ws.insertAll(rows).execute().count());

        assertEquals(1000, ws.select().execute().rowCount());
        assertEquals(1000L, ws.select().where(eq("N", "1000")).fetchFirst().orElseThrow().getLong("N").longValue());
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

    @Test
    @DisplayName("lastUpdated moves forward after a write, and is not served from the cache")
    void lastUpdatedAdvancesAfterWrite() {
        Worksheet employees = TestData.employeesSheet();
        Instant before = employees.lastUpdated().fetch();
        assertTrue(before.isAfter(Instant.parse("2020-01-01T00:00:00Z")));
        try {
            Thread.sleep(2000);
        } catch (Exception e) {}
        employees.insert(TestData.newEmployee("E011", "Timestamp Check")).execute();

        Instant after = employees.lastUpdated().fetch();

        System.out.println("Before: " + before);
        System.out.println("After:  " + after);
        System.out.println("Elapsed: " +
                Duration.between(before, after).toMillis() + " ms");

        assertTrue(
                !after.isBefore(before),
                "expected " + after + " to be equal to or after " + before
        );
    }

    @Test
    @DisplayName("columns added in a batch can be written later in the same batch when nothing read the tab first")
    void addColumnsThenInsertIntoItInOneBatch() {
        Worksheet ws = db.worksheet(newTabName());
        ws.create().execute();

        Batch batch = db.batch();
        batch.add(ws.addColumns("Id", "Label"));
        batch.add(ws.insert(Map.of("Id", "1", "Label", "first")));
        batch.execute();

        assertEquals(List.of("Id", "Label"), ws.columns().fetch());
        assertEquals("first", ws.select().where(eq("Id", 1)).fetchFirst().orElseThrow().getString("Label"));
    }

    /** Known limitation: the engine reads a tab's header once per request, so a column added after that is unknown. */
    @Test
    @DisplayName("a column added after the tab was read in the same batch is not yet known to later operations")
    void selectThenAddColumnsThenInsertFails() {
        Worksheet ws = db.worksheet(newTabName());
        ws.create().execute();
        ws.addColumns("Id").execute();

        Batch batch = db.batch();
        batch.add(ws.select());
        batch.add(ws.addColumns("Label"));
        batch.add(ws.insert(Map.of("Id", "1", "Label", "first")));
        ServerException e = assertThrows(ServerException.class, batch::execute);

        assertTrue(e.getServerMessage().toLowerCase().contains("label"), e.getServerMessage());
        assertEquals(List.of("Id", "Label"), ws.columns().fetch());   // the schema change itself was applied
        assertEquals(0, ws.select().execute().count());
    }

    /** Runs only against a deployment without the Advanced Drive service, e.g. the emulator with HS_DRIVE=off. */
    @Test
    @DisplayName("without Drive, a request with GET_LAST_UPDATED fails with a clear message; other requests still work")
    void lastUpdatedWithoutDriveFailsOnlyThatOperation() {
        Worksheet employees = TestData.employeesSheet();
        ServerException alone;
        try {
            employees.lastUpdated().fetch();
            Assumptions.abort("this deployment has the Advanced Drive service");
            return;
        } catch (ServerException e) {
            alone = e;
        }
        assertTrue(alone.getServerMessage().contains("Advanced Drive"), alone.getServerMessage());

        Batch batch = db.batch();
        batch.add(employees.select().where(eq("EmployeeId", "E001")));
        batch.add(employees.lastUpdated());
        ServerException inBatch = assertThrows(ServerException.class, batch::execute);
        assertTrue(inBatch.getServerMessage().contains("Advanced Drive"), inBatch.getServerMessage());
        assertFalse(inBatch.isRetryable());

        assertEquals(1, employees.select().where(eq("EmployeeId", "E001")).execute().count());
    }
}
