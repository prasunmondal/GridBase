package io.github.prasunmondal.gridbase.integrationTests;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.prasunmondal.gridbase.APIRequestsQueue;
import io.github.prasunmondal.gridbase.Queued;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.mapping.OptionalColumn;
import io.github.prasunmondal.gridbase.mapping.Repository;
import io.github.prasunmondal.gridbase.mapping.SheetKey;
import io.github.prasunmondal.gridbase.mapping.SheetTable;
import io.github.prasunmondal.gridbase.result.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static io.github.prasunmondal.gridbase.integrationTests.TestData.DEPARTMENT_COUNT;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.EMPLOYEE_COLUMNS;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employee;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employeesSheet;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.ids;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository {@code replaceAllData}: one REPLACE_ALL_DATA operation (clear and insert in one step), so the worksheet
 * ends up holding exactly the given entities (header row kept).
 */
class ReplaceAllDataIT {

    private Repository<Employee> employees;
    private Repository<Department> departments;

    @BeforeEach
    void reset() {
        TestData.resetAll();
        employees = Employee.repository();
        departments = ItConfig.db().repository(Department.class);
    }

    // ------------------------------------------------------------------ one entity

    @Test
    @DisplayName("replaceAllData() replaces every row with the one entity and returns it as written")
    void replaceAllDataReplacesAllRows() {
        Employee e = Employee.of("E201", "Sole Survivor", "Finance", 123456, LocalDate.of(2026, 3, 9));
        e.age = 37;
        e.isActive = false;
        e.rating = new BigDecimal("3.25");
        e.notes = "saved";

        Employee saved = employees.replaceAllData(e);

        assertEquals("E201", saved.employeeId);
        assertEquals(1, employeeCount());
        assertFalse(employees.existsById("E001"));

        Employee back = employees.findById("E201").orElseThrow();
        assertEquals("Sole Survivor", back.name);
        assertEquals("Finance", back.department);
        assertEquals(123456L, back.salary.longValue());
        assertEquals(37, back.age.intValue());
        assertEquals(LocalDate.of(2026, 3, 9), back.joiningDate);
        assertFalse(back.isActive);
        assertEquals(0, new BigDecimal("3.25").compareTo(back.rating));
        assertEquals("saved", back.notes);
    }

    @Test
    @DisplayName("replaceAllData() twice keeps only the second entity, even with the same key")
    void replaceAllDataTwiceKeepsLast() {
        employees.replaceAllData(Employee.of("E201", "First", "HR", 40000, LocalDate.of(2026, 1, 1)));
        employees.replaceAllData(Employee.of("E202", "Second", "HR", 41000, LocalDate.of(2026, 1, 2)));
        assertEquals(List.of("E202"), ids(employeesSheet().select().fetch()));

        employees.replaceAllData(Employee.of("E202", "Second Again", "HR", 42000, LocalDate.of(2026, 1, 3)));
        assertEquals(1, employeeCount());
        assertEquals("Second Again", employee("E202").getString("Name"));
    }

    @Test
    @DisplayName("replaceAllData() writes null fields as blank cells")
    void replaceAllDataWritesNullsAsBlanks() {
        Employee e = Employee.of("E201", "Sparse", "HR", 40000, LocalDate.of(2026, 1, 1));
        e.email = null;
        e.rating = null;
        e.managerId = null;
        employees.replaceAllData(e);

        Row row = employee("E201");
        assertTrue(row.isBlank("Email"));
        assertTrue(row.isBlank("Rating"));
        assertTrue(row.isBlank("ManagerId"));
        assertNull(employees.findById("E201").orElseThrow().rating);
    }

    // ------------------------------------------------------------------ a list

    @Test
    @DisplayName("replaceAllData() replaces every row and keeps the given order")
    void replaceAllDataReplacesInOrder() {
        List<Employee> saved = employees.replaceAllData(List.of(
                Employee.of("E203", "Third", "Sales", 53000, LocalDate.of(2026, 5, 3)),
                Employee.of("E201", "First", "Sales", 51000, LocalDate.of(2026, 5, 1)),
                Employee.of("E202", "Second", "Sales", 52000, LocalDate.of(2026, 5, 2))));

        assertEquals(List.of("E203", "E201", "E202"), saved.stream().map(e -> e.employeeId).toList());
        assertEquals(List.of("E203", "E201", "E202"), ids(employeesSheet().select().fetch()));
        assertEquals(LocalDate.of(2026, 5, 1), employees.findById("E201").orElseThrow().joiningDate);
        for (int i = 1; i <= EMPLOYEE_COUNT; i++) {
            assertFalse(employees.existsById(String.format("E%03d", i)));
        }
    }

    @Test
    @DisplayName("replaceAllData(empty) clears the sheet but keeps the header row")
    void replaceAllDataEmptyKeepsHeader() {
        assertTrue(employees.replaceAllData(List.of()).isEmpty());
        assertEquals(0, employeeCount());

        employees.insert(Employee.of("E201", "After Clear", "HR", 40000, LocalDate.of(2026, 1, 1)));
        Row row = employee("E201");
        assertEquals(EMPLOYEE_COLUMNS, List.copyOf(row.columns()));
        assertEquals("After Clear", row.getString("Name"));
    }

    @Test
    @DisplayName("replaceAllData() on an already empty sheet just inserts")
    void replaceAllDataOnEmptySheet() {
        employees.deleteAll();
        assertEquals(0, employeeCount());

        employees.replaceAllData(List.of(
                Employee.of("E201", "A", "HR", 40000, LocalDate.of(2026, 1, 1)),
                Employee.of("E202", "B", "HR", 41000, LocalDate.of(2026, 1, 2))));
        assertEquals(2, employeeCount());
    }

    @Test
    @DisplayName("replaceAllData() does not check keys: duplicates are all written, like insertAll()")
    void replaceAllDataKeepsDuplicateKeys() {
        employees.replaceAllData(List.of(
                Employee.of("E201", "One", "HR", 40000, LocalDate.of(2026, 1, 1)),
                Employee.of("E201", "Two", "HR", 41000, LocalDate.of(2026, 1, 2))));
        assertEquals(List.of("E201", "E201"), ids(employeesSheet().select().fetch()));
    }

    @Test
    @DisplayName("replaceAllData() writes a few hundred rows in one request")
    void replaceAllDataManyRows() {
        List<Employee> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            many.add(Employee.of(String.format("B%04d", i), "Bulk " + i, "Ops", 30000 + i, LocalDate.of(2026, 1, 1)));
        }
        assertEquals(300, employees.replaceAllData(many).size());
        assertEquals(300, employeeCount());
        assertEquals("Bulk 299", employee("B0299").getString("Name"));
    }

    @Test
    @DisplayName("replaceAllData() with a record entity")
    void replaceAllDataRecords() {
        assertEquals(DEPARTMENT_COUNT, departments.findAll().size());
        List<Department> saved = departments.replaceAllData(List.of(
                new Department("OPS", "Operations", 900000L, "Pune", "E007"),
                new Department("LEG", "Legal", 400000L, "Delhi", null)));

        assertEquals(2, saved.size());
        assertEquals(2, departments.findAll().size());
        assertFalse(departments.existsById("ENG"));
        Department legal = departments.findById("LEG").orElseThrow();
        assertEquals(400000L, legal.budget().longValue());
        assertTrue(legal.headEmployeeId() == null || legal.headEmployeeId().isEmpty());
    }

    // ------------------------------------------------------------------ cache and queues

    @Test
    @DisplayName("replaceAllData() invalidates the cache: a warmed findAll() sees the saved rows")
    void replaceAllDataInvalidatesCache() {
        assertEquals(EMPLOYEE_COUNT, employees.findAll().size());   // warms Employee's CACHE_FIRST cache
        employees.replaceAllData(List.of(Employee.of("E201", "Fresh", "HR", 40000, LocalDate.of(2026, 1, 1))));

        List<Employee> after = employees.findAll();
        assertEquals(1, after.size());
        assertEquals("Fresh", after.get(0).name);
    }

    @Test
    @DisplayName("APIRequestsQueue: replaceAllData() runs in order between the reads queued around it")
    void replaceAllDataInQueue() {
        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<List<Department>> before = departments.requests().findAll().queue(reqQ);
        Queued<List<Department>> saved = departments.requests()
                .replaceAllData(List.of(new Department("OPS", "Operations", 900000L, "Pune", "E007")))
                .queue(reqQ);
        Queued<List<Department>> after = departments.requests().findAll().queue(reqQ);
        assertEquals(DEPARTMENT_COUNT, departments.findAll().size());   // nothing sent yet

        reqQ.execute();

        assertEquals(DEPARTMENT_COUNT, before.get().size());
        assertEquals("OPS", saved.get().get(0).deptCode());
        assertEquals(List.of("OPS"), after.get().stream().map(Department::deptCode).toList());
    }

    // ------------------------------------------------------------------ columns

    /** IT_Employees with a property the sheet does not have. */
    @SheetTable(worksheet = TestData.EMPLOYEES)
    public static class EmployeeWithUnknownColumn {
        @SheetKey @JsonProperty("EmployeeId") public String id;
        @JsonProperty("Name") public String name;
        @JsonProperty("NoSuchColumn") public String extra;
    }

    /** As above, but the missing column is optional. */
    @SheetTable(worksheet = TestData.EMPLOYEES)
    public static class EmployeeWithOptionalColumn {
        @SheetKey @JsonProperty("EmployeeId") public String id;
        @JsonProperty("Name") public String name;
        @OptionalColumn @JsonProperty("City") public String city;
        @OptionalColumn @JsonProperty("NoSuchColumn") public String extra;
    }

    @Test
    @DisplayName("an unknown column fails replaceAllData() and leaves the sheet untouched (REPLACE_ALL_DATA is all-or-nothing)")
    void unknownColumnLeavesSheetUntouched() {
        EmployeeWithUnknownColumn e = new EmployeeWithUnknownColumn();
        e.id = "E201";
        e.name = "Bad Column";
        e.extra = "x";

        ServerException ex = assertThrows(ServerException.class,
                () -> ItConfig.db().repository(EmployeeWithUnknownColumn.class).replaceAllData(e));
        assertTrue(ex.getServerMessage().contains("Unknown column: NoSuchColumn"), ex.getServerMessage());
        assertEquals(EMPLOYEE_COUNT, employeeCount());
        assertTrue(employees.existsById("E001"));
    }

    @Test
    @DisplayName("an @OptionalColumn missing from the sheet is skipped by replaceAllData(); one that exists is written")
    void optionalColumnSkippedOnSave() {
        EmployeeWithOptionalColumn e = new EmployeeWithOptionalColumn();
        e.id = "E201";
        e.name = "Optional";
        e.city = "Pune";
        e.extra = "dropped";

        ItConfig.db().repository(EmployeeWithOptionalColumn.class).replaceAllData(e);

        Row row = employee("E201");
        assertEquals("Optional", row.getString("Name"));
        assertEquals("Pune", row.getString("City"));
        assertFalse(row.columns().contains("NoSuchColumn"));
        assertEquals(1, employeeCount());
    }
}
