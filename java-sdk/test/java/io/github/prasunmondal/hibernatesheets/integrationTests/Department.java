package io.github.prasunmondal.hibernatesheets.integrationTests;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.prasunmondal.hibernatesheets.mapping.SheetKey;
import io.github.prasunmondal.hibernatesheets.mapping.SheetTable;

/** Record entity for IT_Departments. */
@SheetTable(worksheet = TestData.DEPARTMENTS)
public record Department(
        @SheetKey @JsonProperty("DeptCode") String deptCode,
        @JsonProperty("DeptName") String deptName,
        @JsonProperty("Budget") Long budget,
        @JsonProperty("Location") String location,
        @JsonProperty("HeadEmployeeId") String headEmployeeId) {
}
