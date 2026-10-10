package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.SheetPropertiesTest.Customer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GridBaseTableTest {

    private static final class Customers extends GridBaseTable<Customer> {
        Customers(SheetProperties properties) {
            super(properties, Customer.class);
        }
    }

    @Test
    void repositoryAndWorksheetUseThePropertiesTab() {
        FakeTransport t = FakeTransport.replying("\"rowCount\":1,\"rows\":[{\"customer_id\":\"C1\",\"name\":\"Asha\"}]");
        Customers customers = new Customers(SheetProperties.builder().transport(t).dbSheetUrl("SHEET1").tabName("Clients").build());

        assertEquals("Asha", customers.repository().findById("C1").orElseThrow().name);
        assertEquals("Clients", t.lastOperation().path("worksheet").asText());
        assertEquals("SHEET1", customers.worksheet().spreadsheetId());
        assertEquals("Clients", customers.worksheet().name());
        assertSame(customers.repository(), customers.repository());
        assertSame(customers.properties().client(), customers.worksheet().client());
    }

    @Test
    void worksheetFallsBackToEntityAnnotation() {
        Customers customers = new Customers(SheetProperties.builder()
                .transport(FakeTransport.replying()).dbSheetUrl("S").build());
        assertEquals("FromAnnotation", customers.worksheet().name());
    }

    @Test
    void constructorSendsNothingAndRejectsNull() {
        FakeTransport t = FakeTransport.replying();
        new Customers(SheetProperties.builder().transport(t).dbSheetUrl("S").build()).worksheet();
        assertEquals(0, t.requests.size());
        assertThrows(NullPointerException.class, () -> new Customers(null));
    }
}
