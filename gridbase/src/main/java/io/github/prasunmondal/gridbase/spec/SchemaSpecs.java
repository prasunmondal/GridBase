package io.github.prasunmondal.gridbase.spec;

import io.github.prasunmondal.gridbase.internal.Compat;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.result.AddColumnsResult;
import io.github.prasunmondal.gridbase.result.ClearResult;
import io.github.prasunmondal.gridbase.result.ColumnsResult;
import io.github.prasunmondal.gridbase.result.OperationResult;
import io.github.prasunmondal.gridbase.result.WorksheetCreated;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Worksheet-level operations. The engine applies these immediately (not at commit time), so they are
 * not undone if a later operation in the same batch fails.
 */
public final class SchemaSpecs {

    private SchemaSpecs() {
    }

    abstract static class Simple<R extends OperationResult> extends OperationSpec<R> {
        private final OperationType type;
        private final Class<R> resultType;

        Simple(Worksheet worksheet, OperationType type, Class<R> resultType) {
            super(worksheet);
            this.type = type;
            this.resultType = resultType;
        }

        @Override
        public OperationType type() {
            return type;
        }

        @Override
        public Class<R> resultType() {
            return resultType;
        }

        @Override
        public Operation toOperation() {
            return operation(Compat.listOf(), Compat.listOf(), Compat.listOf(), Compat.listOf(), Compat.listOf(), Compat.listOf(), false, -1, 0);
        }
    }

    /** CREATE_WORKSHEET. Fails if a worksheet with this name exists. */
    public static final class CreateWorksheet extends Simple<WorksheetCreated> {
        public CreateWorksheet(Worksheet worksheet) {
            super(worksheet, OperationType.CREATE_WORKSHEET, WorksheetCreated.class);
        }
    }

    /** GET_COLUMNS: reads the header row. */
    public static final class GetColumns extends Simple<ColumnsResult> {
        public GetColumns(Worksheet worksheet) {
            super(worksheet, OperationType.GET_COLUMNS, ColumnsResult.class);
        }

        /** Shortcut for {@code execute().columns()}. */
        public List<String> fetch() {
            return execute().columns();
        }
    }

    /** CLEAR_WORKSHEET: clears every data row, keeps the header row. */
    public static final class ClearWorksheet extends Simple<ClearResult> {
        public ClearWorksheet(Worksheet worksheet) {
            super(worksheet, OperationType.CLEAR_WORKSHEET, ClearResult.class);
        }
    }

    /** ADD_COLUMNS: appends header cells. Column names are compared case-insensitively. */
    public static final class AddColumns extends OperationSpec<AddColumnsResult> {
        private final List<String> columns = new ArrayList<>();
        private boolean skipExisting;

        public AddColumns(Worksheet worksheet, String... columns) {
            super(worksheet);
            this.columns.addAll(Arrays.asList(columns));
        }

        public AddColumns column(String name) {
            columns.add(name);
            return this;
        }

        /** Skip names that already exist instead of failing. */
        public AddColumns skipExisting() {
            this.skipExisting = true;
            return this;
        }

        @Override
        public OperationType type() {
            return OperationType.ADD_COLUMNS;
        }

        @Override
        public Class<AddColumnsResult> resultType() {
            return AddColumnsResult.class;
        }

        @Override
        public Operation toOperation() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("ADD_COLUMNS on '" + worksheet.name() + "' has no columns");
            }
            for (String c : columns) {
                if (c == null || Compat.isBlank(c)) {
                    throw new IllegalArgumentException("Column names must not be blank");
                }
            }
            return operation(Compat.listOf(), Compat.listOf(), Compat.listOf(), Compat.listOf(), Compat.listOf(), columns, skipExisting, -1, 0);
        }
    }
}
