package io.github.prasunmondal.gridbase.integrationTests;

import io.github.prasunmondal.gridbase.GridBase;
import io.github.prasunmondal.gridbase.RetryPolicy;
import io.github.prasunmondal.gridbase.SheetProperties;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.mapping.Repository;
import io.github.prasunmondal.gridbase.result.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.prasunmondal.gridbase.integrationTests.TestData.EMPLOYEE_COUNT;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employee;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.employeeCount;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.ids;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.newEmployee;
import static io.github.prasunmondal.gridbase.integrationTests.TestData.sortedIds;
import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static io.github.prasunmondal.gridbase.query.Filters.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several clients writing at once, as separate app instances would. Only meaningful against a live
 * deployment: the emulator serves one request at a time, so nothing ever overlaps there.
 */
@Tag("live")
class ConcurrencyIT {

    private static final int CLIENTS = 5;

    @BeforeEach
    void reset() {
        TestData.resetAll();
    }

    /** A client of its own, like another device: no shared queue or cache. Writes are not retried. */
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
                f.get(5, TimeUnit.MINUTES);
            } catch (java.util.concurrent.ExecutionException e) {
                failures.add(e.getCause());
            } catch (java.util.concurrent.TimeoutException e) {
                failures.add(e);
            }
        }
        pool.shutdownNow();
        return failures;
    }

    @Test
    @DisplayName("concurrent updates of different rows are all kept")
    void concurrentUpdatesOfDifferentRowsAreAllKept() throws Exception {
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 1; i <= CLIENTS; i++) {
            String id = String.format("E%03d", i);
            GridBase client = newClient();
            tasks.add(() -> employeesOf(client).update().set("Notes", "updated " + id).where(eq("EmployeeId", id)).execute());
        }
        assertEquals(List.of(), runTogether(tasks));

        for (int i = 1; i <= CLIENTS; i++) {
            String id = String.format("E%03d", i);
            assertEquals("updated " + id, employee(id).getString("Notes"));
        }
        assertEquals("", employee("E010").getString("Notes"));
    }

    @Test
    @DisplayName("concurrent APPENDs to one cell are serialised: every part is kept")
    void concurrentAppendsToOneCellAreSerialised() throws Exception {
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) {
            String part = "|p" + i;
            GridBase client = newClient();
            tasks.add(() -> employeesOf(client).update().append("Notes", part).where(eq("EmployeeId", "E002")).execute());
        }
        assertEquals(List.of(), runTogether(tasks));

        String notes = employee("E002").getString("Notes");
        for (int i = 0; i < CLIENTS; i++) {
            assertTrue(notes.contains("|p" + i), notes);
        }
        assertEquals(CLIENTS * 3, notes.length(), notes);
    }

    @Test
    @DisplayName("concurrent upserts of one key create exactly one row")
    void concurrentUpsertsOfOneKeyCreateOneRow() throws Exception {
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) {
            String city = "City" + i;
            GridBase client = newClient();
            tasks.add(() -> employeesOf(client).upsert().key("EmployeeId", "E050")
                    .set("Name", "Upserted").set("City", city).execute());
        }
        assertEquals(List.of(), runTogether(tasks));

        List<Row> rows = TestData.employeesSheet().select().where(eq("EmployeeId", "E050")).fetch();
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).getString("City").startsWith("City"));
        assertEquals(EMPLOYEE_COUNT + 1, employeeCount());
    }

    @Test
    @DisplayName("concurrent deletes and inserts leave exactly the right rows")
    void concurrentDeletesAndInsertsKeepTheRightRows() throws Exception {
        List<String> deleted = List.of("E002", "E004", "E006");
        List<String> inserted = List.of("E011", "E012", "E013");
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < deleted.size(); i++) {
            String gone = deleted.get(i);
            String added = inserted.get(i);
            GridBase deleter = newClient();
            GridBase inserter = newClient();
            tasks.add(() -> employeesOf(deleter).delete().where(eq("EmployeeId", gone)).execute());
            tasks.add(() -> employeesOf(inserter).insert(newEmployee(added, "Concurrent " + added)).execute());
        }
        assertEquals(List.of(), runTogether(tasks));

        assertEquals(List.of("E001", "E003", "E005", "E007", "E008", "E009", "E010", "E011", "E012", "E013"),
                sortedIds(TestData.allEmployees()));
        assertEquals("Rohan Das", employee("E003").getString("Name"));
        assertEquals("Vikram Rao", employee("E005").getString("Name"));
    }

    @Test
    @DisplayName("under contention, any failure is a retryable lock timeout")
    void contentionFailuresAreRetryableLockTimeouts() throws Exception {
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            GridBase client = newClient();
            String id = "E" + (100 + i);
            tasks.add(() -> {
                for (int round = 0; round < 3; round++) {
                    employeesOf(client).upsert().key("EmployeeId", id).set("Notes", "round " + round).execute();
                }
                return null;
            });
        }
        for (Throwable failure : runTogether(tasks)) {
            ServerException e = assertInstanceOf(ServerException.class, failure);
            assertTrue(e.isRetryable(), e.getMessage());
            assertTrue(e.getServerMessage().toLowerCase().contains("lock"), e.getServerMessage());
        }
    }

    /** ADR-007: reads take no lock, so they may run during a write, but each operation commits as a whole. */
    @Test
    @DisplayName("reads during writes never fail and see each single-operation commit whole")
    void readsDuringWritesNeverFailAndSeeWholeSingleOpCommits() throws Exception {
        AtomicBoolean writing = new AtomicBoolean(true);
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        List<Callable<?>> tasks = new ArrayList<>();
        GridBase writerClient = newClient();
        tasks.add(() -> {
            try {
                for (int batch = 0; batch < 4; batch++) {
                    List<Object> rows = new ArrayList<>();
                    for (int k = 0; k < 3; k++) {
                        rows.add(newEmployee("D6" + batch + k, "D6 " + batch + k));
                    }
                    employeesOf(writerClient).insertAll(rows).execute();
                }
            } finally {
                writing.set(false);
            }
            return null;
        });
        for (int r = 0; r < 3; r++) {
            GridBase reader = newClient();
            tasks.add(() -> {
                do {
                    seen.add(employeesOf(reader).select("EmployeeId").where(startsWith("Name", "D6 ")).execute().count());
                } while (writing.get());
                return null;
            });
        }
        assertEquals(List.of(), runTogether(tasks));

        assertTrue(seen.stream().allMatch(n -> n % 3 == 0), "partial commit seen: " + seen);
        assertEquals(12, employeesOf(newClient()).select().where(startsWith("Name", "D6 ")).execute().count());
    }

    @Test
    @DisplayName("many threads through the automatic queue end in the right state with fewer HTTP calls")
    void automaticQueueUnderLoadEndsInTheRightStateWithFewerCalls() throws Exception {
        AtomicInteger httpCalls = new AtomicInteger();
        SheetProperties counted = Employee.PROPERTIES.toBuilder()
                .shallCache(false)
                .postNetworkCall(result -> httpCalls.incrementAndGet())
                .build();
        Repository<Employee> repo = counted.repository(Employee.class);

        int threads = 12;
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String id = "E" + (200 + i);
            tasks.add(() -> {
                repo.upsert(Employee.of(id, "Queued " + id, "Engineering", 50000, LocalDate.of(2026, 1, 1)));
                return repo.findById(id).orElseThrow();
            });
        }
        assertEquals(List.of(), runTogether(tasks));

        List<String> queued = ids(TestData.employeesSheet().select().where(startsWith("Name", "Queued ")).orderBy("EmployeeId").fetch());
        assertEquals(threads, queued.size());
        assertEquals("E200", queued.get(0));
        assertTrue(httpCalls.get() < 2 * threads, httpCalls.get() + " calls for " + 2 * threads + " requests");
    }
}
