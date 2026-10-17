package io.github.prasunmondal.gridbase.mapping;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a property whose column may be missing from the sheet. When an entity is written (insert, upsert,
 * update, replaceAllData, clone), the value of this property is dropped if the worksheet has no such column,
 * instead of the engine failing with "Unknown column". When the column exists it is written as usual.
 *
 * <p>Only writes are affected; filters, sorting and {@code select(...)} on the column still require it.
 * Needs an engine deployed with this feature: an older engine ignores the hint and still rejects the
 * unknown column.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface OptionalColumn {
}
