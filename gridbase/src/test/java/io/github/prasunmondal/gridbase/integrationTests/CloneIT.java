package io.github.prasunmondal.gridbase.integrationTests;

import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.result.RowsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static io.github.prasunmondal.gridbase.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employee;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.sortedIds;
import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLONE copies matching rows to the bottom of the sheet, with optional overrides. */
class CloneIT {

    private Worksheet employees;

    @BeforeEach
    void reset() {
        TestData.resetAll();
        employees = TestData.employeesSheet();
    }

    @Test
    @DisplayName("clone one row with a new key; every other column is copied")
    void cloneWithNewKey() {
        RowsResult result = employees.cloneRows()
                .where(eq("EmployeeId", "E002"))
                .set("EmployeeId", "E011")
                .set("Name", "Priya Clone")
                .execute();

        assertEquals(1, result.count());
        assertEquals(EMPLOYEE_COUNT + 1, employeeCount());
        Row clone = employee("E011");
        Row original = employee("E002");
        assertEquals("Priya Clone", clone.getString("Name"));
        for (String column : List.of("Department", "Designation", "Salary", "Age", "Email", "City",
                "IsActive", "Rating", "ManagerId", "Notes")) {
            assertEquals(original.get(column), clone.get(column));
        }
        assertEquals(LocalDate.of(2022, 7, 1), clone.getLocalDate("JoiningDate"));
    }

    @Test
    @DisplayName("the source row is not modified")
    void sourceUntouched() {
        employees.cloneRows().where(eq("EmployeeId", "E004")).set("EmployeeId", "E011").set("City", "Pune").execute();
        assertEquals("Mumbai", employee("E004").getString("City"));
        assertEquals("Pune", employee("E011").getString("City"));
    }

    @Test
    @DisplayName("clone without overrides creates an exact duplicate")
    void exactDuplicate() {
        employees.cloneRows().where(eq("EmployeeId", "E007")).execute();
        List<Row> copies = employees.select().where(eq("EmployeeId", "E007")).fetch();
        assertEquals(2, copies.size());
        assertEquals(copies.get(0), copies.get(1));
    }

    @Test
    @DisplayName("clone every matching row")
    void cloneMany() {
        RowsResult result = employees.cloneRows()
                .where(eq("Department", "HR"))
                .set("Notes", "cloned")
                .execute();
        assertEquals(2, result.count());
        assertEquals(EMPLOYEE_COUNT + 2, employeeCount());
        assertEquals(List.of("E006", "E010"), sortedIds(employees.select().where(eq("Notes", "cloned")).fetch()));
    }

    @Test
    @DisplayName("clone with append builds on the copied value")
    void cloneWithAppend() {
        employees.cloneRows().where(eq("EmployeeId", "E001"))
                .set("EmployeeId", "E011")
                .append("Notes", " (copy)")
                .execute();
        assertEquals("Team lead (copy)", employee("E011").getString("Notes"));
        assertEquals("Team lead", employee("E001").getString("Notes"));
    }

    @Test
    @DisplayName("limit bounds how many rows are cloned")
    void cloneWithLimit() {
        RowsResult result = employees.cloneRows().where(eq("Department", "Engineering"))
                .orderBy("EmployeeId").limit(1).set("Notes", "first only").execute();
        assertEquals(1, result.count());
        assertEquals(EMPLOYEE_COUNT + 1, employeeCount());
        assertEquals(List.of("E001"), sortedIds(employees.select().where(eq("Notes", "first only")).fetch()));
        assertEquals("Team lead", employee("E001").getString("Notes"));   // original (first in sheet order)
    }

    @Test
    @DisplayName("clone with no match fails and adds nothing")
    void noMatchFails() {
        ServerException e = assertThrows(ServerException.class,
                () -> employees.cloneRows().where(eq("EmployeeId", "E999")).set("EmployeeId", "E011").execute());
        assertTrue(e.getServerMessage().contains("No matching row found for CLONE"));
        assertEquals(EMPLOYEE_COUNT, employeeCount());
    }

    @Test
    @DisplayName("clone without where(...) is refused before sending")
    void requiresWhere() {
        assertThrows(IllegalStateException.class, () -> employees.cloneRows().set("EmployeeId", "X").execute());
    }
}
