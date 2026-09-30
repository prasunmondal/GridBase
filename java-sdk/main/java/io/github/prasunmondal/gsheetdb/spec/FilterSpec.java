package io.github.prasunmondal.gsheetdb.spec;

import io.github.prasunmondal.gsheetdb.Worksheet;
import io.github.prasunmondal.gsheetdb.query.Filter;
import io.github.prasunmondal.gsheetdb.query.Sort;
import io.github.prasunmondal.gsheetdb.result.RowsResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Base for operations that select rows with {@code where / orderBy / limit / offset}.
 * The engine applies the filter, then the sort, then offset and limit, and only then performs the
 * write — so {@code delete().where(...).orderBy(...).limit(1)} deletes exactly one row.
 */
public abstract class FilterSpec<S extends FilterSpec<S>> extends OperationSpec<RowsResult> {

    protected final List<Filter> filters = new ArrayList<>();
    protected final List<Sort> orderBy = new ArrayList<>();
    protected int limit = -1;
    protected int offset = 0;

    protected FilterSpec(Worksheet worksheet) {
        super(worksheet);
    }

    @SuppressWarnings("unchecked")
    protected final S self() {
        return (S) this;
    }

    @Override
    public Class<RowsResult> resultType() {
        return RowsResult.class;
    }

    /** Adds conditions; all conditions of an operation are ANDed. */
    public S where(Filter... conditions) {
        return where(Arrays.asList(conditions));
    }

    public S where(Collection<Filter> conditions) {
        conditions.forEach(c -> filters.add(Objects.requireNonNull(c, "filter")));
        return self();
    }

    public S orderBy(String column) {
        return orderBy(Sort.asc(column));
    }

    public S orderBy(String column, Sort.Direction direction) {
        return orderBy(new Sort(column, direction));
    }

    public S orderBy(Sort... sorts) {
        for (Sort s : sorts) {
            orderBy.add(Objects.requireNonNull(s, "sort"));
        }
        return self();
    }

    public S limit(int maxRows) {
        if (maxRows < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        this.limit = maxRows;
        return self();
    }

    public S offset(int skipRows) {
        if (skipRows < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        this.offset = skipRows;
        return self();
    }

    protected Operation filtered(List<String> select, List<Assignment> values) {
        return operation(filters, orderBy, select, values, List.of(), List.of(), false, limit, offset);
    }
}
