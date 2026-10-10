package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.mapping.SheetKey;
import io.github.prasunmondal.gridbase.mapping.SheetTable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
