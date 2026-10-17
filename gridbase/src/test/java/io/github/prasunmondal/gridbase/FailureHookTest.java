package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.exception.QueueExecutionException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.result.RowsResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-call {@code onFailure} actions on requests, specs, batches and queued requests. */
class FailureHookTest {

    private static final String EMPTY = "\"rowCount\":0,\"rows\":[]";

    private final TransportException boom = new TransportException("boom", 503, null, true);
    private final List<String> events = new ArrayList<>();
    private final List<RequestFailure> failures = new ArrayList<>();

    private GridBase failingClient(int maxAttempts) {
        FakeTransport t = new FakeTransport(req -> {
            throw boom;
        });
        return GridBase.builder().transport(t).defaultSpreadsheetId("S")
                .retryPolicy(RetryPolicy.of(maxAttempts, Duration.ZERO))
                .onFailure(f -> events.add("client"))
                .build();
    }

    private void record(RequestFailure f) {
        events.add("call");
        failures.add(f);
    }

    @Test
    void requestActionRunsAfterClientActionsWithTheRequestDetails() {
        SheetRequest<RowsResult> request = failingClient(2).worksheet("T").select().request()
                .onFailure(this::record);

        assertSame(boom, assertThrows(TransportException.class, request::execute));

        assertEquals(List.of("client", "call"), events);
        RequestFailure f = failures.get(0);
        assertSame(boom, f.failure());
        assertEquals(2, f.attempts());
        assertTrue(f.readOnly());
        assertTrue(f.requestId() != null && f.requestBody() != null);
    }

    @Test
    void requestActionIsKeptByMapAndForceRefreshButNotAddedToTheOriginal() {
        SheetRequest<RowsResult> plain = failingClient(1).worksheet("T").select().request();
        SheetRequest<Integer> derived = plain.onFailure(this::record).forceRefresh().map(RowsResult::rowCount);

        assertThrows(TransportException.class, plain::execute);
        assertTrue(failures.isEmpty());
        assertThrows(TransportException.class, derived::execute);
        assertEquals(1, failures.size());
    }

    @Test
    void asyncFailureRunsTheActionAndKeepsTheException() {
        SheetRequest<RowsResult> request = failingClient(1).worksheet("T").select().request().onFailure(this::record);

        CompletionException e = assertThrows(CompletionException.class, () -> request.executeAsync().join());

        assertSame(boom, e.getCause());
        assertEquals(1, failures.size());
        assertSame(boom, failures.get(0).failure());
    }

    @Test
    void specActionRunsOnFetchAndKeepsTheFluentType() {
        GridBase db = failingClient(1);

        assertThrows(TransportException.class, () -> db.worksheet("T").select().onFailure(this::record).limit(5).fetch());
        assertThrows(TransportException.class,
                () -> db.worksheet("T").select().onFailure(this::record).fetchFirst());
        assertThrows(TransportException.class,
                () -> db.worksheet("T").insert(Map.of("id", "1")).onFailure(this::record).execute());

        assertEquals(3, failures.size());
        assertTrue(!failures.get(2).readOnly());
    }

    @Test
    void batchActionRuns() {
        GridBase db = failingClient(1);
        Batch batch = db.batch().onFailure(this::record);
        batch.add(db.worksheet("T").select());

        assertThrows(TransportException.class, batch::execute);
        assertEquals(List.of("client", "call"), events);
    }

    @Test
    void queuedRequestActionRunsOnlyForTheFailingRequest() {
        FakeTransport t = new FakeTransport(req -> req.toString().contains("\"Missing\"")
                ? "{\"success\":false,\"errors\":[{\"message\":\"Worksheet not found: Missing\"}]}"
                : FakeTransport.replying(EMPTY).send(req.toString()));    // only sent alone, one operation
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").retryPolicy(RetryPolicy.none()).build();
        List<String> failed = new ArrayList<>();

        APIRequestsQueue reqQ = new APIRequestsQueue();
        db.worksheet("A").select().onFailure(f -> failed.add("A")).queue(reqQ);
        db.worksheet("Missing").select().onFailure(f -> failed.add("Missing")).queue(reqQ);
        assertThrows(QueueExecutionException.class, reqQ::execute);

        assertEquals(List.of("Missing"), failed);
    }

    @Test
    void failureAfterTheReplyHasNoRequestDetailsAndAThrowingActionIsIgnored() {
        GridBase db = GridBase.builder().transport(FakeTransport.replying(EMPTY)).defaultSpreadsheetId("S").build();
        IllegalStateException mapping = new IllegalStateException("bad mapping");
        SheetRequest<Object> request = db.worksheet("T").select().request()
                .map(r -> {
                    throw mapping;
                })
                .onFailure(f -> {
                    throw new IllegalArgumentException("hook bug");
                })
                .onFailure(this::record);

        assertSame(mapping, assertThrows(IllegalStateException.class, request::execute));

        RequestFailure f = failures.get(0);
        assertSame(mapping, f.failure());
        assertNull(f.requestId());
        assertEquals(0, f.attempts());
    }

    @Test
    void successfulCallsDoNotRunTheAction() {
        GridBase db = GridBase.builder().transport(FakeTransport.replying(EMPTY)).defaultSpreadsheetId("S").build();
        db.worksheet("T").select().onFailure(this::record).fetch();
        db.worksheet("T").select().request().onFailure(this::record).executeAsync().join();
        assertTrue(failures.isEmpty());
    }
}
