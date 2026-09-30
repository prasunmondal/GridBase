package io.github.prasunmondal.gsheetdb;

import io.github.prasunmondal.gsheetdb.result.OperationResult;
import io.github.prasunmondal.gsheetdb.spec.Operation;
import io.github.prasunmondal.gsheetdb.spec.OperationSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Several operations sent as <b>one</b> HTTP request and executed in order by the engine.
 *
 * <p>Semantics inherited from the engine:</p>
 * <ul>
 *   <li>Row operations (insert/update/delete/upsert/clone) work on an in-memory copy of each worksheet
 *       and are written once, after the last operation. If any operation fails, none of the row changes
 *       are written.</li>
 *   <li>Later operations see earlier ones: a SELECT after an INSERT in the same batch returns the new row.</li>
 *   <li>Schema operations (create worksheet, add columns, clear) are applied immediately and are not
 *       undone by a later failure.</li>
 * </ul>
 *
 * <pre>{@code
 * Batch batch = db.batch();
 * Ref<RowsResult> added = batch.add(orders.insert(order));
 * Ref<RowsResult> open  = batch.add(orders.select().where(eq("status", "OPEN")));
 * batch.execute();
 * List<Row> openRows = open.get().rows();
 * }</pre>
 */
public final class Batch {

    private final SheetDB client;
    private final List<Operation> operations = new ArrayList<>();
    private BatchResult result;
    private boolean executed;

    Batch(SheetDB client) {
        this.client = client;
    }

    public <R extends OperationResult> Ref<R> add(OperationSpec<R> spec) {
        if (executed) {
            throw new IllegalStateException("Batch already executed; create a new one");
        }
        if (spec.worksheet().client() != client) {
            throw new IllegalArgumentException("Spec was created by a different GSheetDB client");
        }
        operations.add(spec.toOperation());
        return new Ref<>(this, operations.size() - 1, spec.resultType());
    }

    public int size() {
        return operations.size();
    }

    /** Sends all operations in one request. Once it has succeeded, a batch cannot be executed again. */
    public BatchResult execute() {
        if (executed) {
            throw new IllegalStateException("Batch already executed; create a new one");
        }
        if (operations.isEmpty()) {
            throw new IllegalStateException("Batch is empty");
        }
        result = new BatchResult(this, client.execute(operations));
        executed = true;
        return result;
    }

    BatchResult result() {
        return result;
    }
}
