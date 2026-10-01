package io.github.prasunmondal.hibernatesheets.integrationTests;

import io.github.prasunmondal.hibernatesheets.mapping.Repository;
import io.github.prasunmondal.hibernatesheets.query.Sort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.DEPARTMENT_COUNT;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.hibernatesheets.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.hibernatesheets.query.Filters.eq;
import static io.github.prasunmondal.hibernatesheets.query.Filters.gt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repository&lt;T&gt; with a POJO entity (Employee) and a record entity (Department). */
class RepositoryIT {

    private Repository<Employee> employees;
    private Repository<Department> departments;

    @BeforeEach
    void reset() {
        TestData.resetAll();
        employees = Employee.repository();
        departments = ItConfig.db().repository(Department.class);
    }

    @Test
    @DisplayName("findById maps every column type onto the POJO")
    void findById() {
        Employee e = employees.findById("E001").orElseThrow();
        assertEquals("Aarav Sharma", e.name);
        assertEquals("Engineering", e.department);
        assertEquals("Senior Engineer", e.designation);
        assertEquals(150000L, e.salary.longValue());
        assertEquals(34, e.age.intValue());
        assertEquals("Bangalore", e.city);
        assertEquals(LocalDate.of(2019, 3, 15), e.joiningDate);
        assertTrue(e.isActive);
        assertEquals(new BigDecimal("4.5"), e.rating);
    }

    @Test
    @DisplayName("blank cells map to null for numbers/dates/booleans")
    void blanksBecomeNull() {
        Employee intern = employees.findById("E008").orElseThrow();
        assertNull(intern.rating);
        Employee noEmail = employees.findById("E005").orElseThrow();
        assertTrue(noEmail.email == null || noEmail.email.isEmpty());
    }

    @Test
    @DisplayName("findById on a missing key is empty")
    void findByIdMissing() {
        assertFalse(employees.findById("E999").isPresent());
    }

    @Test
    @DisplayName("findAll returns every row")
    void findAll() {
        assertEquals(EMPLOYEE_COUNT, employees.findAll().size());
        assertEquals(DEPARTMENT_COUNT, departments.findAll().size());
    }

    @Test
    @DisplayName("findWhere / findFirstWhere / query()")
    void finders() {
        assertEquals(4, employees.findWhere(gt("Salary", 100000)).size());
        assertEquals("E004", employees.findFirstWhere(eq("Department", "Sales"), eq("IsActive", true))
                .orElseThrow().employeeId);
        List<Employee> top2 = employees.query().orderBy("Salary", Sort.Direction.DESC).limit(2).fetch(Employee.class);
        assertEquals(List.of("E001", "E009"), top2.stream().map(e -> e.employeeId).toList());
    }

    @Test
    @DisplayName("existsById")
    void existsById() {
        assertTrue(employees.existsById("E010"));
        assertFalse(employees.existsById("E999"));
    }

    @Test
    @DisplayName("save() inserts a new entity")
    void saveInserts() {
        Employee e = Employee.of("E011", "Ishaan Roy", "Engineering", 91000, LocalDate.of(2026, 4, 1));
        employees.save(e);
        assertEquals(EMPLOYEE_COUNT + 1, employeeCount());
        Employee back = employees.findById("E011").orElseThrow();
        assertEquals("Ishaan Roy", back.name);
        assertEquals(LocalDate.of(2026, 4, 1), back.joiningDate);
    }

    @Test
    @DisplayName("save() on an existing key updates in place")
    void saveUpdates() {
        Employee e = employees.findById("E003").orElseThrow();
        e.salary = 97000L;
        e.designation = "Engineer II";
        e.notes = "Promoted 2026";
        employees.save(e);

        assertEquals(EMPLOYEE_COUNT, employeeCount());
        Employee back = employees.findById("E003").orElseThrow();
        assertEquals(97000L, back.salary.longValue());
        assertEquals("Engineer II", back.designation);
        assertEquals("Promoted 2026", back.notes);
        assertEquals(LocalDate.of(2023, 1, 10), back.joiningDate);   // untouched values survive the round trip
    }

    @Test
    @DisplayName("save() writes null fields as blank cells")
    void saveNullField() {
        Employee e = employees.findById("E001").orElseThrow();
        e.email = null;
        employees.save(e);
        assertTrue(TestData.employee("E001").isBlank("Email"));
    }

    @Test
    @DisplayName("saveAll() mixes inserts and updates in ONE request")
    void saveAll() {
        Employee existing = employees.findById("E002").orElseThrow();
        existing.city = "Chennai";
        Employee fresh1 = Employee.of("E011", "New One", "Sales", 50000, LocalDate.of(2026, 5, 1));
        Employee fresh2 = Employee.of("E012", "New Two", "Sales", 52000, LocalDate.of(2026, 5, 2));

        List<Employee> saved = employees.saveAll(List.of(existing, fresh1, fresh2));

        assertEquals(3, saved.size());
        assertEquals(EMPLOYEE_COUNT + 2, employeeCount());
        assertEquals("Chennai", employees.findById("E002").orElseThrow().city);
        assertEquals("New Two", employees.findById("E012").orElseThrow().name);
    }

    @Test
    @DisplayName("insert() / insertAll() append without key checks")
    void insertEntities() {
        employees.insert(Employee.of("E011", "Via Insert", "HR", 40000, LocalDate.of(2026, 6, 1)));
        employees.insertAll(List.of(
                Employee.of("E012", "Bulk A", "HR", 41000, LocalDate.of(2026, 6, 2)),
                Employee.of("E013", "Bulk B", "HR", 42000, LocalDate.of(2026, 6, 3))));
        assertEquals(EMPLOYEE_COUNT + 3, employeeCount());
        assertEquals(5, employees.findWhere(eq("Department", "HR")).size());
    }

    @Test
    @DisplayName("deleteById() and delete(entity)")
    void deletes() {
        assertEquals(1, employees.deleteById("E010"));
        assertEquals(0, employees.deleteById("E010"));
        Employee e = employees.findById("E009").orElseThrow();
        assertEquals(1, employees.delete(e));
        assertEquals(EMPLOYEE_COUNT - 2, employeeCount());
    }

    @Test
    @DisplayName("record entities: find, save (update + insert), delete")
    void recordEntity() {
        Department eng = departments.findById("ENG").orElseThrow();
        assertEquals("Engineering", eng.deptName());
        assertEquals(5000000L, eng.budget().longValue());

        departments.save(new Department("ENG", "Engineering", 5500000L, "Bangalore", "E001"));
        assertEquals(5500000L, departments.findById("ENG").orElseThrow().budget().longValue());

        departments.save(new Department("OPS", "Operations", 900000L, "Pune", "E007"));
        assertEquals(DEPARTMENT_COUNT + 1, departments.findAll().size());

        assertEquals(1, departments.deleteById("OPS"));
        Optional<Department> gone = departments.findById("OPS");
        assertFalse(gone.isPresent());
    }

    @Test
    @DisplayName("null or blank keys are rejected")
    void blankKeyRejected() {
        assertThrows(IllegalArgumentException.class, () -> employees.findById(""));
        Employee noKey = Employee.of("E011", "x", "HR", 1, LocalDate.now());
        noKey.employeeId = null;
        assertThrows(IllegalArgumentException.class, () -> employees.save(noKey));
    }
}
