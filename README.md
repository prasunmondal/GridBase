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

Dependencies: `jackson-databind` and `jackson-datatype-jsr310` only. HTTP uses the JDK's
`java.net.http.HttpClient`.

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
  │  Transport (HttpTransport default)  ← POST /exec, follow Apps Script 302, auth header
  ▼
ResponseParser                          ← success → typed results; success:false → ServerException
```

| Package | Contents |
|---|---|
| `hibernatesheets` | `HibernateSheets` (client), `Worksheet`, `Batch`/`Ref`/`BatchResult`, `RetryPolicy` |
| `.query` | `Filters` (static factory), `Filter`, `Sort` |
| `.spec` | Operation builders and the `Operation` record |
| `.result` | `Row`, `RowsResult`, `ColumnsResult`, `AddColumnsResult`, `ClearResult`, `WorksheetCreated` |
| `.mapping` | `@SheetTable`, `@SheetKey`, `Repository<T>` |
| `.transport` | `Transport` interface, `HttpTransport` |
| `.exception` | `HibernateSheetsException` → `ServerException`, `TransportException` |

---

## API tour

```java
import static io.github.prasunmondal.gsheetdb.query.Filters.*;

// SELECT
List<Row> rows = orders.select().where(eq("customer", "C-7")).fetch();
        List<Order> typed = orders.select().where(in("status", "OPEN", "HOLD")).fetch(Order.class);
        Optional<Row> first = orders.select("id", "qty").where(eq("id", "A1")).fetchFirst();

// INSERT (Map, POJO or record; one or many rows, one sheet write)
orders.

        insert(Map.of("id", "A9","qty",4)).

        execute();
orders.

        insertAll(listOfOrders).

        execute();

        // UPDATE / DELETE — where(...) is mandatory unless you call all()
        int changed = orders.update()
                .set("status", "SHIPPED")
                .append("log", " | shipped")
                .where(eq("id", "A9"))
                .execute().count();

orders.

        delete().

        where(eq("status", "CANCELLED")).

        orderBy("createdAt").

        limit(10).

        execute();

// UPSERT — key() adds both the filter and the value written on insert
orders.

        upsert().

        key("id","A9").

        set("qty",6).

        execute();

// CLONE matching rows with overrides
orders.

        cloneRows().

        where(eq("id", "A9")).

        set("id","A10").

        execute();

// Worksheet / schema operations
db.

        worksheet("Archive").

        create().

        execute();
orders.

        addColumns("discount","notes").

        skipExisting().

        execute();

        List<String> headers = orders.columns().fetch();
orders.

        clear().

        execute();                      // keeps the header row
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

## Engine issues found while building the SDK

These are in the Apps Script code, not the SDK. The SDK works around (1) and guards against part of (4).

1. **EQUALS with a number never matches.** `CompiledEqualsPredicate` does
   `String(cell) === this.expectedValue` without `String()` on the expected value, so JSON `5` fails
   against a cell holding 5. The SDK always sends text. Server fix: `=== String(this.expectedValue)`.
2. **`IN` and `BETWEEN` are rejected on SELECT.** `RequestValidator.validatePredicate` only accepts
   binary and unary predicates, so a SELECT using them fails with *Unsupported predicate: InPredicate*
   (UPDATE/DELETE skip validation, so they work there). Fix: add a
   `predicate instanceof MultiValuePredicate` branch that checks the column.
3. **Deleted rows stay visible within a batch — risk of deleting the wrong row.** `DeleteExecutor`
   marks rows deleted but leaves them in `worksheetData.rows`, so a later SELECT in the same request
   still returns them. Worse, a second DELETE matching the same row pushes it again, and
   `commitDeleted` calls `deleteRow` twice on the same index — the second call removes whichever row
   shifted into that position. Fix: skip
   `RowState.DELETED` rows in `ExecutionExecutor.execute`, and de-duplicate the change set.
4. **No locking.** Two concurrent requests both append at `getLastRow() + 1` and can overwrite each
   other. Fix: wrap `handlePost` in `LockService.getScriptLock().waitLock(30000)` / `releaseLock()`.
5. **No authentication.** Anyone holding the URL can read and write every spreadsheet the script can
   reach, by passing any `spreadsheetId`. Consider a shared secret checked against Script Properties
   plus an allow-list of spreadsheet ids. `doPost` cannot read request headers, so the secret has to
   travel in the JSON body; a custom `Transport` can add it before sending.

---

## Building and testing

```bash
mvn test       # 32 unit tests, no network: request contract, response parsing,
               # batching, retries, entity mapping, and the HTTP redirect flow
               # against an in-process server
mvn package
```
