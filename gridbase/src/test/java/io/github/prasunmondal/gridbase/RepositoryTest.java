package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.prasunmondal.gridbase.mapping.Repository;
import io.github.prasunmondal.gridbase.mapping.SheetKey;
import io.github.prasunmondal.gridbase.mapping.SheetTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.prasunmondal.gridbase.FakeTransport.json;
import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();

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
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();
        var saved = db.repository(Customer.class)
                .upsertAll(List.of(new Customer("C1", "A", 1), new Customer("C2", "B", 2)));
        assertEquals(1, t.requests.size());
        assertEquals(2, saved.size());
    }

    @Test
    void replaceAllDataSendsOneReplaceAllDataOperation() {
        FakeTransport t = FakeTransport.replying(
                "\"rowCount\":2,\"rows\":[{\"customer_id\":\"C1\",\"name\":\"A\"},{\"customer_id\":\"C2\",\"name\":\"B\"}]");
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();

        List<Customer> saved = db.repository(Customer.class)
                .replaceAllData(List.of(new Customer("C1", "A", 1), new Customer("C2", "B", 2)));

        assertEquals(1, t.requests.size());
        var ops = t.requests.get(0).path("operations");
        assertEquals(1, ops.size());
        assertEquals("REPLACE_ALL_DATA", ops.get(0).path("type").asText());
        assertEquals("Customers", ops.get(0).path("worksheet").asText());
        assertEquals(2, ops.get(0).path("rows").size());
        assertEquals(List.of("C1", "C2"), List.of(saved.get(0).id, saved.get(1).id));
    }

    @Test
    void replaceAllDataReplacesSheetWithOneEntity() {
        FakeTransport t = FakeTransport.replying(
                "\"rowCount\":1,\"rows\":[{\"customer_id\":\"C1\",\"name\":\"Asha\",\"creditLimit\":500}]");
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();

        Customer saved = db.repository(Customer.class).replaceAllData(new Customer("C1", "Asha", 500));

        var ops = t.requests.get(0).path("operations");
        assertEquals(1, ops.size());
        assertEquals("REPLACE_ALL_DATA", ops.get(0).path("type").asText());
        assertEquals(500, saved.creditLimit.intValue());
    }

    @Test
    void replaceAllDataOfNothingSendsReplaceAllDataWithoutRows() {
        FakeTransport t = FakeTransport.replying("\"rowCount\":0,\"rows\":[]");
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();

        assertTrue(db.repository(Customer.class).replaceAllData(List.of()).isEmpty());

        assertEquals(1, t.requests.get(0).path("operations").size());
        assertEquals("REPLACE_ALL_DATA", t.lastOperation().path("type").asText());
        assertFalse(t.lastOperation().has("rows"));
    }

    @Test
    void recordsWithExplicitSpreadsheet() {
        FakeTransport t = FakeTransport.replying("\"rowCount\":1,\"rows\":[{\"sku\":\"P1\",\"title\":\"Tea\"}]");
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();
        Product p = db.repository(Product.class).findById("P1").orElseThrow();
        assertEquals("Tea", p.title());
        assertEquals("OTHER", t.lastOperation().path("spreadsheetId").asText());
        assertEquals(1, t.lastOperation().path("limit").asInt());
    }

    @Test
    void deleteAndExists() {
        FakeTransport t = FakeTransport.replying("\"rowCount\":0,\"rows\":[]");
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();
        Repository<Customer> repo = db.repository(Customer.class);
        assertFalse(repo.existsById("C9"));
        assertEquals(0, repo.deleteById("C9"));
        assertEquals("DELETE", t.lastOperation().path("type").asText());
        assertTrue(t.lastOperation().has("where"));
    }

    @Test
    void deleteAllDeletesEveryRow() {
        FakeTransport t = FakeTransport.replying("\"worksheet\":\"Customers\",\"rowsCleared\":3,\"columnsCleared\":4");
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();
        assertEquals(3, db.repository(Customer.class).deleteAll());
        assertEquals("CLEAR_WORKSHEET", t.lastOperation().path("type").asText());
        assertEquals("Customers", t.lastOperation().path("worksheet").asText());
    }

    private static List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    public static class TwoKeys {
        @SheetKey
        public String a;
        @SheetKey
        public String b;
    }

    public static class NoKey {
        public String name;
    }

    public record Sku(@SheetKey @JsonProperty("sku_id") String id, String title) {
    }

    @Test
    void entityMetadataErrors() {
        FakeTransport t = FakeTransport.replying("\"rowCount\":1,\"rows\":[{\"sku_id\":\"S1\",\"title\":\"Tea\"}]");
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();

        IllegalArgumentException twoKeys = assertThrows(IllegalArgumentException.class, () -> db.repository(TwoKeys.class));
        assertTrue(twoKeys.getMessage().contains("more than one @SheetKey"));

        Repository<NoKey> noKey = db.repository(NoKey.class);
        assertThrows(IllegalStateException.class, () -> noKey.findById("1"));
        assertThrows(IllegalStateException.class, () -> noKey.existsById("1"));
        assertThrows(IllegalStateException.class, () -> noKey.upsert(new NoKey()));
        assertThrows(IllegalStateException.class, () -> noKey.deleteById("1"));
        assertEquals(0, t.requests.size());
        noKey.findAll();                                    // keyless entities can still be read
        assertEquals("NoKey", t.lastOperation().path("worksheet").asText());

        Repository<Sku> skus = db.repository(Sku.class);
        assertEquals("Tea", skus.upsert(new Sku("S1", "Tea")).title());
        var op = t.lastOperation();
        assertEquals("sku_id", op.path("where").get(0).path("column").asText());
        assertEquals(List.of("sku_id", "title"), fieldNames(op.path("values")));
        assertThrows(IllegalArgumentException.class, () -> skus.upsert(new Sku(null, "x")));
        assertThrows(IllegalArgumentException.class, () -> skus.findById(" "));
        assertThrows(IllegalArgumentException.class, () -> skus.deleteById(null));
    }

    @Test
    void repositoryCountsAndOptionals() {
        FakeTransport none = FakeTransport.replying("\"rowCount\":0,\"rows\":[]");
        Repository<Customer> empty = GridBase.builder().transport(none).defaultSpreadsheetId("S").build()
                .repository(Customer.class);
        assertTrue(empty.findFirstWhere(eq("name", "Nobody")).isEmpty());
        assertEquals(1, none.lastOperation().path("limit").asInt());
        assertFalse(empty.existsById("C9"));
        assertEquals(json("[\"customer_id\"]"), none.lastOperation().path("select"));
        assertTrue(empty.findById("C9").isEmpty());
        assertEquals(0, empty.deleteById("C9"));

        FakeTransport two = FakeTransport.replying("\"rowCount\":2,\"rows\":["
                + "{\"customer_id\":\"C1\",\"name\":\"Asha\",\"creditLimit\":5},{\"customer_id\":\"C1\",\"name\":\"Dup\"}]");
        Repository<Customer> repo = GridBase.builder().transport(two).defaultSpreadsheetId("S").build()
                .repository(Customer.class);
        assertEquals("Asha", repo.findFirstWhere(eq("name", "Asha")).orElseThrow().name);
        assertTrue(repo.existsById("C1"));
        assertEquals(2, repo.deleteById("C1"));
        assertEquals(2, repo.delete(new Customer("C1", "Asha", 5)));
        assertEquals("C1", two.lastOperation().path("where").get(0).path("value").asText());
        assertEquals(2, repo.findWhere(eq("customer_id", "C1")).size());
    }
}
