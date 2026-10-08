package io.github.prasunmondal.hibernatesheets.integrationTests;

import io.github.prasunmondal.hibernatesheets.Worksheet;
import io.github.prasunmondal.hibernatesheets.exception.ServerException;
import io.github.prasunmondal.hibernatesheets.result.Row;
import io.github.prasunmondal.hibernatesheets.result.RowsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.employee;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.ids;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.newEmployee;
import static io.github.prasunmondal.hibernatesheets.query.Filters.eq;
import static io.github.prasunmondal.hibernatesheets.query.Filters.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INSERT: add data, then read it back with a separate request to prove it was written. */
class InsertIT {

    private Worksheet employees;

    @BeforeEach
    void reset() {
        TestData.resetAll();
        employees = TestData.employeesSheet();
    }

    @Test
    @DisplayName("insert a Map, then fetch it by key with every value intact")
    void insertMapThenFetch() {
        employees.insert(newEmployee("E011", "Nikhil Verma")).execute();

        Row row = employee("E011");
        assertEquals("Nikhil Verma", row.getString("Name"));
        assertEquals("Engineering", row.getString("Department"));
        assertEquals(80000L, row.getLong("Salary").longValue());
        assertEquals(28, row.getInteger("Age").intValue());
        assertEquals("Pune", row.getString("City"));
        assertEquals(LocalDate.of(2026, 1, 5), row.getLocalDate("JoiningDate"));
        assertTrue(row.getBoolean("IsActive"));
        assertEquals(new BigDecimal("3.7"), row.getBigDecimal("Rating"));
        assertEquals(EMPLOYEE_COUNT + 1, employeeCount());
    }

    @Test
    @DisplayName("insert returns the inserted row")
    void insertReturnsRow() {
        RowsResult result = employees.insert(newEmployee("E011", "Nikhil Verma")).execute();
        assertEquals(1, result.count());
        assertEquals("E011", result.rows().get(0).getString("EmployeeId"));
    }

    @Test
    @DisplayName("inserted rows are appended at the bottom of the sheet")
    void insertAppendsAtBottom() {
        employees.insert(newEmployee("E000", "Should Be Last")).execute();
        List<Row> inSheetOrder = employees.select("EmployeeId").fetch();   // no orderBy -> sheet order
        assertEquals("E000", inSheetOrder.get(inSheetOrder.size() - 1).getString("EmployeeId"));
    }

    @Test
    @DisplayName("insertAll writes many rows in one request")
    void bulkInsert() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 11; i <= 30; i++) {
            rows.add(newEmployee(String.format("E%03d", i), "Bulk " + i));
        }
        RowsResult result = employees.insertAll(rows).execute();

        assertEquals(20, result.count());
        assertEquals(EMPLOYEE_COUNT + 20, employeeCount());
        assertEquals(20, employees.select().where(startsWith("Name", "Bulk ")).execute().rowCount());
        assertEquals("Bulk 30", employee("E030").getString("Name"));
    }

    @Test
    @DisplayName("chained .row(...) calls insert several rows")
    void chainedRows() {
        employees.insert(newEmployee("E011", "One"))
                .row(newEmployee("E012", "Two"))
                .row(newEmployee("E013", "Three"))
                .execute();
        assertEquals(List.of("E011", "E012", "E013"),
                ids(employees.select().where(startsWith("EmployeeId", "E01"), eq("City", "Pune"))
                        .orderBy("EmployeeId").fetch()));
    }

    @Test
    @DisplayName("insert a POJO (property names mapped via @JsonProperty)")
    void insertPojo() {
        Employee e = Employee.of("E011", "Pooja Sen", "Finance", 88000, LocalDate.of(2026, 3, 1));
        employees.insert(e).execute();

        Employee back = employees.select().where(eq("EmployeeId", "E011")).fetchFirst(Employee.class).orElseThrow();
        assertEquals("Pooja Sen", back.name);
        assertEquals("Finance", back.department);
        assertEquals(88000L, back.salary.longValue());
        assertEquals(LocalDate.of(2026, 3, 1), back.joiningDate);
        assertEquals(new BigDecimal("4.1"), back.rating);
        assertTrue(back.isActive);
    }

    @Test
    @DisplayName("insert with only some columns leaves the rest blank")
    void partialColumns() {
        employees.insert(Map.of("EmployeeId", "E011", "Name", "Only Name")).execute();
        Row row = employee("E011");
        assertEquals("Only Name", row.getString("Name"));
        assertTrue(row.isBlank("Department"));
        assertTrue(row.isBlank("Salary"));
        assertNull(row.getLong("Salary"));
        assertNull(row.getBoolean("IsActive"));
    }

    @Test
    @DisplayName("null values are written as empty cells")
    void nullValues() {
        Map<String, Object> m = new LinkedHashMap<>(newEmployee("E011", "Has Nulls"));
        m.put("Email", null);
        m.put("Rating", null);
        employees.insert(m).execute();
        Row row = employee("E011");
        assertTrue(row.isBlank("Email"));
        assertTrue(row.isBlank("Rating"));
    }

    @Test
    @DisplayName("column order in the Map does not matter")
    void columnOrderIndependent() {
        Map<String, Object> reversed = new LinkedHashMap<>();
        List<Map.Entry<String, Object>> entries = new ArrayList<>(newEmployee("E011", "Reversed").entrySet());
        java.util.Collections.reverse(entries);
        entries.forEach(en -> reversed.put(en.getKey(), en.getValue()));
        employees.insert(reversed).execute();
        assertEquals("Reversed", employee("E011").getString("Name"));
        assertEquals("Pune", employee("E011").getString("City"));
    }

    @Test
    @DisplayName("Unicode text (Bengali, Hindi, emoji) round-trips")
    void unicode() {
        Map<String, Object> m = new LinkedHashMap<>(newEmployee("E011", "প্রসূন মণ্ডল"));
        m.put("Notes", "राहुल 🚀 — “quotes” & <tags>");
        employees.insert(m).execute();

        Row row = employees.select().where(eq("Name", "প্রসূন মণ্ডল")).fetchFirst().orElseThrow();
        assertEquals("E011", row.getString("EmployeeId"));
        assertEquals("राहुल 🚀 — “quotes” & <tags>", row.getString("Notes"));
    }

    @Test
    @DisplayName("decimal, negative and large numbers keep their value")
    void numericEdgeCases() {
        Map<String, Object> m = new LinkedHashMap<>(newEmployee("E011", "Numbers"));
        m.put("Salary", 1234567890123L);
        m.put("Rating", -0.25);
        m.put("Age", 0);
        employees.insert(m).execute();
        Row row = employee("E011");
        assertEquals(1234567890123L, row.getLong("Salary").longValue());
        assertEquals(new BigDecimal("-0.25"), row.getBigDecimal("Rating"));
        assertEquals(0, row.getInteger("Age").intValue());
    }

    @Test
    @DisplayName("LocalDate values are stored as dates (filterable and sortable)")
    void datesAreFilterable() {
        Map<String, Object> m = new LinkedHashMap<>(newEmployee("E011", "Date Check"));
        m.put("JoiningDate", LocalDate.of(2026, 8, 15));
        employees.insert(m).execute();
        List<Row> rows = employees.select()
                .where(io.github.prasunmondal.hibernatesheets.query.Filters.gt("JoiningDate", LocalDate.of(2026, 1, 1)))
                .fetch();
        assertEquals(List.of("E011"), ids(rows));
    }

    @Test
    @DisplayName("an unknown column fails the request and writes nothing")
    void unknownColumnWritesNothing() {
        Map<String, Object> m = new LinkedHashMap<>(newEmployee("E011", "Bad Column"));
        m.put("NoSuchColumn", "x");
        ServerException e = assertThrows(ServerException.class, () -> employees.insert(m).execute());
        assertTrue(e.getServerMessage().contains("Unknown column: NoSuchColumn"));
        assertEquals(EMPLOYEE_COUNT, employeeCount());
    }

    @Test
    @DisplayName("one bad row in insertAll rejects the whole batch of rows")
    void bulkInsertIsAllOrNothing() {
        Map<String, Object> bad = new LinkedHashMap<>(newEmployee("E012", "Bad"));
        bad.put("Typo", 1);
        assertThrows(ServerException.class,
                () -> employees.insertAll(List.of(newEmployee("E011", "Good"), bad)).execute());
        assertEquals(EMPLOYEE_COUNT, employeeCount());
        assertFalse(employees.select().where(eq("EmployeeId", "E011")).fetchFirst().isPresent());
    }

    @Test
    @DisplayName("column names are case-sensitive on write")
    void columnNamesAreCaseSensitive() {
        assertThrows(ServerException.class, () -> employees.insert(Map.of("employeeid", "E011")).execute());
    }

    @Test
    @DisplayName("duplicate keys are allowed (the engine does not enforce uniqueness)")
    void duplicateKeysAllowed() {
        employees.insert(newEmployee("E001", "Duplicate Of Aarav")).execute();
        assertEquals(2, employees.select().where(eq("EmployeeId", "E001")).execute().rowCount());
    }

    @Test
    @DisplayName("concurrent inserts from separate requests don't overwrite each other (engine lock)")
    void concurrentInsertsAllLand() throws Exception {
        int writers = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String id = "E2" + i;
            done.add(pool.submit(() -> {
                start.await();
                employees.insert(newEmployee(id, "Concurrent " + id)).execute();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();

        assertEquals(EMPLOYEE_COUNT + writers, employeeCount());
        assertEquals(writers, employees.select().where(startsWith("Name", "Concurrent ")).fetch().size());
    }

    @Test
    @DisplayName("an empty row is rejected client-side")
    void emptyRowRejected() {
        assertThrows(IllegalArgumentException.class, () -> employees.insert(Map.of()));
    }
}
