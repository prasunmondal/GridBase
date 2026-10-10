package io.github.prasunmondal.gridbase.mapping;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an entity class to a worksheet. Property names map to header names through Jackson
 * ({@code @JsonProperty("Customer Name")} when they differ; {@code @JsonIgnore} to skip a property).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SheetTable {

    /** Worksheet (tab) name. Defaults to the class's simple name. */
    String worksheet() default "";

    /** Spreadsheet id. Defaults to the client's default spreadsheet. */
    String spreadsheetId() default "";
}
