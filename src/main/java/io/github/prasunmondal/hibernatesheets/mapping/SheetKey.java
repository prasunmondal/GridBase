package io.github.prasunmondal.hibernatesheets.mapping;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the field (or record component) that uniquely identifies a row. Used by
 * {@link Repository#findById}, {@link Repository#upsert} and {@link Repository#deleteById}.
 * The engine does not enforce uniqueness; keeping values unique is the caller's responsibility.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface SheetKey {
}
