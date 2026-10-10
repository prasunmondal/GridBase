package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.mapping.Repository;

import java.util.Objects;

/**
 * Base for a per-table holder: extend it once with the table's {@link SheetProperties} and entity
 * class, and get {@link #properties()}, {@link #repository()} and {@link #worksheet()} without
 * declaring them.
 *
 * <pre>{@code
 * // Kotlin
 * object Customers : GridBaseTable<Customer>(
 *     Db.BASE.toBuilder().tabName("Customers").build(),
 *     Customer::class.java)
 *
 * Customers.repository().findAll()
 * Customers.worksheet().select().request().forceRefresh().execute()
 * }</pre>
 *
 * <p>Nothing is built or sent by the constructor; the client and repository are created on first use.
 * The methods are final, so a subclass cannot declare its own method with the same name and no
 * parameters. Thread-safe.</p>
 *
 * @param <T> the entity class rows are mapped to
 */
public abstract class GridBaseTable<T> {

    private final SheetProperties properties;
    private final Class<T> entityType;
    private volatile Repository<T> repository;

    protected GridBaseTable(SheetProperties properties, Class<T> entityType) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.entityType = Objects.requireNonNull(entityType, "entityType");
    }

    public final SheetProperties properties() {
        return properties;
    }

    /** {@code properties().repository(entityType)}, created once. */
    public final Repository<T> repository() {
        Repository<T> r = repository;
        if (r == null) {
            repository = r = properties.repository(entityType); // a race only builds an equal one twice
        }
        return r;
    }

    /** The repository's worksheet: the properties' tab, or else the entity's {@code @SheetTable}. */
    public final Worksheet worksheet() {
        return repository().worksheet();
    }
}
