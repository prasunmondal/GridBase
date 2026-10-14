# Plan: checked-in list of missing unit and integration tests

## Context
You asked for every unit and integration test that is still missing, including request queueing and
batch requests. The existing suites were mapped method by method: 13 unit/caching classes, 10 IT
classes, about 270 tests. The gaps cluster in five places:
1. The automatic queue's ordering and isolation guarantees (ADR-006).
2. The failure paths of `GridBase.sendCombined`. A retryable rejection is never re-sent alone, and a
   combined call that contains a write loses the read retry.
3. The `Batch` / `Ref` lifecycle.
4. Concurrent-writer consistency on a live deployment.
5. The engine behaviour changed in ADR-015.

Decisions: you want **a list file only** (no tests written now) and **Java tests only** (no Node
engine suite).

## Deliverable
1. Create `TODO_tests.md` (repo root) with the list below. Each row has an ID, a suggested
   `Class#method`, the scenario and its assertions, a priority, and where it runs.
    - **Priority.** P1: guards a consistency promise, a recent change or a suspected bug. P2: public
      behaviour with no test. P3: edge case or validation.
    - **Runs on.** U: `mvn test`, `FakeTransport` / `FakeSheetsEngine`. E: emulator and live. L: live
      deployment only, because the emulator is serial and does not evaluate formulas.
2. Add one line to `.claude/context/domains/testing.md` → Related, pointing at the file. Then run
   `node scripts/knowledge/lint-context.js`.
3. Nothing else changes. No tests are written yet.

## Reuse when the tests are written
- `FakeTransport(Function<JsonNode,String>)` (`gridbase/src/test/.../FakeTransport.java`). A custom
  responder can block on a latch, throw, count concurrent sends or return short results.
- `cachingTests/FakeSheetsEngine` holds real rows with batch semantics: `goOffline`,
  `failRequestsTouching`, `calls()`. It is package-private, so new queue and batch tests that need
  real data go in `cachingTests` or move it to a shared package. It needs a small addition:
  `failNextWith(message)` for retryable errors such as "Lock timeout".
- `CachingTestBase` (`cached()`, `track()`, `assertCalls()`, `MutableClock`). Never sleep.
- For ITs: `TestData.resetAll()`, `employeesSheet()`, `newEmployee()`, `employeeCount()`,
  `ItConfig.db()`, and `InsertIT.concurrentInsertsAllLand` as the pattern for multi-client concurrency.

---

## The list (content of TODO_tests.md)

### A. Automatic queue: `RequestQueue` (extend `RequestQueueTest`)
| ID | Test | Scenario / assertion | P | Runs on |
|---|---|---|---|---|
| A1 | `requestsMadeDuringAnInFlightCallGoInTheNextCallInOrder` | Block call 1 on a latch. Submit r2 and r3, then release. Call 2 = [r2, r3] in submission order. | P1 | U |
| A2 | `neverTwoCallsAtOnce` | 20 threads × 10 requests. The transport's maximum concurrent `send` is 1. | P1 | U |
| A3 | `retryableRejectionOfACombinedCallFailsAllAndIsNotResentAlone` | The combined call returns "Lock timeout…". Every future fails with that `ServerException` (retryable). Exactly 1 call. Pins `sendCombined`'s `isRetryable()` branch. | P1 | U |
| A4 | `combinedCallWithAWriteIsNotRetriedSoItsReadsAreNotEither` | Read + write in one window, transient failure. 1 attempt; the read fails. A pure-read combined call gets 3 attempts. With `retryingWrites(true)`, both are retried. Documents that combining costs reads their retry. | P1 | U |
| A5 | `readThenWriteInOneWindowLeavesNoStaleCacheEntry` | Cached client. Async read and write on one tab in one window, repeated ×200. The next read goes to the network. Suspected race: `finishRead` (put) and `invalidateWrites` complete on different `gridbase-async` threads. | P1 | U |
| A6 | `batchThroughTheQueueStaysAllOrNothing` | A multi-op `Batch` combined with other callers' requests; the combined call is rejected. The batch is re-sent alone as one request, its refs resolve, and the fake engine shows no partial writes. | P1 | U |
| A7 | `reachingMaxOperationsSendsWithoutWaitingForTheWindow` | 10 s window, `queueMaxOperations(3)`, 3 submits. Completes well before the window ends. | P2 | U |
| A8 | `aShortReplyFailsOnlyTheRequestsWithoutAResult` | The combined reply is missing the last result. Only that caller fails (slice isolation); the others get their rows. | P2 | U |
| A9 | `workerKeepsRunningAfterAFailedCall` | The transport throws a `RuntimeException` once. Those futures fail, and later submissions still complete. | P2 | U |
| A10 | `syncCallRethrowsTheOriginalException` | Through the queue, `execute()` throws `ServerException` / `TransportException`, not `CompletionException`. | P2 | U |
| A11 | `hooksRunOncePerCombinedCall` | pre/post network hooks fire once per HTTP call, with the combined body. | P2 | U |
| A12 | `writeThenReadInOneWindowCachesThePostWriteRead` | The read result reflects the write and is cached afterwards. | P2 | U |
| A13 | `twoClientsHaveIndependentQueues` | Requests on different clients are never combined; each client has its own worker. | P3 | U |
| A14 | `lastUpdatedIsQueuedButNeverCached` | GET_LAST_UPDATED combines with reads and is not stored. | P3 | U |
| A15 | `rejectsBadQueueSettings` | Negative window and `maxOperations < 1` are rejected, both in `RequestQueue` and at `SheetProperties.Builder.build()`. | P3 | U |
| A16 | `zeroWindowSendsPromptly` | `queueRequests(Duration.ZERO)` behaves like direct calls, with no stuck futures. | P3 | U |

### B. Explicit queue: `APIRequestsQueue`, `Queued`, `QueueExecutionException` (extend `APIRequestsQueueTest`)
| ID | Test | Scenario / assertion | P | Runs on |
|---|---|---|---|---|
| B1 | `retryableRejectionFailsEveryHandleOfThatCallOnly` | "Lock timeout" on call 1 of 2 (split by size). Call 1's handles fail, not re-sent; call 2 succeeds. | P1 | U |
| B2 | `queueExecutionExceptionListsEveryFailureAndSuccessesStayReadable` | 2 of 5 fail. The exception has 2 failures and a total of 5; the 3 good handles still return values. | P2 | U |
| B3 | `aThrowingMapperFailsOnlyItsHandle` | Mapping a row to a POJO fails for one request. The others complete. | P2 | U |
| B4 | `maxOperationsBoundaries` | A request of exactly `max` ops goes alone (`>=`). Two requests summing to `max` go together; summing to `max+1` splits. | P2 | U |
| B5 | `oneClientGroupFailingDoesNotStopOtherClients` | `executeTogether` throws for client A. B's handles still complete. | P2 | U |
| B6 | `requestsOnOtherClientsAreNotOrderedAsQueued` | Queue A1, B1, A2. Calls are [A1, A2] then [B1]. Pins (and documents) the grouping order. | P2 | U |
| B7 | `aFailedChunkDoesNotAffectOtherChunks` | Split by `maxOperationsPerCall`; chunk 2 is rejected. Chunks 1 and 3 succeed. | P2 | U |
| B8 | `repositoryRequestsAllQueueCorrectly` | `findFirstWhere`, `deleteById`, `delete(entity)`, `insertAll`, `upsertAll` (stays together), `deleteAll` (schema, own call). Results map correctly. | P2 | U |
| B9 | `emptyQueueExecutesWithoutACall` | `execute()` with nothing added makes no call and does not throw. | P3 | U |
| B11 | `rejectsMaxOperationsBelowOne` | `new APIRequestsQueue(0)` throws IAE. | P3 | U |
| B12 | `nonRuntimeFailureIsWrapped` | `Queued.fail(checked)` gives a non-retryable `GridBaseException` with the cause. | P3 | U |

### C. Batch: `Batch`, `Ref`, `BatchResult` (new `BatchTest` (U) plus a new `BatchIT` (E))
| ID | Test | Scenario / assertion | P | Runs on |
|---|---|---|---|---|
| C1 | `BatchIT.insertThenSelectSeesTheNewRow` | The promise in the `Batch` Javadoc. | P1 | E |
| C2 | `BatchIT.failingOperationWritesNoRowChangesOnAnyWorksheet` | [insert IT_Employees, update IT_Departments, op on an unknown column]. Neither tab changed. | P1 | E |
| C3 | `BatchIT.schemaOperationBeforeAFailureStaysApplied` | [create tab, add columns, failing insert]. The tab and columns exist; no rows. | P1 | E |
| C4 | `BatchIT.deleteThenInsertAppendsAfterTheRemainingRows` | Commit order created → updated → deleted. Remaining rows intact, new row last. | P1 | E |
| C5 | `BatchIT.insertThenDeleteSameRowWritesNothing` | Row count unchanged (ADR-015). | P1 | E |
| C6 | `BatchIT.updateThenDeleteSameRowKeepsNeighbours` | The row is gone; neighbours unchanged. | P1 | E |
| C7 | `BatchIT.insertThenUpdateItWritesFinalValues` | The new row has the updated values, written once. | P1 | E |
| C8 | `BatchIT.updatesOfAdjacentRowsOnDifferentColumnsBothApply` | Coalesced span write. Untouched columns keep their values. | P1 | E |
| C9 | `BatchIT.updateThenSelect`, `upsertThenSelect`, `cloneThenSelect` | Later operations see earlier ones. | P2 | E |
| C10 | `BatchIT.oneBatchAcrossTwoWorksheetsCommitsBoth` | Both tabs are changed. | P2 | E |
| C11 | `BatchTest.failedExecuteLeavesBatchRetryable` | After a `ServerException`, `Ref.get` throws ISE. `execute()` can run again and the refs then resolve. Confirm this is intended: the Javadoc says only that a *successful* batch is single-use. | P2 | U |
| C12 | `BatchTest.everyResultTypeResolvesInABatch` | `columns`, `create`, `addColumns`, `clear`, `lastUpdated`, `delete` refs give the right result classes. | P2 | U |
| C13 | `BatchTest.retryPolicyAppliesToTheWholeBatch` | A read-only batch is retried on a transient failure. A batch with a write is not, unless `retryingWrites(true)`. | P2 | U |
| C14 | `BatchTest.writeBatchInvalidatesEveryTouchedWorksheetEvenOnFailure` | Two tabs written, then the engine fails. Both caches are invalidated. | P2 | U |
| C15 | `BatchTest.schemaBatchBypassesTheAutomaticQueue` | Also a batch larger than `queueMaxOperations`. One direct call. | P2 | U |
| C16 | `BatchTest.emptyBatchAndAddAfterExecuteAreRejected` | ISE "Batch is empty", and ISE on `add` after success. | P3 | U |
| C17 | `BatchTest.foreignSpecAndForeignRefAreRejected` | A spec from another client throws IAE; `BatchResult.get(ref of another batch)` throws IAE. | P3 | U |
| C18 | `BatchTest.resultsKeepAddOrderAndPassThroughMetadata` | `results()` order, `requestId()`, `debug()`, `size()`. | P3 | U |

### D. Concurrency and consistency (new `ConcurrencyIT`; live only, with JUnit `@Tag("live")`)
| ID | Test | Scenario / assertion | P | Runs on |
|---|---|---|---|---|
| D1 | `concurrentUpdatesOfDifferentRowsAreAllKept` | N clients each update their own row. Every change is present, with no lost update. | P1 | L |
| D2 | `concurrentAppendsToOneCellAreSerialised` | N clients APPEND to the same cell. The final text contains all N parts. | P1 | L |
| D3 | `concurrentUpsertsOfOneKeyCreateOneRow` | N clients upsert the same key. Exactly 1 row results. | P1 | L |
| D4 | `concurrentDeletesAndInsertsKeepTheRightRows` | Count = start − deleted + inserted. No wrong row is deleted. | P1 | L |
| D5 | `contentionFailuresAreRetryableLockTimeouts` | Any failure under load is a retryable "Lock timeout" `ServerException`. | P2 | L |
| D6 | `readsDuringWritesNeverFailAndSeeWholeSingleOpCommits` | Documents the ADR-007 read window. | P2 | L |
| D7 | `automaticQueueUnderLoadEndsInTheRightStateWithFewerCalls` | Many threads through `Employee` (`queueRequests`). Correct final rows, and fewer HTTP calls than requests. | P2 | L |

### E. Engine behaviour visible through the SDK (extend the existing ITs)
| ID | Test | Scenario / assertion | P | Runs on |
|---|---|---|---|---|
| E1 | `UpdateIT.updateKeepsFormulasInOtherColumns` | Insert `=INDIRECT("B"&ROW())` in an echo column, then update column B. The echo shows the new value (ADR-015). | P1 | L |
| E2 | `SchemaOperationsIT.addColumnsThenInsertIntoItInOneBatch` | The new column is usable in the same request when nothing loaded the tab before. | P2 | E |
| E3 | `SchemaOperationsIT.selectThenAddColumnsThenInsertFails` | Documents the stale schema within one request ("Unknown column"). Known limitation; decide fix vs doc. | P2 | E |
| E4 | `SchemaOperationsIT.lastUpdatedWithoutDriveFailsOnlyThatOperation` | Emulator with `HS_DRIVE=off`, in a batch with a SELECT. | P2 | E |
| E5 | `SelectQueryIT.limitWithoutOrderReturnsFirstMatchesInSheetOrder` | Pins the early-exit scan. Also `offset` + `limit` with a filter. | P2 | E |
| E6 | `DeleteIT.mixedAdjacentAndScatteredDeletesKeepOrder` | Delete runs plus single rows in one request. | P2 | E |

### F. Wire contract (extend `RequestContractTest`)
| ID | Test | Scenario / assertion | P |
|---|---|---|---|
| F1 | `cloneSerializesWhereValuesAndLimit` | | P2 |
| F2 | `deleteSerializesOrderByLimitAndAll` | | P2 |
| F3 | `textOperatorsUseJsString` | ne / contains / startsWith / endsWith match `JsCompat`; `ne(null)` → IS_NOT_NULL. | P2 |
| F4 | `betweenWithNumbersAndJavaTime` | Epoch millis in the sheet zone; `in` with mixed types. | P2 |
| F5 | `schemaOperationsAreBare` | create / clear / getColumns. | P3 |
| F6 | `multipleOrderByKeys` | | P3 |
| F7 | `insertAllPerRowOptionalColumnsAndJsonPropertyNames` | | P3 |
| F8 | `invalidSpecsAreRejected` | Column assigned twice; blank column; negative limit/offset; blank spreadsheetId or worksheet; ordered comparison with a boolean or Map. | P3 |

### G. Responses, errors and transport (extend `ResponseHandlingTest`, `HttpTransportTest`)
| ID | Test | Scenario / assertion | P |
|---|---|---|---|
| G1 | `sliceRenumbersAndRejectsOutOfRange` | `ResponseParser.slice` directly. | P2 |
| G2 | `everyResultTypeParses` | Missing `lastUpdated` → error. | P2 |
| G3 | `transientMarkersAreRetryableDailyQuotaIsNot` | Each `ServerException` marker; "for one day"; a null message. | P2 |
| G4 | `redirectEdgeCases` | 307/308 keep the POST body; Location missing; more than the redirect limit. | P2 |
| G5 | `timeoutsAndStatusCodes` | Read timeout / refused → retryable; 408/429 retryable; 401/403 hint. | P2 |
| G6 | `accessTokenSupplierCalledPerRequest`, `userAgentSent` | | P3 |
| G7 | `nonObjectJsonReplyIsATransportError` | `[]` or `42`. | P3 |

### H. Retry policy (new `RetryPolicyTest`)
| ID | Test | Scenario / assertion | P |
|---|---|---|---|
| H1 | `backOffGrowsAndIsCapped` | 500 ms, 1 s, …, capped at `maxDelay`; multiplier 1 gives a constant delay. | P2 |
| H2 | `shouldRetryRules` | Last attempt; non-retryable; write with and without `retryingWrites`. | P2 |
| H3 | `rejectsBadSettingsAndNoneNeverRetries` | | P3 |
| H4 | `interruptDuringBackOffStopsWithFlagKept` | | P3 |

### I. Client API, mapping, configuration (extend `SheetPropertiesTest`, `RepositoryTest`, `ResponseHandlingTest`)
| ID | Test | Scenario / assertion | P |
|---|---|---|---|
| I1 | `everyBuilderSetterExceptTabNameDetachesTheClient` | The rule in `sdk-main.md`. | P2 |
| I2 | `entityMetadataErrors` | Two `@SheetKey` → IAE; none → ISE on `findById` / `upsert`; record keys. | P2 |
| I3 | `repositoryCountsAndOptionals` | `findFirstWhere`, `deleteById` / `delete(entity)` counts, `existsById` false. | P2 |
| I4 | `javaTimeAndNumberMapping` | Instant / Offset / Zoned / LocalDateTime in the sheet zone; enums; BigDecimal; "TRUE" text; numeric strings. | P2 |
| I5 | `builderNeedsEndpointOrTransport`, `worksheetWithoutDefaultSpreadsheet` | | P3 |
| I6 | `executeOneAndAsyncVariants` | `executeAsync` without a queue runs off the caller thread. | P3 |
| I7 | `filterAndSortRejectBlankColumns`; `Worksheet` equals / hashCode / `clearCache` count | | P3 |

### J. Cache (mostly covered; two gaps)
| ID | Test | Scenario / assertion | P |
|---|---|---|---|
| J1 | `sameQueryAloneOrSlicedFromACombinedCallSharesOneKey` | Key independent of requestId and op ids. | P2 |
| J2 | `deleteAllCacheClearsOtherStoresWhenOneThrows` | Custom store throws; the rest are cleared; the first failure is thrown with the others suppressed. | P3 |

### Decisions found while listing (not tests)
- `EngineConfig.maxOperations` (100) and `maxRowsPerOperation` (5000) are never enforced: enforce
  them or delete them.
- Should a failed `Batch` be re-executable (C11)?
- Combining a read with a write removes the read's retry (A4): accept and document, or send reads
  apart.
- `APIRequestsQueue` orders by client, not by queue order (B6): document it.

### Not covered by this list
- Engine internals not visible from Java (Sheets call counts, header-only reads, lock and flush
  order): left out by your choice, and covered only indirectly by D1–D4.
- `gridbase-android`: the on-device checklist in `android.md`.

## Verification
- Every `Class#method` cited as existing coverage was checked by grep. While writing the file, grep
  again that no proposed name collides with an existing test.
- `node scripts/knowledge/lint-context.js` → 0 errors.
- No source or test code changes: `git status` shows only `TODO_tests.md` and `testing.md`.
