package io.github.prasunmondal.hibernatesheets.cachingTests;

import io.github.prasunmondal.hibernatesheets.mapping.SheetKey;
import io.github.prasunmondal.hibernatesheets.mapping.SheetTable;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Entity for the {@code Products} worksheet of the fake engine. */
@SheetTable(worksheet = "Products")
public class Product {

    @SheetKey
    public String sku;
    public String name;
    public Integer stock;
    public BigDecimal price;
    public Boolean active;
    public LocalDate addedOn;
    public String notes;

    public Product() {
    }

    public static Product of(String sku, String name, int stock, String price) {
        Product p = new Product();
        p.sku = sku;
        p.name = name;
        p.stock = stock;
        p.price = new BigDecimal(price);
        p.active = true;
        p.addedOn = LocalDate.of(2026, 1, 15);
        p.notes = "";
        return p;
    }
}
