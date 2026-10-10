package io.github.prasunmondal.hibernatesheets.integrationTests;

import io.github.prasunmondal.hibernatesheets.Worksheet;
import io.github.prasunmondal.hibernatesheets.result.Row;
import io.github.prasunmondal.hibernatesheets.result.RowsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.employee;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.hibernatesheets.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UPSERT: update when the key exists, insert when it does not. */
class UpsertIT {

    private Worksheet employees;

    @BeforeEach
    void reset() {
        TestData.resetAll();
        employees = TestData.employeesSheet();
    }

    @Test
    @DisplayName("missing key -> inserts a new row, and key() writes the key column too")
    void insertsWhenMissing() {
        RowsResult result = employees.upsert()
                .key("EmployeeId", "E011")
                .set("Name", "Tanvi Kulkarni")
                .set("Department", "Sales")
                .set("Salary", 65000)
                .execute();

        assertEquals(1, result.count());
        assertEquals(EMPLOYEE_COUNT + 1, employeeCount());
        Row row = employee("E011");
        assertEquals("Tanvi Kulkarni", row.getString("Name"));
        assertEquals(65000L, row.getLong("Salary").longValue());
        assertTrue(row.isBlank("City"));
    }

    @Test
    @DisplayName("existing key -> updates in place, row count unchanged")
    void updatesWhenPresent() {
        employees.upsert().key("EmployeeId", "E002").set("Salary", 99000).set("Notes", "Upserted").execute();

        assertEquals(EMPLOYEE_COUNT, employeeCount());
        Row row = employee("E002");
        assertEquals(99000L, row.getLong("Salary").longValue());
        assertEquals("Upserted", row.getString("Notes"));
        assertEquals("Priya Iyer", row.getString("Name"));   // untouched column survives
    }

    @Test
    @DisplayName("running the same upsert twice is idempotent")
    void idempotent() {
        for (int i = 0; i < 2; i++) {
            employees.upsert().key("EmployeeId", "E011").set("Name", "Once Only").execute();
        }
        assertEquals(1, employees.select().where(eq("EmployeeId", "E011")).execute().rowCount());
        assertEquals(EMPLOYEE_COUNT + 1, employeeCount());
    }

    @Test
    @DisplayName("filter matching several rows -> all of them are updated")
    void updatesAllMatches() {
        RowsResult result = employees.upsert()
                .where(eq("Department", "Sales"))
                .set("IsActive", false)
                .execute();
        assertEquals(2, result.count());
        assertEquals(Boolean.FALSE, employee("E004").getBoolean("IsActive"));
        assertEquals(EMPLOYEE_COUNT, employeeCount());
    }

    @Test
    @DisplayName("composite match on two columns")
    void compositeKey() {
        employees.upsert().key("Department", "Engineering").key("City", "Hyderabad")
                .set("Notes", "Hyd engineer").execute();
        assertEquals("Hyd engineer", employee("E002").getString("Notes"));

        employees.upsert().key("Department", "Engineering").key("City", "Kochi")
                .set("EmployeeId", "E011").set("Name", "New Kochi Engineer").execute();
        Row inserted = employee("E011");
        assertEquals("Engineering", inserted.getString("Department"));
        assertEquals("Kochi", inserted.getString("City"));
    }

    @Test
    @DisplayName("append works on the update path")
    void appendOnUpdate() {
        employees.upsert().key("EmployeeId", "E001").append("Notes", " | upserted").execute();
        assertEquals("Team lead | upserted", employee("E001").getString("Notes"));
    }

    @Test
    @DisplayName("append on the insert path starts from an empty cell")
    void appendOnInsert() {
        employees.upsert().key("EmployeeId", "E011").append("Notes", "fresh").execute();
        assertEquals("fresh", employee("E011").getString("Notes"));
    }

    @Test
    @DisplayName("plain where(...) upsert does not copy filter values into the inserted row (use key())")
    void upsertWithoutKeyLeavesLookupColumnBlank() {
        employees.upsert().where(eq("EmployeeId", "E011")).set("Name", "Keyless").execute();
        Row row = employees.select().where(eq("Name", "Keyless")).fetchFirst().orElseThrow();
        assertTrue(row.isBlank("EmployeeId"));
    }

    @Test
    @DisplayName("upsert without a key/where, or with nothing to set, is refused before sending")
    void requiresKeyAndValues() {
        // no filter: the engine would treat every row as a match and update the whole sheet
        assertThrows(IllegalStateException.class, () -> employees.upsert().set("Name", "x").execute());
        assertThrows(IllegalStateException.class,
                () -> employees.upsert().where(eq("EmployeeId", "E001")).execute());
        assertEquals(EMPLOYEE_COUNT, employeeCount());
    }
}
