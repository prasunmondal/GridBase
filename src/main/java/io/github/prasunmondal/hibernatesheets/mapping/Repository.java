package io.github.prasunmondal.hibernatesheets.mapping;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.prasunmondal.hibernatesheets.Batch;
import io.github.prasunmondal.hibernatesheets.HibernateSheets;
import io.github.prasunmondal.hibernatesheets.Ref;
import io.github.prasunmondal.hibernatesheets.SheetProperties;
import io.github.prasunmondal.hibernatesheets.Worksheet;
import io.github.prasunmondal.hibernatesheets.query.Filter;
import io.github.prasunmondal.hibernatesheets.query.Filters;
import io.github.prasunmondal.hibernatesheets.result.RowsResult;
import io.github.prasunmondal.hibernatesheets.spec.SelectSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Object-style access to a worksheet, keyed by the entity's {@link SheetKey}.
 *
 * <pre>{@code
 * @SheetTable(worksheet = "Customers")
 * public class Customer {
 *     @SheetKey @JsonProperty("customer_id") public String id;
 *     public String name;
 *     public Integer creditLimit;
 * }
 *
 * Repository<Customer> customers = db.repository(Customer.class);
 * customers.save(c);                               // upsert by customer_id
 * Optional<Customer> c = customers.findById("C-42");
 * }</pre>
 */
public final class Repository<T> {

    private final HibernateSheets client;
    private final Class<T> type;
    private final EntityMetadata meta;
    private final Worksheet worksheet;

    public Repository(HibernateSheets client, Class<T> type) {
        this.client = Objects.requireNonNull(client);
        this.type = Objects.requireNonNull(type);
        this.meta = EntityMetadata.of(type);
        this.worksheet = meta.spreadsheetId() != null
                ? client.worksheet(meta.spreadsheetId(), meta.worksheet())
                : client.worksheet(meta.worksheet());
    }

    /** Uses the client, spreadsheet and tab from {@code properties}; the tab falls back to the entity's. */
    public Repository(SheetProperties properties, Class<T> type) {
        this.client = properties.client();
        this.type = Objects.requireNonNull(type);
        this.meta = EntityMetadata.of(type);
        String tab = properties.tabName() != null ? properties.tabName() : meta.worksheet();
        this.worksheet = client.worksheet(properties.spreadsheetId(), tab);
    }

    public Worksheet worksheet() {
        return worksheet;
    }

    /** A SELECT on this worksheet; finish with {@code fetch(EntityClass.class)}. */
    public SelectSpec query() {
        return worksheet.select();
    }

    public List<T> findAll() {
        return worksheet.select().fetch(type);
    }

    public List<T> findWhere(Filter... filters) {
        return worksheet.select().where(filters).fetch(type);
    }

    public Optional<T> findFirstWhere(Filter... filters) {
        return worksheet.select().where(filters).fetchFirst(type);
    }

    public Optional<T> findById(Object id) {
        return worksheet.select().where(Filters.eq(meta.keyColumn(), requireId(id))).fetchFirst(type);
    }

    public boolean existsById(Object id) {
        return worksheet.select(meta.keyColumn())
                .where(Filters.eq(meta.keyColumn(), requireId(id)))
                .fetchFirst()
                .isPresent();
    }

    /** Appends the entity as a new row (no key check). */
    public T insert(T entity) {
        return worksheet.insert(entity).execute().as(type).get(0);
    }

    /** Appends all entities in one request and one sheet write. */
    public List<T> insertAll(Collection<? extends T> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        return worksheet.insertAll(entities).execute().as(type);
    }

    /** Updates the row with the entity's key, or inserts it if there is none. */
    public T save(T entity) {
        RowsResult result = worksheet.upsert()
                .key(meta.keyColumn(), keyOf(entity))
                .set(withoutKey(entity))
                .execute();
        return result.first().map(r -> r.as(type)).orElse(entity);
    }

    /** Saves all entities in a single request; the engine writes them together at the end. */
    public List<T> saveAll(Collection<? extends T> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Batch batch = client.batch();
        List<Ref<RowsResult>> refs = new ArrayList<>();
        for (T entity : entities) {
            refs.add(batch.add(worksheet.upsert()
                    .key(meta.keyColumn(), keyOf(entity))
                    .set(withoutKey(entity))));
        }
        batch.execute();
        List<T> saved = new ArrayList<>(refs.size());
        for (Ref<RowsResult> ref : refs) {
            ref.get().first().ifPresent(r -> saved.add(r.as(type)));
        }
        return saved;
    }

    /** @return number of rows deleted */
    public int deleteById(Object id) {
        return worksheet.delete().where(Filters.eq(meta.keyColumn(), requireId(id))).execute().count();
    }

    public int delete(T entity) {
        return deleteById(keyOf(entity));
    }

    private Object keyOf(T entity) {
        Objects.requireNonNull(entity, "entity");
        return requireId(meta.keyValue(entity));
    }

    private Map<String, Object> withoutKey(T entity) {
        Map<String, Object> values = new LinkedHashMap<>(
                client.objectMapper().convertValue(entity, new TypeReference<LinkedHashMap<String, Object>>() {
                }));
        values.remove(meta.keyColumn());
        return values;
    }

    private static Object requireId(Object id) {
        if (id == null || (id instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException("Entity key must not be null or blank");
        }
        return id;
    }
}
