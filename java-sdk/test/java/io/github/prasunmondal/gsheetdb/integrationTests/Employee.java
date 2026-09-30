package io.github.prasunmondal.gsheetdb.integrationTests;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.prasunmondal.gsheetdb.mapping.SheetKey;
import io.github.prasunmondal.gsheetdb.mapping.SheetTable;

import java.math.BigDecimal;
import java.time.LocalDate;

/** POJO entity for IT_Employees (column names differ in case, hence @JsonProperty). */
@SheetTable(worksheet = TestData.EMPLOYEES)
public class Employee {

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
