package io.github.prasunmondal.hibernatesheets.spec;

import io.github.prasunmondal.hibernatesheets.Worksheet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Base for operations that write column values: UPDATE, UPSERT and CLONE. */
public abstract class MutationSpec<S extends MutationSpec<S>> extends FilterSpec<S> {

    protected final Map<String, Assignment> assignments = new LinkedHashMap<>();

    protected MutationSpec(Worksheet worksheet) {
        super(worksheet);
    }

    /** Sets a column to a value ({@code null} clears the cell). */
    public S set(String column, Object value) {
        return assign(new Assignment(column, value, Assignment.Kind.SET));
    }

    /** Sets every property of a {@code Map} or POJO/record. */
    public S set(Object row) {
        columnValues(row).forEach(this::set);
        return self();
    }

    /** Appends text to the current cell value (an empty cell becomes the text). */
    public S append(String column, Object text) {
        return assign(new Assignment(column, text, Assignment.Kind.APPEND));
    }

    /** Prepends text to the current cell value. */
    public S prepend(String column, Object text) {
        return assign(new Assignment(column, text, Assignment.Kind.PREPEND));
    }

    private S assign(Assignment assignment) {
        // One JSON key per column: a second write to the same column would silently win on the server.
        if (assignments.containsKey(assignment.column())) {
            throw new IllegalArgumentException("Column '" + assignment.column() + "' is assigned more than once");
        }
        assignments.put(assignment.column(), assignment);
        return self();
    }

    protected List<Assignment> assignmentList() {
        return new ArrayList<>(assignments.values());
    }
}
