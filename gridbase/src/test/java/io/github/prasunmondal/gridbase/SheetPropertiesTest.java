package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.prasunmondal.gridbase.cache.CacheBackend;
import io.github.prasunmondal.gridbase.cache.CacheExpiry;
import io.github.prasunmondal.gridbase.cache.CacheStrategy;
import io.github.prasunmondal.gridbase.cache.InMemoryResponseCache;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.mapping.SheetKey;
import io.github.prasunmondal.gridbase.mapping.SheetTable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SheetPropertiesTest {

    @SheetTable(worksheet = "FromAnnotation")
    public static class Customer {
        @SheetKey
        @JsonProperty("customer_id")
        public String id;
        public String name;
    }

    @Test
    void dbSheetUrlAcceptsFullUrlOrId() {
        assertEquals("1AbC-d_9", SheetProperties.spreadsheetIdOf("https://docs.google.com/spreadsheets/d/1AbC-d_9/edit#gid=0"));
        assertEquals("1AbC-d_9", SheetProperties.spreadsheetIdOf(" 1AbC-d_9 "));
    }

    @Test
    void repositoryUsesSpreadsheetAndTabFromProperties() {
        FakeTransport t = FakeTransport.replying("\"rowCount\":1,\"rows\":[{\"customer_id\":\"C1\",\"name\":\"Asha\"}]");
        SheetProperties props = SheetProperties.builder()
                .transport(t)
                .dbSheetUrl("https://docs.google.com/spreadsheets/d/SHEET1/edit")
                .tabName("Clients")
                .build();

        Customer c = props.repository(Customer.class).findById("C1").orElseThrow();

        assertEquals("Asha", c.name);
        assertEquals("SHEET1", t.lastOperation().path("spreadsheetId").asText());
        assertEquals("Clients", t.lastOperation().path("worksheet").asText());
    }

    @Test
    void tabFallsBackToEntityAnnotation() {
        FakeTransport t = FakeTransport.replying("\"rowCount\":0,\"rows\":[]");
        SheetProperties props = SheetProperties.builder().transport(t).dbSheetUrl("S").build();
        props.repository(Customer.class).findAll();
        assertEquals("FromAnnotation", t.lastOperation().path("worksheet").asText());
    }

    @Test
    void preAndPostActionsWrapEveryAttemptIncludingRetries() {
        int[] calls = {0};
        FakeTransport t = new FakeTransport(req -> {
            if (calls[0]++ == 0) {
                throw new TransportException("boom", 503, null, true);
            }
            return FakeTransport.replying("\"rowCount\":0,\"rows\":[]").send(req.toString());
        });
        List<String> events = new ArrayList<>();
        List<NetworkCallResult> results = new ArrayList<>();
        SheetProperties props = SheetProperties.builder()
                .transport(t)
                .dbSheetUrl("S")
                .tabName("T")
                .retryPolicy(RetryPolicy.of(2, Duration.ZERO))
                .preNetworkCall(call -> events.add("pre" + call.attempt()))
                .postNetworkCall(result -> {
                    events.add("post" + result.call().attempt());
                    results.add(result);
                })
                .build();

        props.worksheet().select().fetch();

        assertEquals(List.of("pre1", "post1", "pre2", "post2"), events);
        assertFalse(results.get(0).succeeded());
        assertNull(results.get(0).responseBody());
        assertTrue(results.get(1).succeeded());
        assertEquals(results.get(0).call().requestId(), results.get(1).call().requestId());
    }

    @Test
    void onFailureRunsOnceAfterRetriesAreExhausted() {
        TransportException boom = new TransportException("boom", 503, null, true);
        FakeTransport t = new FakeTransport(req -> {
            throw boom;
        });
        List<RequestFailure> failures = new ArrayList<>();
        SheetProperties props = SheetProperties.builder()
                .transport(t)
                .dbSheetUrl("S")
                .tabName("T")
                .retryPolicy(RetryPolicy.of(3, Duration.ZERO))
                .onFailure(failures::add)
                .build();

        assertSame(boom, assertThrows(TransportException.class, () -> props.worksheet().select().fetch()));

        assertEquals(1, failures.size());
        RequestFailure f = failures.get(0);
        assertSame(boom, f.failure());
        assertEquals(3, f.attempts());
        assertTrue(f.readOnly());
        assertEquals(3, t.requests.size());
        assertEquals(t.requests.get(0).path("requestId").asText(), f.requestId());
    }

    @Test
    void onFailureSeesEngineErrorsAndNotSuccesses() {
        boolean[] fail = {true};
        FakeTransport t = new FakeTransport(req -> fail[0]
                ? "{\"success\":false,\"error\":\"Unknown column\",\"exceptionType\":\"Error\",\"stackTrace\":[]}"
                : FakeTransport.replying("\"rowCount\":0,\"rows\":[]").send(req.toString()));
        List<RequestFailure> failures = new ArrayList<>();
        SheetProperties props = SheetProperties.builder()
                .transport(t).dbSheetUrl("S").tabName("T").onFailure(failures::add).build();

        assertThrows(ServerException.class, () -> props.worksheet().select().fetch());
        fail[0] = false;
        props.worksheet().select().fetch();

        assertEquals(1, failures.size());
        assertTrue(failures.get(0).failure() instanceof ServerException);
    }

    @Test
    void throwingFailureActionDoesNotReplaceTheRequestException() {
        TransportException boom = new TransportException("boom", 500, null, false);
        List<String> events = new ArrayList<>();
        SheetProperties props = SheetProperties.builder()
                .transport(new FakeTransport(req -> {
                    throw boom;
                }))
                .dbSheetUrl("S").tabName("T")
                .onFailure(f -> {
                    throw new IllegalStateException("hook bug");
                })
                .onFailure(f -> events.add("second"))
                .build();

        assertSame(boom, assertThrows(TransportException.class, () -> props.worksheet().select().fetch()));
        assertEquals(List.of("second"), events);
    }

    @Test
    void toBuilderDerivesWithoutChangingBase() {
        SheetProperties base = SheetProperties.builder().scriptUrl("https://x/exec").dbSheetUrl("S").build();
        SheetProperties derived = base.toBuilder().tabName("T").preNetworkCall(c -> { }).build();
        assertNull(base.tabName());
        assertTrue(base.preNetworkCallActions().isEmpty());
        assertEquals("T", derived.tabName());
        assertEquals("https://x/exec", derived.scriptUrl());
        assertSame(derived.client(), derived.client());
    }

    @Test
    void requiresScriptUrlAndSheet() {
        assertThrows(IllegalStateException.class, () -> SheetProperties.builder().dbSheetUrl("S").build());
        assertThrows(IllegalStateException.class, () -> SheetProperties.builder().scriptUrl("https://x/exec").build());
    }

    @Test
    void everyBuilderSetterExceptTabNameDetachesTheClient() {
        SheetProperties base = SheetProperties.builder()
                .transport(FakeTransport.replying("\"rowCount\":0,\"rows\":[]"))
                .dbSheetUrl("S")
                .tabName("A")
                .build();
        Map<String, UnaryOperator<SheetProperties.Builder>> setters = new LinkedHashMap<>();
        setters.put("queueRequests", b -> b.queueRequests(Duration.ofMillis(5)));
        setters.put("queueMaxOperations", b -> b.queueMaxOperations(5));
        setters.put("clock", b -> b.clock(Clock.systemUTC()));
        setters.put("shallCache", b -> b.shallCache(false));
        setters.put("cacheStrategy", b -> b.cacheStrategy(CacheStrategy.NETWORK_FIRST));
        setters.put("cacheExpiry", b -> b.cacheExpiry(CacheExpiry.ttlMinutes(1)));
        setters.put("cacheFile", b -> b.cacheFile(Path.of("other.db")));
        setters.put("cacheBackend", b -> b.cacheBackend(CacheBackend.MEMORY));
        setters.put("cacheStore", b -> b.cacheStore(new InMemoryResponseCache()));
        setters.put("scriptUrl", b -> b.scriptUrl("https://x/exec"));
        setters.put("transport", b -> b.transport(FakeTransport.replying()));
        setters.put("dbSheetUrl", b -> b.dbSheetUrl("S"));
        setters.put("timeZone", b -> b.timeZone(ZoneId.of("Asia/Kolkata")));
        setters.put("retryPolicy", b -> b.retryPolicy(RetryPolicy.none()));
        setters.put("connectTimeout", b -> b.connectTimeout(Duration.ofSeconds(1)));
        setters.put("requestTimeout", b -> b.requestTimeout(Duration.ofSeconds(1)));
        setters.put("accessToken", b -> b.accessToken(() -> "t"));
        setters.put("preNetworkCall", b -> b.preNetworkCall(c -> { }));
        setters.put("postNetworkCall", b -> b.postNetworkCall(r -> { }));
        setters.put("onFailure", b -> b.onFailure(f -> { }));
        setters.put("logNetworkCalls", b -> b.logNetworkCalls(NetworkLogger.defaults()));

        GridBase shared = base.client();
        setters.forEach((name, setter) -> {
            assertNotSame(shared, setter.apply(base.toBuilder()).build().client(), name);
            assertNotSame(shared, setter.apply(base.toBuilder()).tabName("B").build().client(), name + " then tabName");
        });
        assertSame(shared, base.toBuilder().tabName("B").build().client());
        assertSame(shared, base.toBuilder().tabName("B").build().toBuilder().tabName(null).build().client());
    }
}
