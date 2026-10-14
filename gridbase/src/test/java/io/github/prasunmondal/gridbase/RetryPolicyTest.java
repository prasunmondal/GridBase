package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.exception.GridBaseException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    private static final GridBaseException TRANSIENT = new TransportException("reset", -1, null, true);
    private static final GridBaseException PERMANENT = new GridBaseException("bad request");

    @Test
    void backOffGrowsAndIsCapped() {
        RetryPolicy defaults = RetryPolicy.defaults();
        assertEquals(List.of(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L),
                List.of(1, 2, 3, 4, 5, 6).stream().map(a -> defaults.delayAfter(a).toMillis()).collect(Collectors.toList()));

        RetryPolicy constant = RetryPolicy.of(5, Duration.ofMillis(300)).withMultiplier(1.0);
        assertEquals(Duration.ofMillis(300), constant.delayAfter(1));
        assertEquals(Duration.ofMillis(300), constant.delayAfter(4));

        RetryPolicy capped = RetryPolicy.of(5, Duration.ofSeconds(1)).withMultiplier(3.0).withMaxDelay(Duration.ofSeconds(5));
        assertEquals(Duration.ofSeconds(3), capped.delayAfter(2));
        assertEquals(Duration.ofSeconds(5), capped.delayAfter(3));
    }

    @Test
    void shouldRetryRules() {
        RetryPolicy policy = RetryPolicy.of(3, Duration.ZERO);
        assertTrue(policy.shouldRetry(TRANSIENT, 1, true));
        assertTrue(policy.shouldRetry(TRANSIENT, 2, true));
        assertFalse(policy.shouldRetry(TRANSIENT, 3, true), "last attempt");
        assertFalse(policy.shouldRetry(PERMANENT, 1, true), "not retryable");
        assertFalse(policy.shouldRetry(TRANSIENT, 1, false), "a write");
        assertTrue(policy.retryingWrites(true).shouldRetry(TRANSIENT, 1, false));
        assertFalse(policy.retryingWrites(true).shouldRetry(PERMANENT, 1, false));
        assertFalse(policy.retryingWrites(true).retryingWrites(false).shouldRetry(TRANSIENT, 1, false));
    }

    @Test
    void rejectsBadSettingsAndNoneNeverRetries() {
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.of(0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.defaults().withMultiplier(0.5));
        assertThrows(NullPointerException.class, () -> RetryPolicy.of(2, null));
        assertThrows(NullPointerException.class, () -> RetryPolicy.defaults().withMaxDelay(null));

        RetryPolicy none = RetryPolicy.none();
        assertEquals(1, none.maxAttempts());
        assertFalse(none.shouldRetry(TRANSIENT, 1, true));
        assertFalse(none.retryingWrites(true).shouldRetry(TRANSIENT, 1, false));
        assertEquals(3, RetryPolicy.defaults().maxAttempts());
    }

    @Test
    void interruptDuringBackOffStopsWithFlagKept() {
        AtomicInteger calls = new AtomicInteger();
        GridBase db = GridBase.builder()
                .transport(new FakeTransport(req -> {
                    calls.incrementAndGet();
                    throw new TransportException("reset", -1, null, true);
                }))
                .defaultSpreadsheetId("S")
                .retryPolicy(RetryPolicy.of(3, Duration.ofSeconds(30)))
                .build();
        Thread.currentThread().interrupt();
        try {
            GridBaseException e = assertThrows(GridBaseException.class, () -> db.worksheet("T").select().fetch());
            assertEquals("Interrupted while waiting to retry", e.getMessage());
            assertInstanceOf(InterruptedException.class, e.getCause());
            assertFalse(e.isRetryable());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, calls.get());
        } finally {
            Thread.interrupted();
        }
    }
}
