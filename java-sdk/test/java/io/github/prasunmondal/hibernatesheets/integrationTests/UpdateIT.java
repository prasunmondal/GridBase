package io.github.prasunmondal.hibernatesheets.integrationTests;

import io.github.prasunmondal.hibernatesheets.Worksheet;
import io.github.prasunmondal.hibernatesheets.exception.ServerException;
import io.github.prasunmondal.hibernatesheets.query.Sort;
import io.github.prasunmondal.hibernatesheets.result.Row;
import io.github.prasunmondal.hibernatesheets.result.RowsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.employee;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.sortedIds;
import static io.github.prasunmondal.hibernatesheets.query.Filters.between;
import static io.github.prasunmondal.hibernatesheets.query.Filters.eq;
import static io.github.prasunmondal.hibernatesheets.query.Filters.gte;
import static io.github.prasunmondal.hibernatesheets.query.Filters.in;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UPDATE with SET / APPEND / PREPEND, verified by re-reading the rows. */
class UpdateIT {

    private Worksheet employees;

    @BeforeEach
    void reset() {
        TestData.resetAll();
        employees = TestData.employeesSheet();
    }

    @Test
    @DisplayName("set one column on one row; other rows and columns unchanged")
    void setSingleColumn() {
        RowsResult result = employees.update().set("Salary", 99000).where(eq("EmployeeId", "E003")).execute();

        assertEquals(1, result.count());
        assertEquals(99000L, employee("E003").getLong("Salary").longValue());
        assertEquals("Rohan Das", employee("E003").getString("Name"));
        assertEquals(95000L, employee("E002").getLong("Salary").longValue());
        assertEquals(EMPLOYEE_COUNT, employeeCount());
    }

    @Test
    @DisplayName("set several columns at once, including a date and a boolean")
    void setSeveralColumns() {
        employees.update()
                .set("Designation", "Senior Engineer")
                .set("Salary", 125000)
                .set("IsActive", false)
                .set("JoiningDate", LocalDate.of(2022, 8, 1))
                .where(eq("EmployeeId", "E002"))
                .execute();
        Row row = employee("E002");
        assertEquals("Senior Engineer", row.getString("Designation"));
        assertEquals(125000L, row.getLong("Salary").longValue());
        assertEquals(Boolean.FALSE, row.getBoolean("IsActive"));
        assertEquals(LocalDate.of(2022, 8, 1), row.getLocalDate("JoiningDate"));
    }

    @Test
    @DisplayName("set(Map) assigns every entry")
    void setFromMap() {
        employees.update().set(Map.of("City", "Pune", "Notes", "Relocated")).where(eq("EmployeeId", "E007")).execute();
        assertEquals("Pune", employee("E007").getString("City"));
        assertEquals("Relocated", employee("E007").getString("Notes"));
    }

    @Test
    @DisplayName("update every row matching a filter")
    void updateManyRows() {
        RowsResult result = employees.update().set("City", "Remote").where(eq("Department", "Sales")).execute();
        assertEquals(2, result.count());
        assertEquals(List.of("E004", "E005"), sortedIds(employees.select().where(eq("City", "Remote")).fetch()));
    }

    @Test
    @DisplayName("updated rows are returned with their new values")
    void returnsUpdatedRows() {
        RowsResult result = employees.update().set("Age", 35).where(eq("EmployeeId", "E001")).execute();
        assertEquals(35, result.rows().get(0).getInteger("Age").intValue());
    }

    @Test
    @DisplayName("append to a non-empty cell")
    void appendText() {
        employees.update().append("Notes", " | Mentor").where(eq("EmployeeId", "E001")).execute();
        assertEquals("Team lead | Mentor", employee("E001").getString("Notes"));
    }

    @Test
    @DisplayName("append to an empty cell just sets it")
    void appendToBlank() {
        employees.update().append("Notes", "Promoted").where(eq("EmployeeId", "E002")).execute();
        assertEquals("Promoted", employee("E002").getString("Notes"));
    }

    @Test
    @DisplayName("prepend text")
    void prependText() {
        employees.update().prepend("Name", "Dr. ").where(eq("EmployeeId", "E006")).execute();
        assertEquals("Dr. Ananya Ghosh", employee("E006").getString("Name"));
    }

    @Test
    @DisplayName("append applied to many rows builds on each row's own value")
    void appendAcrossRows() {
        employees.update().append("Designation", " (Acting)").where(eq("Department", "HR")).execute();
        assertEquals("HR Manager (Acting)", employee("E006").getString("Designation"));
        assertEquals("Recruiter (Acting)", employee("E010").getString("Designation"));
    }

    @Test
    @DisplayName("set, append and prepend on different columns in one update")
    void mixedOperations() {
        employees.update()
                .set("City", "Goa")
                .append("Notes", " - on leave")
                .prepend("Designation", "Ex-")
                .where(eq("EmployeeId", "E004"))
                .execute();
        Row row = employee("E004");
        assertEquals("Goa", row.getString("City"));
        assertEquals("Top performer - on leave", row.getString("Notes"));
        assertEquals("Ex-Sales Manager", row.getString("Designation"));
    }

    @Test
    @DisplayName("set(null) clears a cell")
    void setNullClears() {
        employees.update().set("Email", null).where(eq("EmployeeId", "E001")).execute();
        assertTrue(employee("E001").isBlank("Email"));
    }

    @Test
    @DisplayName("orderBy + limit restricts the update to the top N matches")
    void updateTopN() {
        RowsResult result = employees.update()
                .set("Notes", "Highest paid engineer")
                .where(eq("Department", "Engineering"))
                .orderBy("Salary", Sort.Direction.DESC)
                .limit(1)
                .execute();
        assertEquals(1, result.count());
        assertEquals("Highest paid engineer", employee("E001").getString("Notes"));
        assertEquals("", employee("E002").getString("Notes"));
    }

    @Test
    @DisplayName("filters on UPDATE: numeric range")
    void updateWithRange() {
        RowsResult result = employees.update().set("Notes", "Senior band")
                .where(gte("Salary", 120000)).execute();
        assertEquals(3, result.count());
    }

    @Test
    @DisplayName("IN works on UPDATE (the engine only validates filters for SELECT)")
    void updateWithIn() {
        RowsResult result = employees.update().set("City", "Noida").where(in("EmployeeId", "E002", "E003")).execute();
        assertEquals(2, result.count());
        assertEquals("Noida", employee("E002").getString("City"));
        assertEquals("Noida", employee("E003").getString("City"));
    }

    @Test
    @DisplayName("BETWEEN works on UPDATE")
    void updateWithBetween() {
        // 60000..95000 inclusive: E002 95k, E003 90k, E005 60k, E007 70k
        RowsResult result = employees.update().set("Notes", "Mid band")
                .where(between("Salary", 60000, 95000)).execute();
        assertEquals(4, result.count());
        assertEquals(List.of("E002", "E003", "E005", "E007"),
                sortedIds(employees.select().where(eq("Notes", "Mid band")).fetch()));
    }

    @Test
    @DisplayName("no matching rows updates nothing")
    void noMatch() {
        assertEquals(0, employees.update().set("City", "X").where(eq("EmployeeId", "E999")).execute().count());
        assertEquals(0, employees.select().where(eq("City", "X")).execute().rowCount());
    }

    @Test
    @DisplayName("all() updates every row")
    void updateAll() {
        assertEquals(EMPLOYEE_COUNT, employees.update().set("Notes", "Reviewed 2026").all().execute().count());
        assertEquals(EMPLOYEE_COUNT, employees.select().where(eq("Notes", "Reviewed 2026")).execute().rowCount());
    }

    @Test
    @DisplayName("unknown column fails and leaves the data unchanged")
    void unknownColumn() {
        assertThrows(ServerException.class,
                () -> employees.update().set("Salary", 1).set("Bonus", 5).where(eq("EmployeeId", "E001")).execute());
        assertEquals(150000L, employee("E001").getLong("Salary").longValue());
    }

    @Test
    @DisplayName("update without where(...) is refused before anything is sent")
    void updateWithoutWhereRefused() {
        assertThrows(IllegalStateException.class, () -> employees.update().set("City", "X").execute());
        assertThrows(IllegalStateException.class, () -> employees.update().where(eq("EmployeeId", "E001")).execute());
    }
}
