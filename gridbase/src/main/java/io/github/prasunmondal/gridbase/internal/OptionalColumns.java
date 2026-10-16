package io.github.prasunmondal.gridbase.internal;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import io.github.prasunmondal.gridbase.mapping.IgnoreUnknownColumns;
import io.github.prasunmondal.gridbase.mapping.OptionalColumn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds the {@link OptionalColumn} properties of a class (all of them for an {@link IgnoreUnknownColumns} class),
 * under the column names {@code mapper} writes.
 */
public final class OptionalColumns {

    private OptionalColumns() {
    }

    public static List<String> of(ObjectMapper mapper, Class<?> type) {
        if (Map.class.isAssignableFrom(type)) {
            return Compat.listOf();
        }
        BeanDescription description = mapper.getSerializationConfig().introspect(mapper.constructType(type));
        boolean all = description.getClassInfo().hasAnnotation(IgnoreUnknownColumns.class);
        List<String> columns = new ArrayList<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            if (all || marked(property.getField()) || marked(property.getGetter())) {
                columns.add(property.getName());
            }
        }
        return columns;
    }

    private static boolean marked(AnnotatedMember member) {
        return member != null && member.hasAnnotation(OptionalColumn.class);
    }
}
