package io.github.prasunmondal.gridbase.mapping;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level form of {@link OptionalColumn}: every property of the annotated entity is treated as an
 * optional column. When an entity is written (insert, upsert, update, replaceAllData, clone), the values of properties
 * whose columns the worksheet does not have are dropped instead of the engine failing with "Unknown column".
 *
 * <p>The same limits as {@link OptionalColumn} apply: only writes are affected, and it needs an engine
 * deployed with optional-column support.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IgnoreUnknownColumns {
}
