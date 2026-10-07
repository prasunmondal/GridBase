# hibernate.sheets Java client

A Java 17+ library for the **hibernate.sheets** Apps Script engine. Other projects add one
dependency and talk to Google Sheets through a typed, fluent API instead of hand-building JSON.

```java
HibernateSheets db = HibernateSheets.builder()
        .endpoint("https://script.google.com/macros/s/<DEPLOYMENT_ID>/exec")
        .defaultSpreadsheetId("1C8rsAWa0XfpxfHSb-F-FALSvmCT1knQ5lBoegQ8Phwc")
        .timeZone(ZoneId.of("Asia/Kolkata"))   // the spreadsheet's time zone
        .build();

Worksheet orders = db.worksheet("Orders");

List<Row> open = orders.select()
        .where(eq("status", "OPEN"), gte("qty", 5))
        .orderBy("createdAt", Sort.Direction.DESC)
        .limit(20)
        .fetch();
```

Or configure everything about one entity — engine URL, spreadsheet, tab, retries, network hooks,
caching — in a single `SheetProperties` object the entity carries:

```java
public class Employee {
    public static final SheetProperties PROPERTIES = SheetProperties.builder()
            .scriptUrl("https://script.google.com/macros/s/<DEPLOYMENT_ID>/exec")
            .dbSheetUrl("https://docs.google.com/spreadsheets/d/<SPREADSHEET_ID>/edit")
            .tabName("Employees")
            .shallCache(true)
            .cacheExpiry(CacheExpiry.ttlMinutes(30))
            .build();

    public static Repository<Employee> repository() { return PROPERTIES.repository(Employee.class); }
    ...
}

Optional<Employee> e = Employee.repository().findById("E001");
```

Dependencies: `jackson-databind`, `jackson-datatype-jsr310` and `sqlite-jdbc` (for the response
cache). HTTP uses `java.net.HttpURLConnection` and logging uses `java.util.logging`, so the library
also runs on Android (API 26+); the build checks this with animal-sniffer.

### Android

- Make calls off the main thread (Android throws `NetworkOnMainThreadException`).
- The response cache works on Android: `sqlite-jdbc` bundles native libraries for arm, arm64, x86
  and x86_64. By default the database goes to `<app cache dir>/hibernate-sheets/cache.db`; to keep it
  out of the cache dir (which Android may clear under storage pressure), pass
  `.cacheFile(new File(context.getFilesDir(), "hibernate-sheets.db").toPath())`.
- With R8/minification on, keep the SQLite driver (its native code calls back into it by name):

  ```
  -keep class org.sqlite.** { *; }
  ```

- If the cache cannot be opened on a device, caching is disabled with a warning and every request
  still works.

---

## Install

The project is a plain Maven library (`io.github.prasunmondal:hibernate-sheets-client`).

```bash
mvn install          # into ~/.m2 for local projects
```

```xml
<dependency>
  <groupId>io.github.prasunmondal</groupId>
  <artifactId>hibernate-sheets-client</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

To share it without Maven Central, push the repo to GitHub and consume it via
[JitPack](https://jitpack.io) or GitHub Packages.

---

## Architecture

```
your code
  │  Worksheet / Repository<T>          ← fluent API, entity mapping
  │  SelectSpec, InsertSpec, ...        ← builders; nothing is sent until execute()/fetch()
  ▼
Operation (immutable record)            ← transport-neutral description of one engine op
  │  RequestSerializer                  ← exact JSON contract of RequestParser.js
  ▼
HibernateSheets.execute(List<Operation>)  ← one HTTP request, retry policy
  │  SqliteResponseCache (optional)     ← reads served from / stored in SQLite; writes invalidate
  │  RequestQueue (optional)            ← requests made close together → one HTTP call
  │  pre/post network-call actions      ← run around every HTTP attempt
  │  Transport (HttpTransport default)  ← POST /exec, follow Apps Script 302, auth header
  ▼
ResponseParser                          ← success → typed results; success:false → ServerException
```

`SheetProperties` sits on top: it holds all the settings for one entity/worksheet and builds the
`HibernateSheets` client (and cache) from them.

| Package | Contents |
|---|---|
| `hibernatesheets` | `HibernateSheets` (client), `SheetProperties`, `Worksheet`, `Batch`/`Ref`/`BatchResult`, `RetryPolicy`, `NetworkCall`/`NetworkCallResult` |
| `.cache` | `SqliteResponseCache`, `CacheExpiry`, `CacheStrategy` |
| `.query` | `Filters` (static factory), `Filter`, `Sort` |
| `.spec` | Operation builders and the `Operation` record |
| `.result` | `Row`, `RowsResult`, `ColumnsResult`, `AddColumnsResult`, `ClearResult`, `WorksheetCreated` |
| `.mapping` | `@SheetTable`, `@SheetKey`, `Repository<T>` |
| `.transport` | `Transport` interface, `HttpTransport` |
| `.exception` | `HibernateSheetsException` → `ServerException`, `TransportException` |

---

## API tour

```java
import static io.github.prasunmondal.hibernatesheets.query.Filters.*;

// SELECT
List<Row> rows      = orders.select().where(eq("customer", "C-7")).fetch();
List<Order> typed   = orders.select().where(in("status", "OPEN", "HOLD")).fetch(Order.class);
Optional<Row> first = orders.select("id", "qty").where(eq("id", "A1")).fetchFirst();

// INSERT (Map, POJO or record; one or many rows, one sheet write)
orders.insert(Map.of("id", "A9", "qty", 4)).execute();
orders.insertAll(listOfOrders).execute();

// UPDATE / DELETE — where(...) is mandatory unless you call all()
int changed = orders.update()
        .set("status", "SHIPPED")
        .append("log", " | shipped")
        .where(eq("id", "A9"))
        .execute().count();

orders.delete().where(eq("status", "CANCELLED")).orderBy("createdAt").limit(10).execute();

// UPSERT — key() adds both the filter and the value written on insert
orders.upsert().key("id", "A9").set("qty", 6).execute();

// CLONE matching rows with overrides
orders.cloneRows().where(eq("id", "A9")).set("id", "A10").execute();

// Worksheet / schema operations
db.worksheet("Archive").create().execute();
orders.addColumns("discount", "notes").skipExisting().execute();
List<String> headers = orders.columns().fetch();
orders.clear().execute();                      // keeps the header row
```

### Rows and types

`Row` exposes raw values plus typed getters (`getString`, `getInteger`, `getLong`, `getBigDecimal`,
`getBoolean`, `getInstant`, `getLocalDate`, `getLocalDateTime`) and `as(Class)`.

- Empty cells arrive as `""`; typed getters and entity mapping turn them into `null`.
- Date cells arrive as UTC instants (`JSON.stringify(Date)`). `getLocalDate` and `LocalDate`
  entity fields convert them in the configured `timeZone`, so a sheet date of 30 Sep in
  Asia/Kolkata stays 30 Sep instead of becoming 29 Sep UTC.
- `java.time` values you write are sent as ISO-8601 text.

### How filters compare

The SDK converts filter values so the engine's comparisons behave the way Java code expects:

| Filters | Engine evaluates | SDK sends |
|---|---|---|
| `eq`, `ne`, `contains`, `startsWith`, `endsWith` | `String(cell) === value` | value rendered like JS `String()` (`5.0` → `"5"`) |
| `gt`, `gte`, `lt`, `lte`, `between` | `Number(cell)` / relational | numbers as-is; `Instant`/`LocalDate`/… as epoch millis |
| `in` | strict `includes` | raw JSON values (numbers stay numbers) |
| `eq(col, null)` / `ne(col, null)` | — | rewritten to `isNull` / `isNotNull` |

Pass numbers as numbers: `eq("price", 12.5)` matches a numeric cell; `eq("price", "12.50")` never will.
All conditions in one operation are ANDed (the engine has no OR).

---

## Batches (one request, one commit)

```java
Batch batch = db.batch();
Ref<RowsResult> added   = batch.add(orders.insert(newOrder));
Ref<RowsResult> stock   = batch.add(inventory.update().set("reserved", 1).where(eq("sku", "P1")));
Ref<ColumnsResult> cols = batch.add(orders.columns());
batch.execute();

added.get().rows();  cols.get().columns();
```

What the engine guarantees, and therefore what a batch means:

- Row operations change an in-memory copy of each worksheet; everything is written after the last
  operation. **If any operation fails, no row changes are written.**
- Later operations see earlier ones (SELECT after INSERT returns the new row).
- `create`, `addColumns` and `clear` hit the sheet immediately and are **not** undone by a later failure.
- The final write itself is several Sheets calls; a crash midway (e.g. Apps Script timeout) can leave
  a partial write. It is batching, not a true transaction.

---

## Entities

```java
@SheetTable(worksheet = "Customers")          // spreadsheetId = "" → client default
public class Customer {
    @SheetKey @JsonProperty("customer_id") public String id;
    public String name;
    public Integer creditLimit;
}

Repository<Customer> customers = db.repository(Customer.class);
customers.save(c);                      // UPSERT on customer_id
customers.saveAll(list);                // all upserts in ONE request
Optional<Customer> c = customers.findById("C-42");
List<Customer> vip  = customers.findWhere(gt("creditLimit", 10_000));
customers.deleteById("C-42");
```

Records work too (`public record Product(@SheetKey String sku, String title) {}`). Column names follow
Jackson property names; use `@JsonProperty("Header Text")` and `@JsonIgnore` as usual. The engine does
not enforce key uniqueness.

Every repository method also exists as a not-yet-sent request — `customers.requests().findAll()` —
for sending several together with an [`APIRequestsQueue`](#request-queue-apirequestsqueue-send-when-you-say-so).

---

## Per-entity configuration: `SheetProperties`

`SheetProperties` puts every setting for one worksheet in one immutable object. An entity class
declares it as a constant, so the class itself says where its data lives and how it is fetched:

```java
public class Employee {

    public static final SheetProperties PROPERTIES = SheetProperties.builder()
            // where
            .scriptUrl("https://script.google.com/macros/s/<DEPLOYMENT_ID>/exec")
            .dbSheetUrl("https://docs.google.com/spreadsheets/d/<SPREADSHEET_ID>/edit")  // URL or bare id
            .tabName("Employees")
            .timeZone(ZoneId.of("Asia/Kolkata"))
            // how
            .retryPolicy(RetryPolicy.defaults())
            .connectTimeout(Duration.ofSeconds(10))
            .requestTimeout(Duration.ofSeconds(120))
            .accessToken(() -> credentials.getAccessToken())          // restricted deployments only
            // hooks
            .preNetworkCall(call -> log.debug("-> {} attempt {}", call.requestId(), call.attempt()))
            .postNetworkCall(result -> metrics.record("sheets.call", result.elapsed()))
            // caching (see "Response cache")
            .shallCache(true)
            .cacheStrategy(CacheStrategy.CACHE_FIRST)
            .cacheExpiry(CacheExpiry.ttlMinutes(30)
                    .or(CacheExpiry.dailyAt(LocalTime.of(1, 0), LocalTime.of(15, 0))))
            .build();

    public static Repository<Employee> repository() {
        return PROPERTIES.repository(Employee.class);
    }

    @SheetKey @JsonProperty("EmployeeId") public String employeeId;
    @JsonProperty("Name")                 public String name;
    ...
}
```

| Setting | Default | Notes |
|---|---|---|
| `scriptUrl` | — (required) | The engine's `/exec` URL. Or `transport(...)` to replace HTTP entirely (tests). |
| `dbSheetUrl` | — (required) | Full spreadsheet URL or just its id. |
| `tabName` | entity's `@SheetTable` / class name | Worksheet name. |
| `timeZone` | UTC | The spreadsheet's time zone, for date conversion. |
| `retryPolicy` | `RetryPolicy.defaults()` | See [Retries](#retries). |
| `connectTimeout` / `requestTimeout` | 10 s / 90 s | |
| `accessToken` | none | OAuth token supplier. |
| `preNetworkCall` / `postNetworkCall` | none | Any number; see [Network hooks](#network-hooks). |
| `shallCache`, `cacheStrategy`, `cacheExpiry`, `cacheFile` | off, `CACHE_FIRST`, 10 min, `~/.hibernate-sheets/cache.db` | See [Response cache](#response-cache). |
| `clock` | system clock | Clock used to decide cache freshness; inject one in tests to control expiry. |
| `queueRequests`, `queueMaxOperations` | off, 50 | See [Automatic request queue](#automatic-request-queue-time-window). For sending on demand, see [`APIRequestsQueue`](#request-queue-apirequestsqueue-send-when-you-say-so). |

What you get from it:

```java
Employee.repository().findById("E001");            // Repository<T> on the configured tab
Employee.PROPERTIES.worksheet().select().fetch();  // the fluent Worksheet API
Employee.PROPERTIES.client();                      // the underlying HibernateSheets client
```

The client is built on first use and shared by everything created from the same `SheetProperties`.
To share connection settings between entities, define a base and derive from it with `toBuilder()`
(the base is not modified):

```java
public static final SheetProperties BASE = SheetProperties.builder()
        .scriptUrl(ENDPOINT).dbSheetUrl(SPREADSHEET_ID).timeZone(ZONE).build();

// in Employee
public static final SheetProperties PROPERTIES = BASE.toBuilder().tabName("Employees").build();
// in Department
public static final SheetProperties PROPERTIES = BASE.toBuilder().tabName("Departments").build();
```

Properties derived with `toBuilder()` that change **only `tabName`** share the base's client — one
HTTP client, one cache connection, one request queue — so Employee and Department requests can be
sent together. Changing any other setting (hooks, cache, retry, …) gives the derived properties a
client of their own.

`@SheetTable` + `db.repository(Entity.class)` keep working; `SheetProperties` is the alternative for
when each entity needs its own settings.

---

## Network hooks

Pre- and post-network-call actions run around **every HTTP attempt**, retries included, in the order
they were registered. Use them for logging, metrics, tracing or refreshing state before a call.

```java
SheetProperties.builder()
        ...
        .preNetworkCall(call -> {
            // NetworkCall: requestId(), requestBody(), attempt() (1-based; > 1 on retries)
            log.info("sending {} (attempt {})", call.requestId(), call.attempt());
        })
        .postNetworkCall(result -> {
            // NetworkCallResult: call(), responseBody(), failure(), elapsed(), succeeded()
            if (!result.succeeded()) {
                log.warn("{} failed after {} ms", result.call().requestId(), result.elapsed().toMillis(),
                        result.failure());
            }
        })
        .build();
```

- Post actions run whether the attempt succeeded or failed; exactly one of `responseBody()` /
  `failure()` is set.
- A retried request keeps its `requestId`; only `attempt()` changes.
- Reads served from the cache make no network call, so the hooks do not run for them.
- The same hooks are available on `HibernateSheets.builder()` as `preNetworkCall` / `postNetworkCall`.

---

## Response cache

Replies to read-only requests can be cached in a local SQLite database and reused, so repeated
queries don't go to Apps Script (which is slow and quota-limited).

```java
SheetProperties.builder()
        ...
        .shallCache(true)
        .cacheStrategy(CacheStrategy.CACHE_FIRST)
        .cacheExpiry(CacheExpiry.ttlMinutes(30))
        .cacheFile(Path.of("/var/cache/myapp/sheets.db"))   // optional
        .build();
```

### What is cached

- Only requests made entirely of reads — SELECT and column lookups (`columns()`). The key is the
  request itself (worksheet, filters, sort, columns, limit, offset), so different queries are cached
  separately.
- The engine's reply is stored and mapped to `Row`s / POJOs exactly like a live reply, so typed
  getters, entity mapping and time-zone handling behave the same.
- Engine errors are never cached.
- The database survives restarts; one SQLite connection per file is shared inside a JVM, and several
  processes can use the same file.

### Expiry

`CacheExpiry` decides how long a cached reply stays fresh. Combine rules with `or`; the earliest wins.

```java
CacheExpiry.ttlMinutes(30)                                       // 30 minutes after caching
CacheExpiry.ttl(Duration.ofHours(2))
CacheExpiry.dailyAt(LocalTime.of(1, 0), LocalTime.of(15, 0))     // next 1:00 AM or 3:00 PM
CacheExpiry.dailyAt(ZoneId.of("Asia/Kolkata"), LocalTime.of(1, 0))
CacheExpiry.ttlMinutes(30).or(CacheExpiry.dailyAt(LocalTime.of(1, 0), LocalTime.of(15, 0)))
cachedAt -> cachedAt.plus(5, ChronoUnit.MINUTES)                  // or any lambda
```

`dailyAt` uses the machine's time zone unless you pass one. A reply cached exactly at a boundary
(e.g. 3:00 PM) expires at the next one.

### Strategies

| `CacheStrategy` | Behaviour |
|---|---|
| `CACHE_FIRST` (default) | Fresh cached reply → returned with no network call. Missing or expired → fetched and cached. |
| `NETWORK_FIRST` | Always fetches and refreshes the cache. If the network fails (transport error, timeout, HTTP 5xx/429, quota/lock errors), returns the last cached reply — **even an expired one**. Engine errors such as an unknown column are still thrown. |

### Keeping the cache correct

- Any write (insert, update, upsert, delete, clone, clear, add columns) made through a client with
  the cache enabled **invalidates every cached query on that worksheet**, even if the write fails
  (a timed-out write may still have been saved). Other worksheets are untouched.
- Clients sharing the same cache file see each other's invalidations.
- Changes made **outside** — in the Sheets UI, by Apps Script triggers, or by a client not using
  this cache file — are only picked up when entries expire. Invalidate by hand when you know the
  sheet changed:

```java
SqliteResponseCache cache = Employee.PROPERTIES.cache().orElseThrow();
cache.invalidate(spreadsheetId, "Employees");   // drop cached queries for one worksheet
cache.clear();                                   // drop everything
cache.purgeExpired(Instant.now());               // reclaim space (NETWORK_FIRST loses its fallback)
```

- If the cache itself fails (disk full, file locked, file can't be opened), the problem is logged and
  the request goes to the network as usual; caching never fails a request.

### Forcing a refresh

To get fresh data for one read without dropping anything else, force it:

```java
Optional<Customer> c = Customer.repository().requests().findById("C-42").forceRefresh().execute();
List<Order> open = orders.select().where(eq("status", "OPEN")).request(Order.class).forceRefresh().execute();
```

A forced read skips the cache, always goes to the network, and its reply **replaces** the cached one
(with a new expiry), so later normal reads get the fresh data. If the network fails it throws — no
stale fallback, even with `NETWORK_FIRST` — and the old entry is kept. Forced reads can be queued
(`.forceRefresh().queue(reqQ)`): in a queue, normal reads that are cached are still not sent. On a
write, or without a cache, `forceRefresh()` changes nothing.

Without `SheetProperties`, attach a cache to a client directly:

```java
HibernateSheets db = HibernateSheets.builder()
        .endpoint(...)
        .cache(SqliteResponseCache.open(Path.of("sheets.db")), CacheStrategy.CACHE_FIRST,
               CacheExpiry.ttlMinutes(10))
        .build();
```

---

## Request queue: `APIRequestsQueue` (send when you say so)

Every Apps Script call costs a round trip of a second or more and counts against quota. The engine
processes many operations per request, so collect what a screen or job needs, then send it all at once:

```java
APIRequestsQueue reqQ = new APIRequestsQueue();
Queued<List<Customer>> customers  = Customer.repository().requests().findAll().queue(reqQ);
Queued<List<Delivery>> deliveries = Delivery.repository().requests().findWhere(eq("Date", today)).queue(reqQ);
Queued<RowsResult> logged         = auditSheet.insert(entry).queue(reqQ);
reqQ.execute();                    // nothing was sent before this line; now one HTTP call

customers.get();  deliveries.get();  logged.get().rows();
```

Nothing is sent until `execute()` — no timers, no background threads.

### Building requests

A `SheetRequest<T>` is a request that hasn't been sent yet. It can run on its own or be queued:

```java
SheetRequest<List<Customer>> all = Customer.repository().requests().findAll();
all.execute();          // now, by itself
all.executeAsync();     // now, without waiting
all.queue(reqQ);        // later, with everything else in reqQ → Queued<List<Customer>>
```

Where they come from:

| Source | Requests |
|---|---|
| `repository.requests()` | `findAll()`, `findWhere(...)`, `findFirstWhere(...)`, `findById(id)`, `existsById(id)`, `insert(e)`, `insertAll(list)`, `save(e)`, `saveAll(list)`, `deleteById(id)`, `delete(e)` — same results as the `Repository` methods |
| any operation spec | `.request()` (its raw result), or shortcut `.queue(reqQ)` — inserts, updates, deletes, upserts, clones, selects, schema ops |
| select specs | `.request(Customer.class)` → `List<Customer>`, `.firstRequest()` / `.firstRequest(Customer.class)` → `Optional` |
| your own code | `SheetRequest.of(client, operations, mapper)`, `SheetRequest.completed(value)`, and `.map(...)` on any request |

That makes it easy to write your own fetch methods that callers can either run or queue, e.g.

```java
public static SheetRequest<List<Txn>> fetchRecent() {
    return Txn.repository().requests().findWhere(gte("Date", LocalDate.now().minusDays(7)));
}

APIRequestsQueue reqQ = new APIRequestsQueue();
Queued<List<Txn>> recent = fetchRecent().queue(reqQ);
Queued<List<Delivery>> today = DeliveriesToday.fetchAll().queue(reqQ);
reqQ.execute();
```

### What `execute()` does

- **Cache first.** With a cache configured, fresh cache hits are answered locally and not sent.
  Queued reads are cached individually; queued writes invalidate their worksheets as usual.
- **One call per client.** Everything else for the same client goes out in one HTTP call, in the
  order it was queued. Requests on different clients (different script URL or settings) get one
  call each. Entities whose `SheetProperties` were derived by `tabName` only share a client.
- **Each request succeeds or fails on its own.** If the engine rejects the combined call, it has
  written nothing; each request is then re-sent alone so only the faulty one fails. A network
  failure fails every request in that call. A request made of several operations (`saveAll`) stays
  all-or-nothing.
- **Split only when needed:** `create`, `clear` and `addColumns` go in a call of their own (the engine
  applies them immediately, so they can't be safely re-sent), and a call holds at most 100 operations
  (`new APIRequestsQueue(maxOperationsPerCall)`). Order is kept across the split.
- **Errors.** After every handle is completed, `execute()` throws `QueueExecutionException` if any
  request failed (`failures()` lists them). The successful handles can still be read; `get()` on a
  failed one throws that request's own exception.
- **Single use.** A queue can be executed once; `get()` before `execute()` throws
  `IllegalStateException`.

`APIRequestsQueue` vs `Batch`: a `Batch` is one engine request — all-or-nothing, later operations see
earlier ones, one client. A queue holds independent requests, isolates their failures, uses the
cache, and can span entities and clients.

---

## Automatic request queue (time window)

Alternatively the client can **combine requests made close together on its own**, then hand each
caller its own result. Use this when requests come from places you can't coordinate (concurrent web
requests, async code); use `APIRequestsQueue` when you know what to send together.

```java
SheetProperties.builder()
        ...
        .queueRequests(Duration.ofMillis(20))   // wait up to 20 ms for more requests
        .queueMaxOperations(50)                 // at most 50 operations per HTTP call (default)
        .build();

// or without SheetProperties
HibernateSheets.builder().endpoint(...).requestQueue(Duration.ofMillis(20), 50).build();
```

Off by default. Nothing else changes in your code: results, exceptions and caching behave as if each
request had been sent alone.

### Where the savings come from

**Async calls from one thread.** Fire several requests without waiting, then collect the results:

```java
CompletableFuture<List<Employee>> engineers = Employee.PROPERTIES.worksheet().select()
        .where(eq("Department", "Engineering")).fetchAsync(Employee.class);
CompletableFuture<List<Row>> depts = Department.PROPERTIES.worksheet().select().fetchAsync();
CompletableFuture<RowsResult> added = Employee.PROPERTIES.worksheet().insert(newHire).executeAsync();

// one HTTP call for all three
render(engineers.join(), depts.join(), added.join().rows());
```

Async methods: `executeAsync()` on every operation (insert, update, delete, upsert, clone, select…),
`fetchAsync()` / `fetchAsync(Class)` on selects, and `HibernateSheets.executeAsync(List<Operation>)` /
`executeOneAsync(...)`. They also work without a queue (each runs on a background thread).

**Concurrent callers.** Synchronous calls from different threads — e.g. web requests in a server —
are combined too: each caller blocks until its own result arrives.

**While a call is in flight**, new requests accumulate and go out together in the next call, so
batches grow automatically under load. Plain sequential synchronous code (one call after another on
one thread) gains nothing, and waits up to the window per call.

### Guarantees

- **Order.** Queued requests reach the engine in the order they were made, one HTTP call at a time.
  Inside a combined call the engine runs operations in order, so later requests see earlier writes.
- **Isolation.** Each request keeps its own outcome. If the engine rejects a combined call (e.g. one
  query names a missing column), it has written nothing; the client then re-sends each request
  alone, so only the faulty request fails. This costs extra calls only when something fails.
- **Network failures** (timeout, connection reset) fail every request in that call, exactly as they
  would fail a single request today: a timed-out write may or may not have been committed. Reads are
  retried per the [retry policy](#retries); combined calls containing writes are not.
- **Not queued:** `create`, `clear` and `addColumns` (the engine applies them immediately, so they
  could not be safely re-sent), requests larger than `queueMaxOperations`, and calls made from a
  network hook. These go straight to the network.
- **Explicit batches** (`db.batch()`) are queued as one request and stay all-or-nothing.
- **Cache.** Cache hits are answered without queueing; each queued read is cached as its own entry.
- **Threads.** HTTP calls run on a daemon thread named `hibernate-sheets-queue`, so network hooks run
  there; `CompletableFuture` callbacks run on a separate pool, never on the queue thread.

Requests only combine when they go through the same client — the same `HibernateSheets`, or
`SheetProperties` derived by `tabName` only (see [above](#per-entity-configuration-sheetproperties)).

---

## Deployment, auth and errors

- **Endpoint**: the `/exec` URL of the web app deployment (your `deploy.sh` prints it).
  `HttpTransport` follows Apps Script's `302` to `script.googleusercontent.com` with a GET, and does
  not forward `Authorization` to that other host.
- **Access**: with *Who has access: Anyone*, no credentials are needed. For deployments restricted
  to Google accounts, supply an OAuth token: `.accessToken(() -> credentials.getAccessToken())`.
- **An HTML page instead of JSON** (sign-in page, "Script function not found") becomes a
  `TransportException` whose message names the page title and the usual causes.
- **`ServerException`** carries the engine's message, JS exception type, Apps Script stack trace and
  debug entries.
- **Custom transport**: implement `Transport` (one method) for your own HTTP stack or to stub the
  engine in tests (`FakeTransport` in `src/test` is an example).

### Retries

`RetryPolicy.defaults()` retries 3 times with back-off, **only for read-only requests** (SELECT,
GET_COLUMNS), and only for retryable failures: I/O errors, timeouts, HTTP 408/429/5xx, and engine
errors like *"too many times in a short time"* or lock timeouts. A daily-quota error is never retried.
Writes are not retried because a timed-out write may already be committed; opt in with
`RetryPolicy.defaults().retryingWrites(true)` only if duplicates are acceptable.

---

## Engine issues

These are in the Apps Script engine (`appscript/` / `server-appscript/`), not the SDK.
**Redeploy the engine** for the fixes to take effect on a live deployment.

### Fixed

| Issue | Fix | Regression test |
|---|---|---|
| **EQUALS with a JSON number never matched** (`String(cell) === 5`). | `CompiledEqualsPredicate` compares against `String(expectedValue)`. The SDK still sends text, so it works with old deployments too. | `SelectQueryIT.rawNumericEquals` |
| **`IN` / `BETWEEN` rejected on SELECT** (*Unsupported predicate*). | `RequestValidator.validatePredicate` accepts them and checks the column and values. | `SelectQueryIT.inOnSelect`, `betweenOnSelect` |
| **Deleted rows stayed visible within a batch**, and deleting the same row twice also deleted the next row. | `ExecutionExecutor` skips rows already deleted in the request; `commitDeleted` deletes each sheet row once, bottom-up. | `DeleteIT.deletedRowInvisibleLaterInBatch`, `doubleDeleteInBatchKeepsNeighbour` |
| **No locking** — concurrent requests appending at `getLastRow() + 1` overwrote each other. | `SheetEngine.handlePost` takes `LockService.getScriptLock()` for any request that writes (waits up to `EngineConfig.lockTimeoutMillis`, 30 s) and releases it in `finally`. Read-only requests don't wait. | `InsertIT.concurrentInsertsAllLand` |

Notes on the lock:

- It is a **script** lock, so writes to *different* spreadsheets through the same deployment are
  serialized too. Apps Script has no per-spreadsheet lock for standalone scripts.
- If the wait times out, the engine answers `success:false` with *Lock timeout…*; the SDK treats it
  as retryable. Nothing ran, but writes are still only retried when you opt in with
  `retryingWrites(true)`.
- Reads don't take the lock, so a read that overlaps a write's commit can see it half-applied.

### Open

- **No authentication.** Anyone holding the URL can read and write every spreadsheet the script can
  reach, by passing any `spreadsheetId`. Consider a shared secret checked against Script Properties
  plus an allow-list of spreadsheet ids. `doPost` cannot read request headers, so the secret has to
  travel in the JSON body; a custom `Transport` can add it before sending.

---

## Building and testing

```bash
mvn test       # 162 unit tests, no network: request contract, response parsing,
               # batching, retries, entity mapping, SheetProperties and network hooks,
               # cache expiry/strategies/invalidation against a temp SQLite file,
               # APIRequestsQueue and the automatic queue (combining, ordering,
               # isolation, cache, threads),
               # and the HTTP redirect flow against an in-process server
mvn package
mvn test -Dtest='io.github.prasunmondal.hibernatesheets.cachingTests.*'   # just the caching suite
```

The caching suite (`cachingTests`, 87 tests) runs the real SDK and SQLite cache against an
in-memory fake engine (`FakeSheetsEngine`) that holds real rows and can be edited "behind the SDK's
back", go offline, or reject requests — so tests check data freshness, not only call counts:

| Class | Covers |
|---|---|
| `CacheModeTest` | on/off/default, cached result = network result (types, dates, blanks), what makes queries distinct, shared/separate cache files, restart persistence, hooks skipped on hits, stale-while-fresh |
| `CacheExpiryBehaviourTest` | TTL boundary, default 10 min, daily 1:00 AM / 3:00 PM, midnight crossover, TTL-or-daily, custom rules, CACHE_FIRST vs NETWORK_FIRST after expiry, `purgeExpired` |
| `CacheForceRefreshTest` | `forceRefresh()` bypass + update + new expiry, failure keeps the old entry, async/map/queue, manual `invalidate` / `clear`, NETWORK_FIRST |
| `CacheInvalidationTest` | every write kind (17 variants incl. batch, async, queued), scope (worksheet / spreadsheet), failed writes, writes from other clients, read-your-own-writes, read/write order in a queue |
| `CacheBatchingTest` | queued reads cached one by one, only misses sent, all-hit queues, failures not cached, NETWORK_FIRST offline queue, `Batch` as one entry, automatic queue, shared clients |
| `CacheRobustnessTest` | engine errors not cached, closed or unusable cache file, 8 concurrent readers, 3,000-row replies, unicode, per-client time zones |

Integration tests (`*IT`) hit a live deployment and are run explicitly, e.g. `mvn test -Dtest='*IT'`.
`Employee` in the integration tests is the reference example of an entity with its own
`SheetProperties`, network hooks, caching and request queueing.
