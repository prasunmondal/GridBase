package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.result.OperationResult;
import io.github.prasunmondal.gridbase.spec.Operation;
import io.github.prasunmondal.gridbase.spec.OperationSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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

    private final GridBase client;
    private final List<Operation> operations = new ArrayList<>();
    private final List<Consumer<RequestFailure>> failureActions = new ArrayList<>();
    private BatchResult result;
    private boolean executed;

    Batch(GridBase client) {
        this.client = client;
    }

    public <R extends OperationResult> Ref<R> add(OperationSpec<R> spec) {
        if (executed) {
            throw new IllegalStateException("Batch already executed; create a new one");
        }
        if (spec.worksheet().client() != client) {
            throw new IllegalArgumentException("Spec was created by a different GridBase client");
        }
        operations.add(spec.toOperation());
        return new Ref<>(this, operations.size() - 1, spec.resultType());
    }

    /**
     * Adds an action that runs if {@link #execute()} throws, after the client's own {@code onFailure}
     * actions. An exception thrown by the action is logged, not propagated.
     */
    public Batch onFailure(Consumer<RequestFailure> action) {
        failureActions.add(Objects.requireNonNull(action, "action"));
        return this;
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
        try {
            result = new BatchResult(this, client.execute(operations));
        } catch (RuntimeException e) {
            client.reportCallFailure(e, operations, failureActions);
            throw e;
        }
        executed = true;
        return result;
    }

    BatchResult result() {
        return result;
    }
}
