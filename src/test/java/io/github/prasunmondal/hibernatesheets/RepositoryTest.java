package io.github.prasunmondal.hibernatesheets;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.prasunmondal.hibernatesheets.mapping.Repository;
import io.github.prasunmondal.hibernatesheets.mapping.SheetKey;
import io.github.prasunmondal.hibernatesheets.mapping.SheetTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryTest {

    @SheetTable(worksheet = "Customers")
    public static class Customer {
        @SheetKey
        @JsonProperty("customer_id")
        public String id;
        public String name;
        public Integer creditLimit;

        public Customer() {
        }

        Customer(String id, String name, Integer creditLimit) {
            this.id = id;
            this.name = name;
            this.creditLimit = creditLimit;
        }
    }

    @SheetTable(worksheet = "Products", spreadsheetId = "OTHER")
    public record Product(@SheetKey String sku, String title) {
    }

    @Test
    void upsertIsUpsertByKey() {
        FakeTransport t = FakeTransport.replying(
                "\"rowCount\":1,\"rows\":[{\"customer_id\":\"C1\",\"name\":\"Asha\",\"creditLimit\":500}]");
        HibernateSheets db = HibernateSheets.builder().transport(t).defaultSpreadsheetId("S").build();

        Customer saved = db.repository(Customer.class).upsert(new Customer("C1", "Asha", 500));

        var op = t.lastOperation();
        assertEquals("UPSERT", op.path("type").asText());
        assertEquals("Customers", op.path("worksheet").asText());
        assertEquals("customer_id", op.path("where").get(0).path("column").asText());
        assertEquals("C1", op.path("where").get(0).path("value").asText());
        assertEquals(List.of("customer_id", "name", "creditLimit"), fieldNames(op.path("values")));
        assertEquals(500, saved.creditLimit.intValue());
    }

    @Test
    void upsertAllIsOneRequest() {
        FakeTransport t = FakeTransport.replying(
                "\"rowCount\":1,\"rows\":[{\"customer_id\":\"C1\"}]",
                "\"rowCount\":1,\"rows\":[{\"customer_id\":\"C2\"}]");
        HibernateSheets db = HibernateSheets.builder().transport(t).defaultSpreadsheetId("S").build();
        var saved = db.repository(Customer.class)
                .upsertAll(List.of(new Customer("C1", "A", 1), new Customer("C2", "B", 2)));
        assertEquals(1, t.requests.size());
        assertEquals(2, saved.size());
    }

    @Test
    void recordsWithExplicitSpreadsheet() {
        FakeTransport t = FakeTransport.replying("\"rowCount\":1,\"rows\":[{\"sku\":\"P1\",\"title\":\"Tea\"}]");
        HibernateSheets db = HibernateSheets.builder().transport(t).defaultSpreadsheetId("S").build();
        Product p = db.repository(Product.class).findById("P1").orElseThrow();
        assertEquals("Tea", p.title());
        assertEquals("OTHER", t.lastOperation().path("spreadsheetId").asText());
        assertEquals(1, t.lastOperation().path("limit").asInt());
    }

    @Test
    void deleteAndExists() {
        FakeTransport t = FakeTransport.replying("\"rowCount\":0,\"rows\":[]");
        HibernateSheets db = HibernateSheets.builder().transport(t).defaultSpreadsheetId("S").build();
        Repository<Customer> repo = db.repository(Customer.class);
        assertFalse(repo.existsById("C9"));
        assertEquals(0, repo.deleteById("C9"));
        assertEquals("DELETE", t.lastOperation().path("type").asText());
        assertTrue(t.lastOperation().has("where"));
    }

    @Test
    void deleteAllDeletesEveryRow() {
        FakeTransport t = FakeTransport.replying("\"worksheet\":\"Customers\",\"rowsCleared\":3,\"columnsCleared\":4");
        HibernateSheets db = HibernateSheets.builder().transport(t).defaultSpreadsheetId("S").build();
        assertEquals(3, db.repository(Customer.class).deleteAll());
        assertEquals("CLEAR_WORKSHEET", t.lastOperation().path("type").asText());
        assertEquals("Customers", t.lastOperation().path("worksheet").asText());
    }

    private static List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
