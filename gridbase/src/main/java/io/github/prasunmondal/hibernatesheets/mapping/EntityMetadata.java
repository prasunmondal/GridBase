package io.github.prasunmondal.hibernatesheets.mapping;

import io.github.prasunmondal.hibernatesheets.internal.Compat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reflection metadata for an entity class, cached per class. */
final class EntityMetadata {

    private static final Map<Class<?>, EntityMetadata> CACHE = new ConcurrentHashMap<>();

    private final Class<?> type;
    private final String worksheet;
    private final String spreadsheetId;
    private final Field keyField;
    private final String keyColumn;

    private EntityMetadata(Class<?> type) {
        this.type = type;
        SheetTable table = type.getAnnotation(SheetTable.class);
        this.worksheet = table != null && !Compat.isBlank(table.worksheet()) ? table.worksheet() : type.getSimpleName();
        this.spreadsheetId = table != null && !Compat.isBlank(table.spreadsheetId()) ? table.spreadsheetId() : null;

        Field key = null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(SheetKey.class) && !Modifier.isStatic(f.getModifiers())) {
                    if (key != null) {
                        throw new IllegalArgumentException(type.getName() + " has more than one @SheetKey");
                    }
                    key = f;
                }
            }
        }
        this.keyField = key;
        if (key != null) {
            key.setAccessible(true);
            JsonProperty prop = key.getAnnotation(JsonProperty.class);
            this.keyColumn = prop != null && !prop.value().isEmpty() ? prop.value() : key.getName();
        } else {
            this.keyColumn = null;
        }
    }

    static EntityMetadata of(Class<?> type) {
        return CACHE.computeIfAbsent(type, EntityMetadata::new);
    }

    String worksheet() {
        return worksheet;
    }

    /** {@code null} means "use the client's default". */
    String spreadsheetId() {
        return spreadsheetId;
    }

    boolean hasKey() {
        return keyField != null;
    }

    String keyColumn() {
        requireKey();
        return keyColumn;
    }

    Object keyValue(Object entity) {
        requireKey();
        try {
            return keyField.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read @SheetKey field " + keyField, e);
        }
    }

    private void requireKey() {
        if (keyField == null) {
            throw new IllegalStateException(type.getName() + " has no @SheetKey field");
        }
    }
}
