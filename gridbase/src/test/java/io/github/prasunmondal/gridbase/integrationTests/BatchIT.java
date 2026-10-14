package io.github.prasunmondal.gridbase.integrationTests;

import io.github.prasunmondal.gridbase.Batch;
import io.github.prasunmondal.gridbase.GridBase;
import io.github.prasunmondal.gridbase.Ref;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.result.RowsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.prasunmondal.gridbase.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employee;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.ids;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.newEmployee;
import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link Batch}: one request, operations in order, row changes all-or-nothing, schema changes immediate. */
class BatchIT {

    private GridBase db;
    private Worksheet employees;
    private Worksheet departments;

    @BeforeEach
    void reset() {
        TestData.resetAll();
        db = ItConfig.db();
        employees = TestData.employeesSheet();
        departments = TestData.departmentsSheet();
    }

    /** Employee ids in sheet order (no orderBy), to see where rows were written. */
    private List<String> idsInSheetOrder() {
        return ids(employees.select("EmployeeId").fetch());
    }

    private static List<String> seedIdsWithout(String... removed) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= EMPLOYEE_COUNT; i++) {
            out.add(String.format("E%03d", i));
        }
        out.removeAll(List.of(removed));
        return out;
    }

    private Row department(String code) {
        return departments.select().where(eq("DeptCode", code)).fetchFirst().orElseThrow();
    }

    @Test
    @DisplayName("a SELECT after an INSERT in the same batch sees the new row")
    void insertThenSelectSeesTheNewRow() {
        Batch batch = db.batch();
        batch.add(employees.insert(newEmployee("E011", "Nikhil Verma")));
        Ref<RowsResult> selected = batch.add(employees.select().where(eq("EmployeeId", "E011")));
        batch.execute();

        assertEquals(1, selected.get().count());
        assertEquals("Nikhil Verma", selected.get().rows().get(0).getString("Name"));
        assertEquals(EMPLOYEE_COUNT + 1, employeeCount());
    }

    @Test
    @DisplayName("a failing operation leaves every worksheet in the batch unchanged")
    void failingOperationWritesNoRowChangesOnAnyWorksheet() {
        Batch batch = db.batch();
        batch.add(employees.insert(newEmployee("E011", "Nikhil Verma")));
        batch.add(departments.update().set("Budget", 1).where(eq("DeptCode", "ENG")));
        batch.add(employees.update().set("NoSuchColumn", "x").where(eq("EmployeeId", "E001")));
        assertThrows(ServerException.class, batch::execute);

        assertEquals(EMPLOYEE_COUNT, employeeCount());
        assertEquals(seedIdsWithout(), idsInSheetOrder());
        assertEquals(5000000L, department("ENG").getLong("Budget").longValue());
    }

    @Test
    @DisplayName("create worksheet and add columns stay applied when a later operation fails")
    void schemaOperationBeforeAFailureStaysApplied() {
        Worksheet created = db.worksheet("IT_Created_" + System.currentTimeMillis());
        Batch batch = db.batch();
        batch.add(created.create());
        batch.add(created.addColumns("Id", "Label"));
        batch.add(created.insert(Map.of("Id", "1", "NoSuchColumn", "x")));
        assertThrows(ServerException.class, batch::execute);

        assertEquals(List.of("Id", "Label"), created.columns().fetch());
        assertEquals(0, created.select().execute().count());
    }

    @Test
    @DisplayName("delete then insert: remaining rows keep their order and the new row is last")
    void deleteThenInsertAppendsAfterTheRemainingRows() {
        Batch batch = db.batch();
        batch.add(employees.delete().where(eq("EmployeeId", "E003")));
        batch.add(employees.insert(newEmployee("E011", "Nikhil Verma")));
        batch.execute();

        List<String> expected = seedIdsWithout("E003");
        expected.add("E011");
        assertEquals(expected, idsInSheetOrder());
        assertEquals("Sneha Kapoor", employee("E004").getString("Name"));
    }

    @Test
    @DisplayName("insert then delete of the same row writes nothing")
    void insertThenDeleteSameRowWritesNothing() {
        Batch batch = db.batch();
        batch.add(employees.insert(newEmployee("E011", "Nikhil Verma")));
        Ref<RowsResult> deleted = batch.add(employees.delete().where(eq("EmployeeId", "E011")));
        batch.execute();

        assertEquals(1, deleted.get().count());
        assertEquals(seedIdsWithout(), idsInSheetOrder());
    }

    @Test
    @DisplayName("update then delete of the same row removes it and keeps its neighbours")
    void updateThenDeleteSameRowKeepsNeighbours() {
        Batch batch = db.batch();
        batch.add(employees.update().set("City", "Goa").where(eq("EmployeeId", "E005")));
        batch.add(employees.delete().where(eq("EmployeeId", "E005")));
        batch.execute();

        assertEquals(seedIdsWithout("E005"), idsInSheetOrder());
        assertEquals("Mumbai", employee("E004").getString("City"));
        assertEquals("Kolkata", employee("E006").getString("City"));
        assertEquals("Ananya Ghosh", employee("E006").getString("Name"));
    }

    @Test
    @DisplayName("insert then update of the new row writes the final values once")
    void insertThenUpdateItWritesFinalValues() {
        Batch batch = db.batch();
        batch.add(employees.insert(newEmployee("E011", "Nikhil Verma")));
        batch.add(employees.update().set("City", "Goa").append("Notes", "moved").where(eq("EmployeeId", "E011")));
        batch.execute();

        assertEquals(1, employees.select().where(eq("EmployeeId", "E011")).execute().count());
        Row row = employee("E011");
        assertEquals("Goa", row.getString("City"));
        assertEquals("moved", row.getString("Notes"));
        assertEquals("Nikhil Verma", row.getString("Name"));
        assertEquals(EMPLOYEE_COUNT + 1, employeeCount());
    }

    @Test
    @DisplayName("updates of adjacent rows on different columns both apply; other cells keep their values")
    void updatesOfAdjacentRowsOnDifferentColumnsBothApply() {
        Batch batch = db.batch();
        batch.add(employees.update().set("City", "Goa").where(eq("EmployeeId", "E002")));
        batch.add(employees.update().set("Age", 50).where(eq("EmployeeId", "E003")));
        batch.execute();

        Row e002 = employee("E002");
        Row e003 = employee("E003");
        assertEquals("Goa", e002.getString("City"));
        assertEquals(27, e002.getInteger("Age").intValue());
        assertEquals(50, e003.getInteger("Age").intValue());
        assertEquals("Bhubaneswar", e003.getString("City"));
        assertEquals("Rohan Das", e003.getString("Name"));
        assertEquals("E001", e003.getString("ManagerId"));
    }

    @Test
    @DisplayName("a SELECT after an UPDATE in the same batch sees the new value")
    void updateThenSelect() {
        Batch batch = db.batch();
        batch.add(employees.update().set("City", "Goa").where(eq("EmployeeId", "E001")));
        Ref<RowsResult> inGoa = batch.add(employees.select().where(eq("City", "Goa")));
        batch.execute();
        assertEquals(List.of("E001"), ids(inGoa.get().rows()));
    }

    @Test
    @DisplayName("a SELECT after an UPSERT in the same batch sees the inserted and the updated row")
    void upsertThenSelect() {
        Batch batch = db.batch();
        batch.add(employees.upsert().key("EmployeeId", "E011").set("Name", "Nikhil Verma").set("City", "Goa"));
        batch.add(employees.upsert().key("EmployeeId", "E002").set("City", "Goa"));
        Ref<RowsResult> inGoa = batch.add(employees.select().where(eq("City", "Goa")).orderBy("EmployeeId"));
        batch.execute();
        assertEquals(List.of("E002", "E011"), ids(inGoa.get().rows()));
    }

    @Test
    @DisplayName("a SELECT after a CLONE in the same batch sees the copy")
    void cloneThenSelect() {
        Batch batch = db.batch();
        batch.add(employees.cloneRows().where(eq("EmployeeId", "E001")).set("EmployeeId", "E011"));
        Ref<RowsResult> copy = batch.add(employees.select().where(eq("EmployeeId", "E011")));
        batch.execute();
        assertEquals("Aarav Sharma", copy.get().rows().get(0).getString("Name"));
        assertEquals(EMPLOYEE_COUNT + 1, employeeCount());
    }

    @Test
    @DisplayName("one batch across two worksheets commits both")
    void oneBatchAcrossTwoWorksheetsCommitsBoth() {
        Batch batch = db.batch();
        batch.add(employees.update().set("City", "Goa").where(eq("EmployeeId", "E001")));
        batch.add(departments.update().set("Location", "Goa").where(eq("DeptCode", "ENG")));
        batch.execute();

        assertEquals("Goa", employee("E001").getString("City"));
        assertEquals("Goa", department("ENG").getString("Location"));
        assertEquals("Mumbai", department("SAL").getString("Location"));
    }
}
