package io.github.prasunmondal.hibernatesheets;

import io.github.prasunmondal.hibernatesheets.spec.CloneSpec;
import io.github.prasunmondal.hibernatesheets.spec.DeleteSpec;
import io.github.prasunmondal.hibernatesheets.spec.InsertSpec;
import io.github.prasunmondal.hibernatesheets.spec.SchemaSpecs;
import io.github.prasunmondal.hibernatesheets.spec.SelectSpec;
import io.github.prasunmondal.hibernatesheets.spec.UpdateSpec;
import io.github.prasunmondal.hibernatesheets.spec.UpsertSpec;

import java.util.Collection;
import java.util.Objects;

/**
 * Handle to one worksheet (tab) of one spreadsheet; the entry point for building operations.
 * Row 1 of the worksheet is the header row and defines the column names.
 * Cheap to create, immutable and thread-safe.
 */
public final class Worksheet {

    private final HibernateSheets client;
    private final String spreadsheetId;
    private final String name;

    Worksheet(HibernateSheets client, String spreadsheetId, String name) {
        this.client = Objects.requireNonNull(client, "client");
        if (spreadsheetId == null || spreadsheetId.isBlank()) {
            throw new IllegalArgumentException("spreadsheetId must not be empty");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("worksheet name must not be empty");
        }
        this.spreadsheetId = spreadsheetId;
        this.name = name;
    }

    public HibernateSheets client() {
        return client;
    }

    public String spreadsheetId() {
        return spreadsheetId;
    }

    public String name() {
        return name;
    }

    /** SELECT; pass column names to return only those columns. */
    public SelectSpec select(String... columns) {
        return new SelectSpec(this).columns(columns);
    }

    /** INSERT one row ({@code Map} or POJO/record). Chain {@code .row(...)} to add more. */
    public InsertSpec insert(Object row) {
        return new InsertSpec(this).row(row);
    }

    /** INSERT many rows in a single sheet write. */
    public InsertSpec insertAll(Collection<?> rows) {
        return new InsertSpec(this).rows(rows);
    }

    public UpdateSpec update() {
        return new UpdateSpec(this);
    }

    public DeleteSpec delete() {
        return new DeleteSpec(this);
    }

    public UpsertSpec upsert() {
        return new UpsertSpec(this);
    }

    /** CLONE matching rows ({@code clone} is taken by {@link Object}). */
    public CloneSpec cloneRows() {
        return new CloneSpec(this);
    }

    /** CREATE_WORKSHEET with this worksheet's name. */
    public SchemaSpecs.CreateWorksheet create() {
        return new SchemaSpecs.CreateWorksheet(this);
    }

    /** GET_COLUMNS; {@code columns().fetch()} returns the header names. */
    public SchemaSpecs.GetColumns columns() {
        return new SchemaSpecs.GetColumns(this);
    }

    public SchemaSpecs.AddColumns addColumns(String... columns) {
        return new SchemaSpecs.AddColumns(this, columns);
    }

    /** CLEAR_WORKSHEET: removes all data, keeps the header row. */
    public SchemaSpecs.ClearWorksheet clear() {
        return new SchemaSpecs.ClearWorksheet(this);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Worksheet w && w.client == client
                && w.spreadsheetId.equals(spreadsheetId) && w.name.equals(name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(spreadsheetId, name);
    }

    @Override
    public String toString() {
        return spreadsheetId + "/" + name;
    }
}
