package io.github.prasunmondal.gsheetdb.integrationTests;

import io.github.prasunmondal.gsheetdb.Worksheet;
import io.github.prasunmondal.gsheetdb.result.Row;
import io.github.prasunmondal.gsheetdb.result.RowsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.prasunmondal.gsheetdb.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.gsheetdb.integrationTests.TestData.allEmployees;
import static io.github.prasunmondal.gsheetdb.integrationTests.TestData.employee;
import static io.github.prasunmondal.gsheetdb.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.gsheetdb.integrationTests.TestData.ids;
import static io.github.prasunmondal.gsheetdb.integrationTests.TestData.newEmployee;
import static io.github.prasunmondal.gsheetdb.query.Filters.between;
import static io.github.prasunmondal.gsheetdb.query.Filters.eq;
import static io.github.prasunmondal.gsheetdb.query.Filters.in;
import static io.github.prasunmondal.gsheetdb.query.Filters.isNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DELETE removes sheet rows; the remaining rows must be intact and correctly aligned. */
class DeleteIT {

    private Worksheet employees;

    @BeforeEach
    void reset() {
        TestData.resetAll();
        employees = TestData.employeesSheet();
    }

    @Test
    @DisplayName("delete one row by key; neighbours keep their own data")
    void deleteOne() {
        RowsResult result = employees.delete().where(eq("EmployeeId", "E005")).execute();

        assertEquals(1, result.count());
        assertEquals("E005", result.rows().get(0).getString("EmployeeId"));
        assertEquals(EMPLOYEE_COUNT - 1, employeeCount());
        assertEquals(List.of("E001", "E002", "E003", "E004", "E006", "E007", "E008", "E009", "E010"),
                ids(allEmployees()));
        // the row that moved up into E005's position still has E006's data
        assertEquals("Ananya Ghosh", employee("E006").getString("Name"));
        assertEquals("Sneha Kapoor", employee("E004").getString("Name"));
    }

    @Test
    @DisplayName("delete several non-adjacent rows in one operation")
    void deleteNonAdjacent() {
        // Hyderabad: E002 and E010
        assertEquals(2, employees.delete().where(eq("City", "Hyderabad")).execute().count());
        assertEquals(List.of("E001", "E003", "E004", "E005", "E006", "E007", "E008", "E009"), ids(allEmployees()));
        assertEquals("Rohan Das", employee("E003").getString("Name"));
        assertEquals("Arjun Mehta", employee("E009").getString("Name"));
    }

    @Test
    @DisplayName("delete adjacent rows")
    void deleteAdjacent() {
        // E001, E002, E003 are rows 2..4
        assertEquals(3, employees.delete().where(in("EmployeeId", "E001", "E002", "E003")).execute().count());
        assertEquals("E004", allEmployees().get(0).getString("EmployeeId"));
        assertEquals(EMPLOYEE_COUNT - 3, employeeCount());
    }

    @Test
    @DisplayName("delete the last row of the sheet")
    void deleteLastRow() {
        employees.delete().where(eq("EmployeeId", "E010")).execute();
        List<Row> rows = employees.select("EmployeeId").fetch();
        assertEquals("E009", rows.get(rows.size() - 1).getString("EmployeeId"));
    }

    @Test
    @DisplayName("orderBy + limit deletes only the top N matches")
    void deleteTopN() {
        RowsResult result = employees.delete()
                .where(eq("Department", "Engineering"))
                .orderBy("Salary")          // lowest first
                .limit(1)
                .execute();
        assertEquals(List.of("E008"), ids(result.rows()));
        assertEquals(3, employees.select().where(eq("Department", "Engineering")).execute().rowCount());
    }

    @Test
    @DisplayName("BETWEEN works on DELETE")
    void deleteBetween() {
        // Age 22..26: E003 25, E008 22, E010 26
        assertEquals(3, employees.delete().where(between("Age", 22, 26)).execute().count());
        assertEquals(EMPLOYEE_COUNT - 3, employeeCount());
    }

    @Test
    @DisplayName("delete rows with a blank cell")
    void deleteWhereBlank() {
        assertEquals(1, employees.delete().where(isNull("Email")).execute().count());
        assertEquals(0, employees.select().where(eq("EmployeeId", "E005")).execute().rowCount());
    }

    @Test
    @DisplayName("no match deletes nothing")
    void noMatch() {
        assertEquals(0, employees.delete().where(eq("EmployeeId", "E999")).execute().count());
        assertEquals(EMPLOYEE_COUNT, employeeCount());
    }

    @Test
    @DisplayName("all() deletes every data row; header remains")
    void deleteAll() {
        assertEquals(EMPLOYEE_COUNT, employees.delete().all().execute().count());
        assertEquals(0, employeeCount());
        assertEquals(TestData.EMPLOYEE_COLUMNS, employees.columns().fetch());
    }

    @Test
    @DisplayName("insert after delete appends correctly")
    void insertAfterDelete() {
        employees.delete().where(eq("EmployeeId", "E003")).execute();
        employees.insert(newEmployee("E011", "After Delete")).execute();
        assertEquals(EMPLOYEE_COUNT, employeeCount());
        assertEquals("After Delete", employee("E011").getString("Name"));
        assertEquals("Meera Joshi", employee("E008").getString("Name"));
    }

    @Test
    @DisplayName("delete without where(...) is refused before anything is sent")
    void deleteWithoutWhereRefused() {
        assertThrows(IllegalStateException.class, () -> employees.delete().execute());
        assertEquals(EMPLOYEE_COUNT, employeeCount());
    }

    @Test
    @DisplayName("sheet order of remaining rows is preserved")
    void orderPreserved() {
        employees.delete().where(eq("Department", "Finance")).execute();
        List<Row> sheetOrder = employees.select("EmployeeId").fetch();
        assertEquals(List.of("E001", "E002", "E003", "E004", "E005", "E006", "E008", "E010"), ids(sheetOrder));
    }
}
