package io.github.prasunmondal.gridbase.integrationTests;

import io.github.prasunmondal.gridbase.Batch;
import io.github.prasunmondal.gridbase.GridBase;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.result.Row;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Test schema and seed data.
 *
 * <h2>IT_Employees</h2>
 * <pre>
 * EmployeeId | Name | Department | Designation | Salary | Age | Email | City | JoiningDate | IsActive | Rating | ManagerId | Notes
 * text (key) | text | text       | text        | number | int | text  | text | date        | boolean  | decimal| text      | text
 * </pre>
 * The 10 seed rows are chosen so each filter has a known, small answer (see the {@code EXPECTED_*}
 * comments next to the tests). Blank cells: E005.Email, E008.Rating, several ManagerId/Notes.
 *
 * <h2>IT_Departments</h2>
 * <pre>
 * DeptCode (key) | DeptName | Budget | Location | HeadEmployeeId
 * </pre>
 *
 * <h2>IT_SchemaProbe</h2>
 * Scratch worksheet for column tests. {@code SchemaOperationsIT} also creates one
 * {@code IT_Created_<timestamp>} worksheet per run (the engine has no "delete worksheet").
 */
public final class TestData {

    public static final String EMPLOYEES = "IT_Employees";
    public static final String DEPARTMENTS = "IT_Departments";
    public static final String SCHEMA_PROBE = "IT_SchemaProbe";

    public static final List<String> EMPLOYEE_COLUMNS = List.of(
            "EmployeeId", "Name", "Department", "Designation", "Salary", "Age", "Email",
            "City", "JoiningDate", "IsActive", "Rating", "ManagerId", "Notes");

    public static final List<String> DEPARTMENT_COLUMNS = List.of(
            "DeptCode", "DeptName", "Budget", "Location", "HeadEmployeeId");

    public static final int EMPLOYEE_COUNT = 10;
    public static final int DEPARTMENT_COUNT = 4;

    private static volatile boolean schemaReady;

    private TestData() {
    }

    // ------------------------------------------------------------------ seed rows

    public static List<Map<String, Object>> employees() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(emp("E001", "Aarav Sharma", "Engineering", "Senior Engineer", 150000, 34, "aarav@acme.test", "Bangalore", "2019-03-15", true, 4.5, "", "Team lead"));
        rows.add(emp("E002", "Priya Iyer", "Engineering", "Engineer", 95000, 27, "priya@acme.test", "Hyderabad", "2022-07-01", true, 4.0, "E001", ""));
        rows.add(emp("E003", "Rohan Das", "Engineering", "Engineer", 90000, 25, "rohan@acme.test", "Bhubaneswar", "2023-01-10", true, 3.5, "E001", ""));
        rows.add(emp("E004", "Sneha Kapoor", "Sales", "Sales Manager", 120000, 38, "sneha@acme.test", "Mumbai", "2017-11-20", true, 4.8, "", "Top performer"));
        rows.add(emp("E005", "Vikram Rao", "Sales", "Sales Executive", 60000, 29, "", "Mumbai", "2021-05-05", false, 3.0, "E004", ""));
        rows.add(emp("E006", "Ananya Ghosh", "HR", "HR Manager", 110000, 41, "ananya@acme.test", "Kolkata", "2016-02-01", true, 4.2, "", ""));
        rows.add(emp("E007", "Karthik Nair", "Finance", "Accountant", 70000, 31, "karthik@acme.test", "Chennai", "2020-09-14", true, 3.8, "", ""));
        rows.add(emp("E008", "Meera Joshi", "Engineering", "Intern", 25000, 22, "meera@acme.test", "Bangalore", "2025-06-01", true, "", "E001", "Summer intern"));
        rows.add(emp("E009", "Arjun Mehta", "Finance", "Finance Manager", 130000, 45, "arjun@acme.test", "Delhi", "2015-08-18", false, 4.6, "", "On sabbatical"));
        rows.add(emp("E010", "Divya Pillai", "HR", "Recruiter", 55000, 26, "divya@acme.test", "Hyderabad", "2024-02-12", true, 3.9, "E006", ""));
        return rows;
    }

    public static List<Map<String, Object>> departments() {
        return List.of(
                dept("ENG", "Engineering", 5000000, "Bangalore", "E001"),
                dept("SAL", "Sales", 2000000, "Mumbai", "E004"),
                dept("HR", "Human Resources", 800000, "Kolkata", "E006"),
                dept("FIN", "Finance", 1200000, "Delhi", "E009"));
    }

    /** A new employee not in the seed data. */
    public static Map<String, Object> newEmployee(String id, String name) {
        return emp(id, name, "Engineering", "Engineer", 80000, 28, id.toLowerCase() + "@acme.test",
                "Pune", "2026-01-05", true, 3.7, "E001", "");
    }

    private static Map<String, Object> emp(String id, String name, String dept, String designation, int salary,
                                           int age, String email, String city, String joiningDate, boolean active,
                                           Object rating, String managerId, String notes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("EmployeeId", id);
        m.put("Name", name);
        m.put("Department", dept);
        m.put("Designation", designation);
        m.put("Salary", salary);
        m.put("Age", age);
        m.put("Email", email);
        m.put("City", city);
        m.put("JoiningDate", joiningDate);
        m.put("IsActive", active);
        m.put("Rating", rating);
        m.put("ManagerId", managerId);
        m.put("Notes", notes);
        return m;
    }

    private static Map<String, Object> dept(String code, String name, long budget, String location, String head) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("DeptCode", code);
        m.put("DeptName", name);
        m.put("Budget", budget);
        m.put("Location", location);
        m.put("HeadEmployeeId", head);
        return m;
    }

    // ------------------------------------------------------------------ setup helpers

    public static Worksheet employeesSheet() {
        return ItConfig.db().worksheet(EMPLOYEES);
    }

    public static Worksheet departmentsSheet() {
        return ItConfig.db().worksheet(DEPARTMENTS);
    }

    /** Creates the test worksheets and header rows if missing (idempotent, once per JVM). */
    public static void ensureSchema() {
        if (schemaReady) {
            return;
        }
        synchronized (TestData.class) {
            if (schemaReady) {
                return;
            }
            GridBase db = ItConfig.db();
            createIfMissing(db.worksheet(EMPLOYEES));
            createIfMissing(db.worksheet(DEPARTMENTS));
            createIfMissing(db.worksheet(SCHEMA_PROBE));
            Batch headers = db.batch();
            headers.add(db.worksheet(EMPLOYEES).addColumns(EMPLOYEE_COLUMNS.toArray(String[]::new)).skipExisting());
            headers.add(db.worksheet(DEPARTMENTS).addColumns(DEPARTMENT_COLUMNS.toArray(String[]::new)).skipExisting());
            headers.add(db.worksheet(SCHEMA_PROBE).addColumns("Id", "Label").skipExisting());
            headers.execute();
            schemaReady = true;
        }
    }

    public static void createIfMissing(Worksheet ws) {
        try {
            ws.create().execute();
        } catch (ServerException e) {
            if (!e.getServerMessage().toLowerCase().contains("already exists")) {
                throw e;
            }
        }
    }

    /**
     * Restores both worksheets to exactly the seed data in ONE request:
     * clear (applied immediately, header kept) then insert (committed at the end).
     */
    public static void resetAll() {
        ensureSchema();
        Batch batch = ItConfig.db().batch();
        batch.add(employeesSheet().clear());
        batch.add(departmentsSheet().clear());
        batch.add(employeesSheet().insertAll(employees()));
        batch.add(departmentsSheet().insertAll(departments()));
        batch.execute();
        Employee.PROPERTIES.cache().ifPresent(c -> c.invalidate(ItConfig.SPREADSHEET_ID, EMPLOYEES));
    }

    // ------------------------------------------------------------------ assertion helpers

    public static List<Row> allEmployees() {
        return employeesSheet().select().orderBy("EmployeeId").fetch();
    }

    public static List<String> ids(List<Row> rows) {
        return rows.stream().map(r -> r.getString("EmployeeId")).collect(Collectors.toList());
    }

    public static List<String> sortedIds(List<Row> rows) {
        return ids(rows).stream().sorted().collect(Collectors.toList());
    }

    public static Row employee(String id) {
        return employeesSheet().select()
                .where(io.github.prasunmondal.gridbase.query.Filters.eq("EmployeeId", id))
                .fetchFirst()
                .orElseThrow(() -> new AssertionError("Employee " + id + " not found"));
    }

    public static int employeeCount() {
        return employeesSheet().select("EmployeeId").execute().rowCount();
    }
}
