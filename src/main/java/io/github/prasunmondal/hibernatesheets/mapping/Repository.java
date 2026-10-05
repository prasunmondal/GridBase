package io.github.prasunmondal.hibernatesheets.mapping;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.prasunmondal.hibernatesheets.HibernateSheets;
import io.github.prasunmondal.hibernatesheets.SheetProperties;
import io.github.prasunmondal.hibernatesheets.SheetRequest;
import io.github.prasunmondal.hibernatesheets.Worksheet;
import io.github.prasunmondal.hibernatesheets.query.Filter;
import io.github.prasunmondal.hibernatesheets.query.Filters;
import io.github.prasunmondal.hibernatesheets.result.OperationResult;
import io.github.prasunmondal.hibernatesheets.result.RowsResult;
import io.github.prasunmondal.hibernatesheets.spec.Operation;
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
    private final Requests requests = new Requests();

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

    /**
     * The same operations as not-yet-sent {@link SheetRequest}s, to {@code execute()} later or
     * {@code queue(...)} on an {@link io.github.prasunmondal.hibernatesheets.APIRequestsQueue}.
     */
    public Requests requests() {
        return requests;
    }

    public List<T> findAll() {
        return requests.findAll().execute();
    }

    public List<T> findWhere(Filter... filters) {
        return requests.findWhere(filters).execute();
    }

    public Optional<T> findFirstWhere(Filter... filters) {
        return requests.findFirstWhere(filters).execute();
    }

    public Optional<T> findById(Object id) {
        return requests.findById(id).execute();
    }

    public boolean existsById(Object id) {
        return requests.existsById(id).execute();
    }

    /** Appends the entity as a new row (no key check). */
    public T insert(T entity) {
        return requests.insert(entity).execute();
    }

    /** Appends all entities in one request and one sheet write. */
    public List<T> insertAll(Collection<? extends T> entities) {
        return requests.insertAll(entities).execute();
    }

    /** Updates the row with the entity's key, or inserts it if there is none. */
    public T save(T entity) {
        return requests.save(entity).execute();
    }

    /** Saves all entities in a single request; the engine writes them together at the end. */
    public List<T> saveAll(Collection<? extends T> entities) {
        return requests.saveAll(entities).execute();
    }

    /** @return number of rows deleted */
    public int deleteById(Object id) {
        return requests.deleteById(id).execute();
    }

    public int delete(T entity) {
        return requests.delete(entity).execute();
    }

    /** {@link Repository} operations that are built now and sent later. Arguments are validated now. */
    public final class Requests {

        private Requests() {
        }

        public SheetRequest<List<T>> findAll() {
            return worksheet.select().request(type);
        }

        public SheetRequest<List<T>> findWhere(Filter... filters) {
            return worksheet.select().where(filters).request(type);
        }

        public SheetRequest<Optional<T>> findFirstWhere(Filter... filters) {
            return worksheet.select().where(filters).firstRequest(type);
        }

        public SheetRequest<Optional<T>> findById(Object id) {
            return worksheet.select().where(Filters.eq(meta.keyColumn(), requireId(id))).firstRequest(type);
        }

        public SheetRequest<Boolean> existsById(Object id) {
            return worksheet.select(meta.keyColumn())
                    .where(Filters.eq(meta.keyColumn(), requireId(id)))
                    .firstRequest()
                    .map(Optional::isPresent);
        }

        public SheetRequest<T> insert(T entity) {
            return worksheet.insert(entity).request().map(r -> r.as(type).get(0));
        }

        public SheetRequest<List<T>> insertAll(Collection<? extends T> entities) {
            if (entities.isEmpty()) {
                return SheetRequest.completed(List.of());
            }
            return worksheet.insertAll(entities).request().map(r -> r.as(type));
        }

        public SheetRequest<T> save(T entity) {
            return worksheet.upsert()
                    .key(meta.keyColumn(), keyOf(entity))
                    .set(withoutKey(entity))
                    .request()
                    .map(r -> r.first().map(row -> row.as(type)).orElse(entity));
        }

        /** All upserts in one engine request: written together, all-or-nothing. */
        public SheetRequest<List<T>> saveAll(Collection<? extends T> entities) {
            if (entities.isEmpty()) {
                return SheetRequest.completed(List.of());
            }
            List<Operation> ops = new ArrayList<>(entities.size());
            for (T entity : entities) {
                ops.add(worksheet.upsert()
                        .key(meta.keyColumn(), keyOf(entity))
                        .set(withoutKey(entity))
                        .toOperation());
            }
            return SheetRequest.of(client, ops, response -> {
                List<T> saved = new ArrayList<>(ops.size());
                for (OperationResult result : response.results()) {
                    ((RowsResult) result).first().ifPresent(r -> saved.add(r.as(type)));
                }
                return saved;
            });
        }

        /** Resolves to the number of rows deleted. */
        public SheetRequest<Integer> deleteById(Object id) {
            return worksheet.delete().where(Filters.eq(meta.keyColumn(), requireId(id))).request()
                    .map(RowsResult::count);
        }

        public SheetRequest<Integer> delete(T entity) {
            return deleteById(keyOf(entity));
        }
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
