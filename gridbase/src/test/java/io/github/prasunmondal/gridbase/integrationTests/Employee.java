package io.github.prasunmondal.gridbase.integrationTests;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.prasunmondal.gridbase.SheetProperties;
import io.github.prasunmondal.gridbase.cache.CacheExpiry;
import io.github.prasunmondal.gridbase.cache.CacheStrategy;
import io.github.prasunmondal.gridbase.mapping.Repository;
import io.github.prasunmondal.gridbase.mapping.SheetKey;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.logging.Logger;

/** POJO entity for IT_Employees (column names differ in case, hence @JsonProperty). */
public class Employee {

    private static final Logger LOG = Logger.getLogger(Employee.class.getName());

    public static final SheetProperties PROPERTIES = ItConfig.BASE_PROPERTIES.toBuilder()
            .tabName(TestData.EMPLOYEES)
            .shallCache(true)
            .cacheStrategy(CacheStrategy.CACHE_FIRST)
            .cacheExpiry(CacheExpiry.ttlMinutes(30).or(CacheExpiry.dailyAt(LocalTime.of(1, 0), LocalTime.of(15, 0))))
            .cacheFile(Path.of(System.getProperty("java.io.tmpdir"), "hibernate-sheets-it", "cache.db"))
            .queueRequests(Duration.ofMillis(20))
            .queueMaxOperations(50)
            .preNetworkCall(call -> LOG.fine(
                    () -> "Employee request " + call.requestId() + " attempt " + call.attempt()))
            .postNetworkCall(result -> LOG.fine(
                    () -> "Employee request " + result.call().requestId() + " took " + result.elapsed().toMillis()
                            + " ms" + (result.succeeded() ? "" : ", failed: " + result.failure().getMessage())))
            .build();

    public static Repository<Employee> repository() {
        return PROPERTIES.repository(Employee.class);
    }

    @SheetKey
    @JsonProperty("EmployeeId")
    public String employeeId;

    @JsonProperty("Name")
    public String name;

    @JsonProperty("Department")
    public String department;

    @JsonProperty("Designation")
    public String designation;

    @JsonProperty("Salary")
    public Long salary;

    @JsonProperty("Age")
    public Integer age;

    @JsonProperty("Email")
    public String email;

    @JsonProperty("City")
    public String city;

    @JsonProperty("JoiningDate")
    public LocalDate joiningDate;

    @JsonProperty("IsActive")
    public Boolean isActive;

    @JsonProperty("Rating")
    public BigDecimal rating;

    @JsonProperty("ManagerId")
    public String managerId;

    @JsonProperty("Notes")
    public String notes;

    public Employee() {
    }

    public static Employee of(String id, String name, String department, long salary, LocalDate joiningDate) {
        Employee e = new Employee();
        e.employeeId = id;
        e.name = name;
        e.department = department;
        e.designation = "Engineer";
        e.salary = salary;
        e.age = 30;
        e.email = id.toLowerCase() + "@acme.test";
        e.city = "Pune";
        e.joiningDate = joiningDate;
        e.isActive = true;
        e.rating = new BigDecimal("4.1");
        e.managerId = "E001";
        e.notes = "";
        return e;
    }
}
