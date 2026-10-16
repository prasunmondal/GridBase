package io.github.prasunmondal.gridbase.integrationTests;

import io.github.prasunmondal.gridbase.Batch;
import io.github.prasunmondal.gridbase.GridBase;
import io.github.prasunmondal.gridbase.RetryPolicy;
import io.github.prasunmondal.gridbase.SheetProperties;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.mapping.Repository;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.result.RowsResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.prasunmondal.gridbase.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employee;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.ids;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.newEmployee;
import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static io.github.prasunmondal.gridbase.query.Filters.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Load well beyond what the functional ITs send: large payloads, many clients over many rounds, hot
 * cells, async bursts and big batches. Each test checks the final sheet state, not only that calls
 * returned, and prints latency figures to stderr.
 *
 * <p>Sized by system properties, so a quick run and a soak run use the same code:</p>
 * <pre>
 *   mvn test -pl gridbase -Dtest=StressIT -Dhs.logNetwork=false
 *       -Dhs.stress.clients=8 -Dhs.stress.rounds=5 -Dhs.stress.rows=500
 * </pre>
 *
 * <p>Runs against the emulator too, but the emulator is serial, so contention only happens on a live
 * deployment. Writes that fail with a retryable lock timeout are retried here: the engine rejects
 * them before touching the sheet, so a retry cannot apply a write twice.</p>
 */
@Tag("stress")
class StressIT {

    private static final int CLIENTS = Integer.getInteger("hs.stress.clients", 8);
    private static final int ROUNDS = Integer.getInteger("hs.stress.rounds", 5);
    private static final int ROWS = Integer.getInteger("hs.stress.rows", 500);
    private static final int WRITE_ATTEMPTS = Integer.getInteger("hs.stress.writeAttempts", 8);

    /** Every row a stress test adds has a Name with this prefix, so it can be counted and found. */
    private static final String PREFIX = "Stress ";

    @BeforeEach
    void reset() {
        TestData.resetAll();
    }

    /** Leave the worksheet at its seed size rather than hundreds of stress rows. */
    @AfterAll
    static void cleanUp() {
        TestData.resetAll();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("one insert of many rows lands whole and reads back in full")
    void bulkInsertLandsWholeAndReadsBack() {
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            rows.add(newEmployee(id("B", i), PREFIX + "bulk " + i));
        }
        Stats insert = new Stats("bulk insert of " + ROWS);
        insert.time(() -> TestData.employeesSheet().insertAll(rows).execute());

        Stats read = new Stats("bulk select of " + ROWS);
        List<Row> back = read.time(() -> TestData.employeesSheet().select().where(startsWith("Name", PREFIX)).fetch());
        insert.print();
        read.print();

        assertEquals(ROWS, back.size());
        assertEquals(ROWS, new HashSet<>(ids(back)).size(), "duplicate ids read back");
        assertEquals(EMPLOYEE_COUNT + ROWS, employeeCount());
        assertEquals(PREFIX + "bulk " + (ROWS - 1), employee(id("B", ROWS - 1)).getString("Name"));
    }

    @Test
    @DisplayName("many clients upserting and reading for many rounds end with every last write")
    void sustainedMixedLoadKeepsEveryLastWrite() throws Exception {
        Stats writes = new Stats("upsert");
        Stats reads = new Stats("select by key");
        List<Callable<?>> tasks = new ArrayList<>();
        for (int c = 0; c < CLIENTS; c++) {
            String key = id("M", c);
            Worksheet ws = employeesOf(newClient());
            tasks.add(() -> {
                for (int round = 0; round < ROUNDS; round++) {
                    String notes = "round " + round;
                    writes.time(() -> withLockRetry(() -> ws.upsert().key("EmployeeId", key)
                            .set("Name", PREFIX + key).set("Notes", notes).execute()));
                    List<Row> seen = reads.time(() -> ws.select().where(eq("EmployeeId", key)).fetch());
                    assertEquals(1, seen.size(), key + " after " + notes);
                    assertEquals(notes, seen.get(0).getString("Notes"), "read-your-write for " + key);
                }
                return null;
            });
        }
        assertEquals(List.of(), runTogether(tasks));
        writes.print();
        reads.print();

        List<Row> rows = TestData.employeesSheet().select().where(startsWith("Name", PREFIX)).fetch();
        assertEquals(CLIENTS, rows.size(), "one row per key");
        for (Row row : rows) {
            assertEquals("round " + (ROUNDS - 1), row.getString("Notes"), row.getString("EmployeeId"));
        }
        assertEquals(EMPLOYEE_COUNT + CLIENTS, employeeCount());
    }

    @Test
    @DisplayName("a hot cell appended to by every client keeps every part exactly once")
    void hotCellKeepsEveryAppendExactlyOnce() throws Exception {
        Stats appends = new Stats("append to one cell");
        List<Callable<?>> tasks = new ArrayList<>();
        for (int c = 0; c < CLIENTS; c++) {
            int client = c;
            Worksheet ws = employeesOf(newClient());
            tasks.add(() -> {
                for (int round = 0; round < ROUNDS; round++) {
                    String part = String.format("|%02d.%02d", client, round);
                    appends.time(() -> withLockRetry(() ->
                            ws.update().append("Notes", part).where(eq("EmployeeId", "E003")).execute()));
                }
                return null;
            });
        }
        assertEquals(List.of(), runTogether(tasks));
        appends.print();

        String notes = employee("E003").getString("Notes");
        for (int c = 0; c < CLIENTS; c++) {
            for (int round = 0; round < ROUNDS; round++) {
                String part = String.format("|%02d.%02d", c, round);
                assertEquals(notes.indexOf(part), notes.lastIndexOf(part), part + " appended twice: " + notes);
                assertTrue(notes.contains(part), part + " lost: " + notes);
            }
        }
        assertEquals(CLIENTS * ROUNDS * 6, notes.length(), notes);
    }

    @Test
    @DisplayName("a burst of async calls from one client all complete with the right answers")
    void asyncBurstFromOneClientCompletes() throws Exception {
        GridBase client = newClient();
        Worksheet ws = employeesOf(client);
        int burst = CLIENTS * ROUNDS;

        List<CompletableFuture<RowsResult>> inserts = new ArrayList<>();
        for (int i = 0; i < burst; i++) {
            inserts.add(ws.insert(newEmployee(id("A", i), PREFIX + "async " + i)).executeAsync());
        }
        CompletableFuture.allOf(inserts.toArray(new CompletableFuture[0])).get(10, TimeUnit.MINUTES);

        List<CompletableFuture<List<Row>>> selects = new ArrayList<>();
        for (int i = 0; i < burst; i++) {
            selects.add(ws.select().where(eq("EmployeeId", id("A", i))).fetchAsync());
        }
        for (int i = 0; i < burst; i++) {
            List<Row> rows = selects.get(i).get(10, TimeUnit.MINUTES);
            assertEquals(1, rows.size(), id("A", i));
            assertEquals(PREFIX + "async " + i, rows.get(0).getString("Name"));
        }
        assertEquals(EMPLOYEE_COUNT + burst, employeeCount());
    }

    @Test
    @DisplayName("one batch of many mixed operations applies them all in order")
    void largeBatchAppliesEveryOperationInOrder() {
        int ops = Math.max(ROWS / 5, 10);
        Worksheet employees = TestData.employeesSheet();
        Batch batch = ItConfig.db().batch();
        for (int i = 0; i < ops; i++) {
            String key = id("T", i);
            batch.add(employees.insert(newEmployee(key, PREFIX + "batch " + i)));
            batch.add(employees.update().set("Notes", "touched " + i).where(eq("EmployeeId", key)));
            if (i % 2 == 1) {
                batch.add(employees.delete().where(eq("EmployeeId", key)));
            }
        }
        Stats stats = new Stats("batch of " + (ops * 2 + ops / 2) + " operations");
        stats.time(batch::execute);
        stats.print();

        List<Row> left = employees.select().where(startsWith("Name", PREFIX)).orderBy("EmployeeId").fetch();
        assertEquals((ops + 1) / 2, left.size());
        for (Row row : left) {
            int i = Integer.parseInt(row.getString("EmployeeId").substring(1));
            assertEquals(0, i % 2, "odd rows were deleted: " + row.getString("EmployeeId"));
            assertEquals("touched " + i, row.getString("Notes"));
        }
    }

    @Test
    @DisplayName("many threads through one queued repository end in the right state")
    void queuedRepositoryUnderSustainedLoad() throws Exception {
        AtomicInteger httpCalls = new AtomicInteger();
        SheetProperties counted = Employee.PROPERTIES.toBuilder()
                .shallCache(false)
                .postNetworkCall(result -> httpCalls.incrementAndGet())
                .build();
        Repository<Employee> repo = counted.repository(Employee.class);

        int threads = CLIENTS * 2;
        List<Callable<?>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String key = id("Q", t);
            tasks.add(() -> {
                for (int round = 0; round < ROUNDS; round++) {
                    Employee e = Employee.of(key, PREFIX + key, "Engineering", 50000 + round, LocalDate.of(2026, 1, 1));
                    repo.upsert(e);
                    assertEquals(50000L + round, repo.findById(key).orElseThrow().salary, key);
                }
                return null;
            });
        }
        assertEquals(List.of(), runTogether(tasks));

        List<Row> rows = TestData.employeesSheet().select().where(startsWith("Name", PREFIX)).fetch();
        assertEquals(threads, rows.size());
        for (Row row : rows) {
            assertEquals(50000L + ROUNDS - 1, row.getLong("Salary"), row.getString("EmployeeId"));
        }
        int requests = threads * ROUNDS * 2;
        System.err.printf("[stress] queued repository: %d requests in %d HTTP calls%n", requests, httpCalls.get());
        assertTrue(httpCalls.get() <= requests, httpCalls.get() + " calls for " + requests + " requests");
    }

    // ------------------------------------------------------------------ helpers

    private static String id(String series, int n) {
        return series + String.format("%04d", n);
    }

    /** A client of its own, like another device: no shared queue or cache. */
    private static GridBase newClient() {
        return GridBase.builder()
                .endpoint(ItConfig.ENDPOINT)
                .defaultSpreadsheetId(ItConfig.SPREADSHEET_ID)
                .timeZone(ItConfig.ZONE)
                .requestTimeout(Duration.ofSeconds(120))
                .retryPolicy(RetryPolicy.defaults())
                .build();
    }

    private static Worksheet employeesOf(GridBase client) {
        return client.worksheet(TestData.EMPLOYEES);
    }

    /** Retries a write rejected with a retryable lock timeout, which the engine raises before writing. */
    private static <T> T withLockRetry(Callable<T> write) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return write.call();
            } catch (ServerException e) {
                boolean lock = e.isRetryable() && e.getServerMessage().toLowerCase().contains("lock");
                if (!lock || attempt >= WRITE_ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(Math.min(250L << (attempt - 1), 8000L));
            }
        }
    }

    /** Starts every task at the same moment and waits for all; returns their failures (empty if none). */
    private static List<Throwable> runTogether(List<Callable<?>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> running = new ArrayList<>();
        for (Callable<?> task : tasks) {
            running.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<Throwable> failures = new ArrayList<>();
        for (Future<?> f : running) {
            try {
                f.get(15, TimeUnit.MINUTES);
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            } catch (TimeoutException e) {
                failures.add(e);
            }
        }
        pool.shutdownNow();
        return failures;
    }

    /** Thread-safe latency recorder; prints count, p50, p95 and max to stderr. */
    private static final class Stats {
        private final String label;
        private final List<Long> millis = Collections.synchronizedList(new ArrayList<>());

        Stats(String label) {
            this.label = label;
        }

        <T> T time(Callable<T> call) {
            long start = System.nanoTime();
            try {
                return call.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                millis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        }

        void print() {
            List<Long> sorted;
            synchronized (millis) {
                sorted = new ArrayList<>(millis);
            }
            if (sorted.isEmpty()) {
                return;
            }
            Collections.sort(sorted);
            System.err.printf("[stress] %s: n=%d p50=%dms p95=%dms max=%dms%n", label, sorted.size(),
                    sorted.get(sorted.size() / 2), sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1),
                    sorted.get(sorted.size() - 1));
        }
    }
}
