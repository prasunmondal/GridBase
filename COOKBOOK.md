# GridBase cookbook

Task-oriented recipes for the GridBase Java SDK (Google Sheets as a database through the
GridBase Apps Script engine). Each recipe is self-contained; the [README](README.md) has the
reference material behind them.

All snippets assume:

```java
import io.github.prasunmondal.gridbase.*;
import io.github.prasunmondal.gridbase.cache.*;
import io.github.prasunmondal.gridbase.mapping.*;
import io.github.prasunmondal.gridbase.query.Sort;
import io.github.prasunmondal.gridbase.result.*;
import io.github.prasunmondal.gridbase.exception.*;
import static io.github.prasunmondal.gridbase.query.Filters.*;
```

**Contents**

1. [Setup](#1-setup)
2. [Connecting](#2-connecting)
3. [Reading rows](#3-reading-rows)
4. [Working with values and types](#4-working-with-values-and-types)
5. [Writing rows](#5-writing-rows)
6. [Entities and repositories](#6-entities-and-repositories)
7. [Worksheets and columns](#7-worksheets-and-columns)
8. [Several changes as one unit: batches](#8-several-changes-as-one-unit-batches)
9. [Fewer HTTP calls: request queues](#9-fewer-http-calls-request-queues)
10. [Async code](#10-async-code)
11. [Caching](#11-caching)
12. [Errors, retries and timeouts](#12-errors-retries-and-timeouts)
13. [Logging, metrics and hooks](#13-logging-metrics-and-hooks)
14. [Android](#14-android)
15. [Testing your code](#15-testing-your-code)
16. [Pitfalls](#16-pitfalls)

---

## 1. Setup

### Add the dependency

```kotlin
// Gradle (Maven Central)
implementation("io.github.prasunmondal:gridbase:0.2.0")
implementation("io.github.prasunmondal:gridbase-android:0.2.0")   // Android apps only

// Gradle (JitPack)
implementation("com.github.prasunmondal.GridBase:gridbase:<tag>")
implementation("com.github.prasunmondal.GridBase:gridbase-android:<tag>")
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.prasunmondal</groupId>
  <artifactId>gridbase</artifactId>
  <version>0.2.0</version>
</dependency>
```

### Deploy the engine

1. Create an Apps Script project and copy in the sources from `server-appscript/`
   (or push them with `clasp`).
2. **Deploy → New deployment → Web app**: *Execute as: Me*; *Who has access*: *Anyone* (or restricted,
   see [Restricted deployments](#call-a-restricted-deployment)).
3. Copy the `/exec` URL. That is your `scriptUrl` / `endpoint`.
4. Every engine change needs a **new deployment version** before clients see it.

The script must be able to open the spreadsheet: share it with the account the script runs as.

---

## 2. Connecting

### The quickest client

```java
GridBase db = GridBase.builder()
        .endpoint("https://script.google.com/macros/s/<DEPLOYMENT_ID>/exec")
        .defaultSpreadsheetId("<SPREADSHEET_ID>")
        .timeZone(ZoneId.of("Asia/Kolkata"))      // the spreadsheet's time zone (File → Settings)
        .build();

Worksheet orders = db.worksheet("Orders");
```

Build one client and reuse it; it is thread-safe.

### Keep all settings for an entity on the entity

```java
public class Customer {
    public static final SheetProperties PROPERTIES = SheetProperties.builder()
            .scriptUrl("https://script.google.com/macros/s/<DEPLOYMENT_ID>/exec")
            .dbSheetUrl("https://docs.google.com/spreadsheets/d/<SPREADSHEET_ID>/edit") // URL or bare id
            .tabName("Customers")
            .timeZone(ZoneId.of("Asia/Kolkata"))
            .build();

    public static Repository<Customer> repository() {
        return PROPERTIES.repository(Customer.class);
    }

    @SheetKey public String id;
    public String name;
}

Customer.repository().findById("C-42");
Customer.PROPERTIES.worksheet().select().fetch();   // fluent API on the same tab
```

### Share connection settings between entities

```java
public final class Sheets {
    public static final SheetProperties BASE = SheetProperties.builder()
            .scriptUrl(ENDPOINT)
            .dbSheetUrl(SPREADSHEET_ID)
            .timeZone(ZoneId.of("Asia/Kolkata"))
            .shallCache(true)
            .build();
}

// Customer.java
public static final SheetProperties PROPERTIES = Sheets.BASE.toBuilder().tabName("Customers").build();
// Order.java
public static final SheetProperties PROPERTIES = Sheets.BASE.toBuilder().tabName("Orders").build();
```

Deriving with **only** `tabName` changed shares one client, which means one cache and one request
queue, so Customer and Order requests can go out in the same HTTP call. Changing anything else gives
the derived properties their own client.

### Several spreadsheets through one engine

```java
Worksheet live    = db.worksheet("Orders");                            // default spreadsheet
Worksheet archive = db.worksheet("<ARCHIVE_SPREADSHEET_ID>", "Orders"); // explicit spreadsheet
```

For entities, set `@SheetTable(spreadsheetId = "...")` or give the entity its own `SheetProperties`.

### Call a restricted deployment

If the web app is not *Anyone*, send an OAuth access token:

```java
SheetProperties.builder()
        ...
        .accessToken(() -> credentials.refreshIfExpired().getAccessToken().getTokenValue())
        .build();
```

The supplier is called for every HTTP attempt, so it can refresh tokens.

---

## 3. Reading rows

### All rows

```java
List<Row> rows = orders.select().fetch();
```

### Rows matching conditions

All conditions passed to `where` are **AND**ed.

```java
List<Row> bigOpen = orders.select()
        .where(eq("status", "OPEN"), gte("qty", 5))
        .fetch();
```

| Need | Filter |
|---|---|
| equal / not equal | `eq("status", "OPEN")`, `ne("status", "CANCELLED")` |
| numeric / date comparison | `gt`, `gte`, `lt`, `lte`, `between("qty", 1, 10)` |
| one of several values | `in("status", "OPEN", "HOLD")` or `in("status", listOfStatuses)` |
| text search | `contains("notes", "urgent")`, `startsWith("sku", "P-")`, `endsWith("email", "@acme.com")` |
| empty / not empty cell | `isNull("shippedAt")`, `isNotNull("shippedAt")` (also `eq(col, null)` / `ne(col, null)`) |

### "OR" across columns

The engine has no OR. Within one column use `in(...)`. Across columns, run the queries together in
one HTTP call and merge:

```java
APIRequestsQueue q = new APIRequestsQueue();
Queued<List<Order>> urgent  = orders.select().where(eq("priority", "HIGH")).request(Order.class).queue(q);
Queued<List<Order>> overdue = orders.select().where(lt("dueDate", LocalDate.now())).request(Order.class).queue(q);
q.execute();

Map<String, Order> byId = new LinkedHashMap<>();
urgent.get().forEach(o -> byId.put(o.id, o));
overdue.get().forEach(o -> byId.put(o.id, o));
List<Order> urgentOrOverdue = new ArrayList<>(byId.values());
```

### Map rows straight to your classes

```java
List<Order> open = orders.select().where(eq("status", "OPEN")).fetch(Order.class);
```

Any Jackson-mappable class or record works; column headers map to property names
(`@JsonProperty("Header Text")` when they differ).

### One row

```java
Optional<Row> row     = orders.select().where(eq("id", "A1")).fetchFirst();
Optional<Order> order = orders.select().where(eq("id", "A1")).fetchFirst(Order.class);
```

### Only some columns

```java
List<Row> slim = orders.select("id", "status").fetch();
```

### Sorting

```java
orders.select().orderBy("createdAt", Sort.Direction.DESC).fetch();
orders.select().orderBy(Sort.asc("customer"), Sort.desc("createdAt")).fetch();   // several keys
```

### Paging

```java
int page = 2, size = 50;
List<Row> rows = orders.select()
        .orderBy("createdAt")          // always sort when paging, so pages are stable
        .offset(page * size)
        .limit(size)
        .fetch();
```

### Latest N rows

```java
List<Row> last10 = orders.select().orderBy("createdAt", Sort.Direction.DESC).limit(10).fetch();
```

### Rows in a date range

```java
List<Order> september = orders.select()
        .where(between("orderDate", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
        .fetch(Order.class);
```

Range filters send `java.time` values as epoch millis, so they compare correctly against date cells.

### Does a row exist?

```java
boolean exists = orders.select("id").where(eq("id", "A1")).limit(1).fetchFirst().isPresent();
// with a repository
boolean known = Customer.repository().existsById("C-42");
```

---

## 4. Working with values and types

### Typed getters

```java
Row r = orders.select().where(eq("id", "A1")).fetchFirst().orElseThrow();

String     id      = r.getString("id");
Integer    qty     = r.getInteger("qty");
BigDecimal price   = r.getBigDecimal("price");       // money: avoid getDouble
Boolean    paid    = r.getBoolean("paid");
LocalDate  ordered = r.getLocalDate("orderDate");    // in the client's timeZone
Instant    at      = r.getInstant("createdAt");
```

Empty cells come back as `""` from `r.get(col)` and as `null` from typed getters. Check with
`r.isBlank(col)`.

### Dates show up a day early

Sheets sends dates as UTC instants. Set the client's `timeZone(...)` to the spreadsheet's time zone
and read with `getLocalDate` / `LocalDate` fields; they convert in that zone. Without it (UTC), a
30 Sep date in Asia/Kolkata reads as 29 Sep.

### Whole row as a map or object

```java
Map<String, Object> values = r.asMap();
Order o = r.as(Order.class);
Set<String> headers = r.columns();
```

### Write dates and times

Pass `LocalDate`, `LocalDateTime` or `Instant`. They are written as ISO-8601 text, which Sheets
recognises as dates:

```java
orders.update().set("shippedAt", LocalDateTime.now()).where(eq("id", "A1")).execute();
```

---

## 5. Writing rows

Nothing is sent until `execute()`.

### Insert one row

```java
orders.insert(Map.of("id", "A9", "qty", 4, "status", "OPEN")).execute();
orders.insert(new Order("A9", 4, "OPEN")).execute();            // POJO or record
```

### Insert many rows in one call

```java
RowsResult added = orders.insertAll(newOrders).execute();
int n = added.count();
```

### Update matching rows

```java
int changed = orders.update()
        .set("status", "SHIPPED")
        .set("shippedAt", LocalDate.now())
        .where(eq("id", "A9"))
        .execute()
        .count();
```

`update()` and `delete()` **require** `where(...)`. To touch every row on purpose, call `.all()`.

### Update several columns from an object

```java
orders.update().set(new StatusChange("SHIPPED", LocalDate.now())).where(eq("id", "A9")).execute();
```

Each property of the object (or `Map` entry) becomes a `set(column, value)`. A `null` property
**clears** that cell, so use a small object with only the fields you mean to change.

### Append to a text column (audit trail)

```java
orders.update()
        .append("log", " | shipped " + LocalDate.now())
        .where(eq("id", "A9"))
        .execute();
// prepend("log", "...") adds to the front instead
```

### Update every row

```java
orders.update().set("archived", true).all().execute();
```

### Delete rows

```java
orders.delete().where(eq("status", "CANCELLED")).execute();

// only the 10 oldest
orders.delete().where(eq("status", "CANCELLED")).orderBy("createdAt").limit(10).execute();
```

### Insert or update by key (upsert)

```java
orders.upsert()
        .key("id", "A9")          // the filter AND the value written if a new row is inserted
        .set("qty", 6)
        .set("status", "OPEN")
        .execute();
```

Use `key(...)`, not `where(eq(...))`. The engine doesn't copy `where` values into an inserted row, so
the key column would be blank.

### Duplicate rows with changes

```java
orders.cloneRows()
        .where(eq("id", "A9"))
        .set("id", "A10")
        .set("status", "DRAFT")
        .execute();
```

### Replace a whole sheet's contents

```java
Customer.repository().saveAll(freshList);   // clears the rows (header kept), then inserts freshList
```

This is **not atomic**: if the insert fails, the sheet stays empty. For important data, upsert
instead (`upsertAll`), or keep a backup sheet.

---

## 6. Entities and repositories

### Define an entity

```java
@SheetTable(worksheet = "Customers")             // omit to use the class name
public class Customer {
    @SheetKey
    @JsonProperty("Customer ID") public String id;   // header text differs from the field name
    @JsonProperty("Name")        public String name;
    @JsonProperty("Credit Limit") public Integer creditLimit;
    @JsonProperty("Since")       public LocalDate since;
    @JsonIgnore                  public transient String uiState;   // not a column
}

Repository<Customer> customers = db.repository(Customer.class);
```

Records work too:

```java
@SheetTable(worksheet = "Products")
public record Product(@SheetKey String sku, String title, BigDecimal price) {}
```

### CRUD

```java
customers.insert(c);                                  // append (no key check)
customers.upsert(c);                                  // update the row with c.id, or append it
Optional<Customer> one = customers.findById("C-42");
List<Customer> all     = customers.findAll();
List<Customer> vip     = customers.findWhere(gt("Credit Limit", 10_000));
Optional<Customer> any = customers.findFirstWhere(eq("Name", "Asha"));
customers.deleteById("C-42");
customers.delete(c);                                  // by c's key
```

Filters use **column headers** (`"Credit Limit"`), not Java field names.

### Bulk writes in one request

```java
customers.insertAll(newCustomers);    // one INSERT
customers.upsertAll(changed);         // one request, all-or-nothing
```

### Which write method?

| You want | Use |
|---|---|
| Add rows, duplicates OK | `insert` / `insertAll` |
| Create or update by key | `upsert` / `upsertAll` |
| Sheet should contain exactly this list | `save` / `saveAll` (not atomic, see [above](#replace-a-whole-sheets-contents)) |
| Remove everything | `deleteAll()` |

The engine does not enforce key uniqueness. Use `upsert` if duplicate keys would be a problem.

### Go from a repository to the fluent API

```java
customers.worksheet().update().set("Credit Limit", 0).where(lt("Since", LocalDate.of(2020, 1, 1))).execute();
```

---

## 7. Worksheets and columns

### Create a worksheet with headers

```java
Worksheet audit = db.worksheet("Audit");
audit.create().execute();
audit.addColumns("at", "user", "action", "details").execute();
```

### Create it only if missing

`create()` fails if the worksheet exists:

```java
static void ensureWorksheet(Worksheet ws, String... columns) {
    try {
        ws.create().execute();
    } catch (ServerException e) {
        if (!e.getServerMessage().contains("already exists")) throw e;
    }
    ws.addColumns(columns).skipExisting().execute();
}
```

### Add columns without failing on existing ones

```java
AddColumnsResult r = orders.addColumns("discount", "notes").skipExisting().execute();
r.columns();          // added
r.skippedColumns();   // already there
```

### Read the header row

```java
List<String> headers = orders.columns().fetch();
```

### Empty a sheet but keep its headers

```java
ClearResult r = orders.clear().execute();
int removed = r.rowsCleared();
```

`create`, `addColumns` and `clear` take effect **immediately**, even inside a batch, and are not
rolled back.

---

## 8. Several changes as one unit: batches

### All-or-nothing row changes

```java
Worksheet stock = db.worksheet("Stock");
Worksheet moves = db.worksheet("Moves");

Batch batch = db.batch();
batch.add(stock.update().set("qty", 7).where(eq("sku", "P1")));
batch.add(stock.update().set("qty", 13).where(eq("sku", "P2")));
batch.add(moves.insert(Map.of("from", "P1", "to", "P2", "qty", 3, "at", Instant.now())));
batch.execute();     // one request; if any operation fails, no row changes are written
```

### Read your own write in the same request

```java
Batch batch = db.batch();
Ref<RowsResult> inserted = batch.add(orders.insert(newOrder));
Ref<RowsResult> open     = batch.add(orders.select().where(eq("status", "OPEN")));
batch.execute();

open.get().rows();   // includes newOrder: later operations see earlier ones
```

The final write to the sheet takes several Sheets calls. An Apps Script timeout in the middle can
leave a partial write, so a batch groups changes but is not a real database transaction.

---

## 9. Fewer HTTP calls: request queues

Each Apps Script call takes a second or more and counts against quota. Combine calls.

### Load everything a screen needs in one call

```java
APIRequestsQueue q = new APIRequestsQueue();
Queued<List<Customer>> customers = Customer.repository().requests().findAll().queue(q);
Queued<List<Order>>    open      = Order.repository().requests().findWhere(eq("status", "OPEN")).queue(q);
Queued<List<String>>   headers   = Order.PROPERTIES.worksheet().columns().request()
                                        .map(ColumnsResult::columns).queue(q);
q.execute();   // nothing was sent before this line

render(customers.get(), open.get(), headers.get());
```

Requests that the cache can answer are not sent at all.

### Write data-access methods callers can run or queue

```java
public final class Orders {
    public static SheetRequest<List<Order>> openFor(String customerId) {
        return Order.repository().requests().findWhere(eq("customer", customerId), eq("status", "OPEN"));
    }
}

List<Order> now = Orders.openFor("C-7").execute();            // alone
Queued<List<Order>> later = Orders.openFor("C-7").queue(q);   // with others
```

Transform a request's result without sending it: `request.map(list -> list.size())`.

### Handle partial failure

Each queued request succeeds or fails on its own:

```java
try {
    q.execute();
} catch (QueueExecutionException e) {
    log.warn("{} of {} requests failed", e.failures().size(), e.requestCount());
}
if (!open.failed()) {
    show(open.get());
}
```

### Combine requests from code you don't control

When requests come from many threads (web handlers, async jobs), let the client merge whatever
arrives within a short window:

```java
SheetProperties.builder()
        ...
        .queueRequests(Duration.ofMillis(20))
        .queueMaxOperations(50)
        .build();
```

Callers don't change. Requests are sent one HTTP call at a time and in order, which also avoids
write races in the engine.

**Queue, batch or neither?**

| Situation | Use |
|---|---|
| Several changes that must all succeed or all fail | `Batch` |
| Independent reads/writes you know about up front (a screen, a job) | `APIRequestsQueue` |
| Concurrent callers you can't coordinate | `queueRequests(window)` |

---

## 10. Async code

```java
CompletableFuture<List<Customer>> customers = Customer.PROPERTIES.worksheet().select().fetchAsync(Customer.class);
CompletableFuture<List<Row>> orders         = Order.PROPERTIES.worksheet().select().fetchAsync();
CompletableFuture<RowsResult> logged        = auditSheet.insert(entry).executeAsync();

CompletableFuture.allOf(customers, orders, logged).join();
```

Any request has `executeAsync()`:
`Customer.repository().requests().findById("C-42").executeAsync()`. With `queueRequests(...)`
enabled, async calls made close together share one HTTP call. Callbacks run on a library pool
thread, so on Android switch back to the main thread before touching views.

---

## 11. Caching

### Turn it on

```java
SheetProperties.builder()
        ...
        .shallCache(true)
        .cacheExpiry(CacheExpiry.ttlMinutes(30))
        .build();
```

Reads (`select`, `columns`) are cached. Any write through a cached client clears the cached reads of
that worksheet.

### Refresh at fixed times of day

```java
.cacheExpiry(CacheExpiry.dailyAt(ZoneId.of("Asia/Kolkata"), LocalTime.of(6, 0), LocalTime.of(14, 0)))
// whichever comes first: 30 min after caching, or 6 AM
.cacheExpiry(CacheExpiry.ttlMinutes(30).or(CacheExpiry.dailyAt(LocalTime.of(6, 0))))
```

### Work offline with the last known data

```java
.shallCache(true)
.cacheStrategy(CacheStrategy.NETWORK_FIRST)
```

Every read goes to the network. If the network fails (no connection, timeout, 5xx, quota), the
last cached reply is returned, **even an expired one**.

### Get fresh data for one read

```java
Optional<Customer> c = Customer.repository().requests().findById("C-42").forceRefresh().execute();
List<Order> open = orders.select().where(eq("status", "OPEN")).request(Order.class).forceRefresh().execute();
```

### Someone edited the sheet by hand

Edits made outside this client (Sheets UI, triggers, other apps) only show up after expiry. Drop the
stale entries yourself:

```java
ResponseCache cache = Customer.PROPERTIES.cache().orElseThrow();
cache.invalidate(spreadsheetId, "Customers");   // one worksheet
cache.clear();                                   // everything
```

### Choose where the cache lives

```java
.cacheBackend(CacheBackend.AUTO)        // default: SQLite on JVM, Android SQLite on Android
.cacheBackend(CacheBackend.SQLITE)      // sqlite-jdbc file; several processes may share it
.cacheBackend(CacheBackend.ANDROID_SQLITE)  // Android's own SQLite (needs gridbase-android)
.cacheBackend(CacheBackend.JOURNAL)     // pure Java file, all entries in memory, fastest reads
.cacheBackend(CacheBackend.MEMORY)      // nothing on disk
.cacheFile(Path.of("/var/cache/myapp/sheets.db"))
```

### Plug in your own store

Implement `ResponseCache` (get, put, invalidate, purgeExpired, clear, close), e.g. on Room or Redis,
and pass it in:

```java
.shallCache(true)
.cacheStore(new MyRedisResponseCache(redis))
```

Each entry is tagged with the worksheets it read. `invalidate(spreadsheetId, worksheet)` must drop
every entry with that tag. The README has a Room example.

### Keep the cache file small

```java
cache.purgeExpired(Instant.now());   // e.g. once a day; NETWORK_FIRST loses those fallbacks
```

### Without SheetProperties

```java
GridBase db = GridBase.builder()
        .endpoint(ENDPOINT)
        .cache(CacheBackend.AUTO.open(Path.of("sheets.db")), CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10))
        .build();
```

Cache problems (disk full, locked file) are logged and the request goes to the network, so a broken
cache never breaks your app.

---

## 12. Errors, retries and timeouts

### Tell error kinds apart

```java
try {
    orders.update().set("stauts", "X").where(eq("id", "A1")).execute();
} catch (ServerException e) {
    // The engine rejected the request: unknown column, bad filter, sheet missing, quota...
    log.error("engine: {} ({})", e.getServerMessage(), e.getExceptionType());
} catch (TransportException e) {
    // No usable reply: network down, timeout, HTTP error, HTML page instead of JSON
    log.error("transport: HTTP {}", e.getHttpStatus(), e);
}
```

Both extend `GridBaseException`. `e.isRetryable()` says whether trying again could help.

### Tune retries

```java
.retryPolicy(RetryPolicy.of(5, Duration.ofMillis(500))     // 5 attempts, 0.5 s first back-off
        .withMultiplier(2.0)
        .withMaxDelay(Duration.ofSeconds(8)))
.retryPolicy(RetryPolicy.none())                            // fail fast
```

Only reads are retried by default. A timed-out write may already be saved, so retrying it could
duplicate rows. Turn write retries on only for idempotent writes (e.g. upserts by key):

```java
.retryPolicy(RetryPolicy.defaults().retryingWrites(true))
```

Daily-quota errors are never retried.

### Long-running requests

Large sheets or big batches can take tens of seconds:

```java
.connectTimeout(Duration.ofSeconds(10))
.requestTimeout(Duration.ofSeconds(180))
```

Apps Script stops a single execution after about 6 minutes. Split large jobs into several requests.

### "I got an HTML page"

A `TransportException` mentioning a page title usually means one of these: wrong or old deployment
URL, *Who has access* isn't *Anyone* and no `accessToken` was given, or the script owner must
re-authorize the script. Open the `/exec` URL in a browser to see the page.

---

## 13. Logging, metrics and hooks

### Log every request in red

```java
.logNetworkCalls()
```

Each HTTP attempt logs `GridBase >> <id> attempt n | ops | body=...` and its reply logs
`GridBase << <id> OK in 1840 ms | 5.2 KB` (or `FAILED` / `ENGINE ERROR` with the reason). They are logged
at `SEVERE` under the tag `GridBaseNetwork`, so Logcat shows them red (`Log.e`) and you can filter on
`tag:GridBaseNetwork`; desktop IDE consoles show them red on stderr. Shorten or silence bodies with
`.logNetworkCalls(NetworkLogger.defaults().maxBodyChars(0))`. Bodies contain your data, so prefer debug
builds:

```java
.logNetworkCalls(BuildConfig.DEBUG ? NetworkLogger.defaults() : NetworkLogger.defaults().level(Level.OFF))
```

### Log every call

```java
.preNetworkCall(call -> log.info("-> {} attempt {}", call.requestId(), call.attempt()))
.postNetworkCall(r -> log.info("<- {} {} in {} ms", r.call().requestId(),
        r.succeeded() ? "ok" : "FAILED", r.elapsed().toMillis()))
```

Hooks run around every HTTP attempt, including retries. Cache hits make no call and don't trigger them.

### Count calls (e.g. to watch quota)

```java
AtomicInteger calls = new AtomicInteger();
.preNetworkCall(call -> calls.incrementAndGet())
```

### See the SDK's own logs

The SDK logs through `java.util.logging` under `io.github.prasunmondal.gridbase.*`. Cache
hits and request combining are logged at `FINE`, and cache problems at `WARNING`.

```java
Logger l = Logger.getLogger("io.github.prasunmondal.gridbase");
l.setLevel(Level.FINE);
ConsoleHandler h = new ConsoleHandler();
h.setLevel(Level.FINE);
l.addHandler(h);
```

---

## 14. Android

### Dependencies

```kotlin
dependencies {
    implementation("io.github.prasunmondal:gridbase:0.2.0") {
        exclude(group = "org.xerial", module = "sqlite-jdbc")   // not needed on Android
    }
    implementation("io.github.prasunmondal:gridbase-android:0.2.0")
}
```

Also add `<uses-permission android:name="android.permission.INTERNET"/>` to the manifest.

### Configure once, with a persistent cache

```java
public final class Sheets {
    private static volatile SheetProperties base;

    public static SheetProperties base(Context context) {
        if (base == null) {
            synchronized (Sheets.class) {
                if (base == null) {
                    base = SheetProperties.builder()
                            .scriptUrl(BuildConfig.SHEETS_URL)
                            .dbSheetUrl(BuildConfig.SPREADSHEET_ID)
                            .timeZone(ZoneId.of("Asia/Kolkata"))
                            .shallCache(true)
                            .cacheStrategy(CacheStrategy.NETWORK_FIRST)        // usable offline
                            .cacheFile(new File(context.getFilesDir(), "sheets.db").toPath())
                            .build();
                }
            }
        }
        return base;
    }
}
```

With `gridbase-android` present, `CacheBackend.AUTO` uses Android's own SQLite. Nothing native is
shipped, so you won't see `dlopen failed: libsqlitejdbc.so`. `getFilesDir()` survives the system
clearing app caches; the default (`getCacheDir()`) doesn't.

### Never call on the main thread

```kotlin
// Kotlin + coroutines
val customers = withContext(Dispatchers.IO) { Customer.repository().findAll() }
```

```java
// Java
executor.execute(() -> {
    List<Customer> list = Customer.repository().findAll();
    mainHandler.post(() -> adapter.submitList(list));
});
```

The same applies to `fetchAsync` callbacks, which run on a background pool thread.

### Avoid `Map.of` below API 30

`Map.of` / `List.of` need API 30. On older devices use POJOs/records or `HashMap` for inserts.

### R8 / ProGuard

`gridbase-android` ships its own keep rule. Keep your entity classes, since Jackson maps them by name:

```
-keep class com.example.app.model.** { *; }
```

---

## 15. Testing your code

### No network: fake the transport

```java
Transport fake = requestJson -> """
        {"success":true,"requestId":"t","results":[
          {"operationId":"op-1","rowCount":1,"rows":[{"id":"A1","qty":3}]}]}""";

GridBase db = GridBase.builder()
        .transport(fake)
        .defaultSpreadsheetId("TEST")
        .build();

assertEquals(3, db.worksheet("Orders").select().fetchFirst().orElseThrow().getInteger("qty"));
```

`SheetProperties.builder().transport(fake)` works the same way, with no `scriptUrl` needed. Inspect
`requestJson` to assert what your code sent.

### Control cache expiry without sleeping

```java
MutableClock clock = new MutableClock(Instant.parse("2026-10-01T10:00:00Z"));   // your own Clock subclass
SheetProperties p = SheetProperties.builder()...shallCache(true).clock(clock).build();

clock.advance(Duration.ofMinutes(31));   // entries cached 30 min ago are now stale
```

Use `CacheBackend.MEMORY` in tests to avoid files.

### Run the real engine locally

The repository ships a Node emulator that runs the actual engine code against in-memory sheets:

```bash
node gridbase/src/test/emulator/engine-emulator.js server-appscript   # listens on :8765
```

Point your client at `http://127.0.0.1:8765/macros/s/LOCAL/exec`.

---

## 16. Pitfalls

| Symptom | Cause | Fix |
|---|---|---|
| `eq("price", "12.50")` matches nothing | Compared as text `"12.5"` | Pass numbers as numbers: `eq("price", 12.5)` |
| Dates are one day off | Client time zone is UTC | `.timeZone(<spreadsheet zone>)` |
| Upserted row has a blank key | Key given only in `where` | Use `.key(column, value)` |
| Update/delete throws about missing `where` | Safety check | Add `where(...)` or call `.all()` |
| Sheet empty after `saveAll` failed | `save*` clears first, not atomic | Use `upsertAll`, or validate before saving |
| Old data after editing the sheet by hand | Cache only knows its own writes | `cache.invalidate(...)`, shorter expiry or `forceRefresh()` |
| `Worksheet already exists` | `create()` isn't idempotent | See [Create it only if missing](#create-it-only-if-missing) |
| Lost rows with many concurrent writers | Old engine without locking | Redeploy the current engine (it locks writes) |
| Filters on field names find nothing | Filters use **column headers** | `eq("Credit Limit", ...)`, not `eq("creditLimit", ...)` |
| `NetworkOnMainThreadException` | Android main thread | Run on `Dispatchers.IO` / an executor |
| Changes to the engine have no effect | Deployment not updated | Deploy a new version of the web app |
