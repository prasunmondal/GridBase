package io.github.prasunmondal.hibernatesheets.integrationTests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.prasunmondal.hibernatesheets.Worksheet;
import io.github.prasunmondal.hibernatesheets.query.Sort;
import io.github.prasunmondal.hibernatesheets.transport.HttpTransport;
import io.github.prasunmondal.hibernatesheets.result.Row;
import io.github.prasunmondal.hibernatesheets.result.RowsResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.EMPLOYEE_COLUMNS;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.ids;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.sortedIds;
import static io.github.prasunmondal.hibernatesheets.query.Filters.between;
import static io.github.prasunmondal.hibernatesheets.query.Filters.contains;
import static io.github.prasunmondal.hibernatesheets.query.Filters.endsWith;
import static io.github.prasunmondal.hibernatesheets.query.Filters.eq;
import static io.github.prasunmondal.hibernatesheets.query.Filters.gt;
import static io.github.prasunmondal.hibernatesheets.query.Filters.gte;
import static io.github.prasunmondal.hibernatesheets.query.Filters.in;
import static io.github.prasunmondal.hibernatesheets.query.Filters.isNotNull;
import static io.github.prasunmondal.hibernatesheets.query.Filters.isNull;
import static io.github.prasunmondal.hibernatesheets.query.Filters.lt;
import static io.github.prasunmondal.hibernatesheets.query.Filters.lte;
import static io.github.prasunmondal.hibernatesheets.query.Filters.ne;
import static io.github.prasunmondal.hibernatesheets.query.Filters.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SELECT: projections, every filter operator, AND-combination, sorting and pagination.
 * Read-only, so the seed data is loaded once for the whole class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelectQueryIT {

    private Worksheet employees;

    @BeforeAll
    void seed() {
        TestData.resetAll();
        employees = TestData.employeesSheet();
    }

    // ------------------------------------------------------------------ projection

    @Test
    @DisplayName("select() with no columns returns every column in header order")
    void selectAllColumns() {
        List<Row> rows = employees.select().fetch();
        assertEquals(EMPLOYEE_COUNT, rows.size());
        assertEquals(EMPLOYEE_COLUMNS, List.copyOf(rows.get(0).columns()));
    }

    @Test
    @DisplayName("select(cols) returns only those columns, in the requested order")
    void selectProjection() {
        List<Row> rows = employees.select("Name", "EmployeeId").where(eq("EmployeeId", "E001")).fetch();
        assertEquals(1, rows.size());
        assertEquals(List.of("Name", "EmployeeId"), List.copyOf(rows.get(0).columns()));
        assertEquals("Aarav Sharma", rows.get(0).getString("Name"));
        assertFalse(rows.get(0).has("Salary"));
    }

    @Test
    @DisplayName("rowCount matches the number of rows returned")
    void rowCountMatchesRows() {
        RowsResult result = employees.select().where(eq("Department", "Engineering")).execute();
        assertEquals(4, result.rowCount());
        assertEquals(4, result.rows().size());
    }

    // ------------------------------------------------------------------ equality

    @Test
    @DisplayName("eq on a text column")
    void eqText() {
        // Engineering: E001, E002, E003, E008
        assertEquals(List.of("E001", "E002", "E003", "E008"),
                sortedIds(employees.select().where(eq("Department", "Engineering")).fetch()));
    }

    @Test
    @DisplayName("eq on an integer column passes a Java int")
    void eqInteger() {
        assertEquals(List.of("E003"), ids(employees.select().where(eq("Age", 25)).fetch()));
    }

    @Test
    @DisplayName("eq(4.0) matches a cell holding 4 (JS renders both as \"4\")")
    void eqWholeDecimal() {
        assertEquals(List.of("E002"), ids(employees.select().where(eq("Rating", 4.0)).fetch()));
    }

    @Test
    @DisplayName("eq on a fractional decimal")
    void eqDecimal() {
        assertEquals(List.of("E001"), ids(employees.select().where(eq("Rating", 4.5)).fetch()));
    }

    @Test
    @DisplayName("eq on a boolean column")
    void eqBoolean() {
        assertEquals(List.of("E005", "E009"), sortedIds(employees.select().where(eq("IsActive", false)).fetch()));
        assertEquals(8, employees.select().where(eq("IsActive", true)).execute().rowCount());
    }

    @Test
    @DisplayName("eq is case-sensitive and exact")
    void eqIsExact() {
        assertEquals(0, employees.select().where(eq("Department", "engineering")).execute().rowCount());
        assertEquals(0, employees.select().where(eq("Department", "Engineer")).execute().rowCount());
    }

    @Test
    @DisplayName("ne excludes matching rows")
    void notEquals() {
        List<Row> rows = employees.select().where(ne("Department", "Engineering")).fetch();
        assertEquals(6, rows.size());
        assertTrue(rows.stream().noneMatch(r -> "Engineering".equals(r.getString("Department"))));
    }

    // ------------------------------------------------------------------ ordered comparisons

    @Test
    @DisplayName("gt on a number")
    void greaterThan() {
        // > 100000: E001 150k, E004 120k, E006 110k, E009 130k
        assertEquals(List.of("E001", "E004", "E006", "E009"),
                sortedIds(employees.select().where(gt("Salary", 100000)).fetch()));
    }

    @Test
    @DisplayName("gte includes the boundary")
    void greaterThanOrEqual() {
        assertEquals(List.of("E001", "E004", "E009"),
                sortedIds(employees.select().where(gte("Salary", 120000)).fetch()));
    }

    @Test
    @DisplayName("lt excludes the boundary")
    void lessThan() {
        assertEquals(List.of("E003", "E008"), sortedIds(employees.select().where(lt("Age", 26)).fetch()));
    }

    @Test
    @DisplayName("lte includes the boundary")
    void lessThanOrEqual() {
        assertEquals(List.of("E003", "E008", "E010"), sortedIds(employees.select().where(lte("Age", 26)).fetch()));
    }

    @Test
    @DisplayName("gte on a date column with a LocalDate (sent as epoch millis)")
    void dateComparison() {
        // Joined on/after 2022-01-01: E002, E003, E008, E010
        assertEquals(List.of("E002", "E003", "E008", "E010"),
                sortedIds(employees.select().where(gte("JoiningDate", LocalDate.of(2022, 1, 1))).fetch()));
    }

    @Test
    @DisplayName("date range with two conditions (gte + lt)")
    void dateRange() {
        List<Row> rows = employees.select()
                .where(gte("JoiningDate", LocalDate.of(2020, 1, 1)), lt("JoiningDate", LocalDate.of(2023, 1, 1)))
                .fetch();
        // 2020-09-14 E007, 2021-05-05 E005, 2022-07-01 E002
        assertEquals(List.of("E002", "E005", "E007"), sortedIds(rows));
    }

    @Test
    @DisplayName("gt boundary on an exact date is exclusive")
    void dateBoundaryExclusive() {
        assertEquals(List.of("E008"),
                ids(employees.select().where(gt("JoiningDate", LocalDate.of(2024, 2, 12))).fetch()));
    }

    // ------------------------------------------------------------------ text matching

    @Test
    @DisplayName("contains")
    void containsText() {
        assertEquals(List.of("E002", "E010"), sortedIds(employees.select().where(contains("City", "bad")).fetch()));
    }

    @Test
    @DisplayName("contains is case-sensitive")
    void containsIsCaseSensitive() {
        assertEquals(0, employees.select().where(contains("City", "BAD")).execute().rowCount());
    }

    @Test
    @DisplayName("startsWith")
    void startsWithText() {
        // Bangalore E001, E008; Bhubaneswar E003
        assertEquals(List.of("E001", "E003", "E008"), sortedIds(employees.select().where(startsWith("City", "B")).fetch()));
    }

    @Test
    @DisplayName("endsWith")
    void endsWithText() {
        // Mumbai E004, E005; Chennai E007
        assertEquals(List.of("E004", "E005", "E007"), sortedIds(employees.select().where(endsWith("City", "ai")).fetch()));
    }

    @Test
    @DisplayName("contains on a numeric column matches its text form")
    void containsOnNumber() {
        // Salaries containing "000" -> all 10; containing "50000" -> 150000 only
        assertEquals(List.of("E001"), ids(employees.select().where(contains("Salary", "50000")).fetch()));
    }

    // ------------------------------------------------------------------ null handling

    @Test
    @DisplayName("isNull matches empty cells")
    void isNullMatchesBlank() {
        assertEquals(List.of("E005"), ids(employees.select().where(isNull("Email")).fetch()));
        assertEquals(List.of("E008"), ids(employees.select().where(isNull("Rating")).fetch()));
    }

    @Test
    @DisplayName("isNotNull matches filled cells")
    void isNotNullMatchesFilled() {
        assertEquals(List.of("E002", "E003", "E005", "E008", "E010"),
                sortedIds(employees.select().where(isNotNull("ManagerId")).fetch()));
    }

    @Test
    @DisplayName("eq(col, null) is translated to isNull")
    void eqNull() {
        assertEquals(List.of("E001", "E004", "E006", "E007", "E009"),
                sortedIds(employees.select().where(eq("ManagerId", null)).fetch()));
    }

    // ------------------------------------------------------------------ AND

    @Test
    @DisplayName("multiple filters are ANDed")
    void multipleFiltersAreAnded() {
        List<Row> rows = employees.select()
                .where(eq("Department", "Engineering"), gte("Salary", 90000))
                .fetch();
        assertEquals(List.of("E001", "E002", "E003"), sortedIds(rows));
    }

    @Test
    @DisplayName("where(...) called twice accumulates conditions")
    void chainedWhere() {
        List<Row> rows = employees.select()
                .where(eq("Department", "Engineering"))
                .where(eq("City", "Bangalore"))
                .where(eq("IsActive", true))
                .fetch();
        assertEquals(List.of("E001", "E008"), sortedIds(rows));
    }

    @Test
    @DisplayName("no match returns an empty list, not an error")
    void noMatch() {
        assertEquals(0, employees.select().where(eq("Department", "Legal")).fetch().size());
    }

    // ------------------------------------------------------------------ ordering & paging

    @Test
    @DisplayName("orderBy ascending on a number")
    void orderByAsc() {
        List<Row> rows = employees.select().orderBy("Salary").fetch();
        assertEquals("E008", rows.get(0).getString("EmployeeId"));
        assertEquals("E001", rows.get(rows.size() - 1).getString("EmployeeId"));
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).getLong("Salary") <= rows.get(i).getLong("Salary"));
        }
    }

    @Test
    @DisplayName("orderBy descending on a number")
    void orderByDesc() {
        List<Row> rows = employees.select().orderBy("Salary", Sort.Direction.DESC).limit(3).fetch();
        assertEquals(List.of("E001", "E009", "E004"), ids(rows));
    }

    @Test
    @DisplayName("orderBy on text")
    void orderByText() {
        List<Row> rows = employees.select("Name").orderBy("Name").fetch();
        assertEquals("Aarav Sharma", rows.get(0).getString("Name"));
        assertEquals("Vikram Rao", rows.get(rows.size() - 1).getString("Name"));
    }

    @Test
    @DisplayName("orderBy on a date column")
    void orderByDate() {
        List<Row> rows = employees.select().orderBy("JoiningDate", Sort.Direction.DESC).limit(2).fetch();
        assertEquals(List.of("E008", "E010"), ids(rows));
    }

    @Test
    @DisplayName("multi-column sort: Department ASC, Salary DESC")
    void multiColumnSort() {
        List<Row> rows = employees.select()
                .orderBy(Sort.asc("Department"), Sort.desc("Salary"))
                .fetch();
        assertEquals(List.of("E001", "E002", "E003", "E008",   // Engineering
                        "E009", "E007",                          // Finance
                        "E006", "E010",                          // HR
                        "E004", "E005"),                         // Sales
                ids(rows));
    }

    @Test
    @DisplayName("filter + sort + limit together")
    void filterSortLimit() {
        List<Row> rows = employees.select()
                .where(eq("Department", "Engineering"))
                .orderBy("Salary", Sort.Direction.DESC)
                .limit(2)
                .fetch();
        assertEquals(List.of("E001", "E002"), ids(rows));
    }

    @Test
    @DisplayName("limit")
    void limit() {
        assertEquals(3, employees.select().orderBy("EmployeeId").limit(3).fetch().size());
    }

    @Test
    @DisplayName("limit(0) returns nothing")
    void limitZero() {
        assertEquals(0, employees.select().limit(0).fetch().size());
    }

    @Test
    @DisplayName("limit larger than the table returns everything")
    void limitLargerThanTable() {
        assertEquals(EMPLOYEE_COUNT, employees.select().limit(500).fetch().size());
    }

    @Test
    @DisplayName("offset + limit returns the requested page")
    void offsetAndLimit() {
        List<Row> page = employees.select().orderBy("EmployeeId").offset(3).limit(3).fetch();
        assertEquals(List.of("E004", "E005", "E006"), ids(page));
    }

    @Test
    @DisplayName("offset past the end returns nothing")
    void offsetPastEnd() {
        assertEquals(0, employees.select().offset(50).fetch().size());
    }

    @Test
    @DisplayName("paging through the table visits every row exactly once")
    void pagingWalk() {
        List<String> seen = new java.util.ArrayList<>();
        int pageSize = 4;
        for (int offset = 0; ; offset += pageSize) {
            List<Row> page = employees.select("EmployeeId").orderBy("EmployeeId").offset(offset).limit(pageSize).fetch();
            seen.addAll(ids(page));
            if (page.size() < pageSize) {
                break;
            }
        }
        assertEquals(TestData.employees().stream().map(m -> (String) m.get("EmployeeId")).toList(), seen);
    }

    // ------------------------------------------------------------------ first / typed

    @Test
    @DisplayName("fetchFirst returns the first row of the sorted result")
    void fetchFirst() {
        Optional<Row> top = employees.select().orderBy("Rating", Sort.Direction.DESC).fetchFirst();
        assertTrue(top.isPresent());
        assertEquals("E004", top.get().getString("EmployeeId"));
    }

    @Test
    @DisplayName("fetchFirst on no match is empty")
    void fetchFirstEmpty() {
        assertFalse(employees.select().where(eq("EmployeeId", "E999")).fetchFirst().isPresent());
    }

    @Test
    @DisplayName("fetch(Class) maps rows onto a POJO")
    void fetchAsPojo() {
        List<Employee> hr = employees.select().where(eq("Department", "HR")).orderBy("EmployeeId").fetch(Employee.class);
        assertEquals(2, hr.size());
        assertEquals("Ananya Ghosh", hr.get(0).name);
        assertEquals(110000L, hr.get(0).salary.longValue());
        assertEquals(LocalDate.of(2016, 2, 1), hr.get(0).joiningDate);
    }

    @Test
    @DisplayName("IN on SELECT")
    void inOnSelect() {
        List<Row> rows = employees.select().where(in("EmployeeId", "E001", "E002")).orderBy("EmployeeId").fetch();
        assertEquals(List.of("E001", "E002"), ids(rows));
    }

    @Test
    @DisplayName("BETWEEN on SELECT (inclusive)")
    void betweenOnSelect() {
        List<Row> rows = employees.select().where(between("Age", 22, 26)).orderBy("EmployeeId").fetch();
        assertEquals(List.of("E003", "E008", "E010"), ids(rows));
    }

    @Test
    @DisplayName("EQUALS with a raw JSON number matches a numeric cell")
    void rawNumericEquals() throws Exception {
        HttpTransport raw = HttpTransport.builder(ItConfig.ENDPOINT).build();
        String body = "{\"requestId\":\"raw\",\"operations\":[{\"id\":\"1\",\"type\":\"SELECT\","
                + "\"spreadsheetId\":\"" + ItConfig.SPREADSHEET_ID + "\",\"worksheet\":\"" + TestData.EMPLOYEES + "\","
                + "\"where\":[{\"column\":\"Age\",\"operator\":\"EQUALS\",\"value\":25}]}]}";
        JsonNode reply = new ObjectMapper().readTree(raw.send(body));

        assertTrue(reply.path("success").asBoolean());
        assertEquals(1, reply.path("results").get(0).path("rowCount").asInt());
        assertEquals(1, employees.select().where(eq("Age", 25)).fetch().size());
    }

    @Test
    @DisplayName("same query twice returns the same result (no hidden state between requests)")
    void repeatableReads() {
        List<Row> a = employees.select().where(eq("City", "Mumbai")).orderBy("EmployeeId").fetch();
        List<Row> b = employees.select().where(eq("City", "Mumbai")).orderBy("EmployeeId").fetch();
        assertEquals(a, b);
    }
}
