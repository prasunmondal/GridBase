package io.github.prasunmondal.gridbase;

import com.sun.net.httpserver.HttpServer;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.transport.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reproduces the Apps Script web-app flow: POST /exec -> 302 -> GET echo URL -> JSON. */
class HttpTransportTest {

    private static final class Server implements AutoCloseable {
        final HttpServer http;
        final List<String> log = Collections.synchronizedList(new ArrayList<>());

        Server() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }

        String base() {
            return "http://127.0.0.1:" + http.getAddress().getPort();
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            ex.getResponseHeaders().add("Content-Type", contentType);
        }
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }

    @Test
    void followsAppsScriptRedirectWithGetAndDropsAuthOffHost() throws Exception {
        try (Server s = new Server()) {
            s.http.createContext("/macros/s/DEP/exec", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                s.log.add(ex.getRequestMethod() + " exec auth=" + ex.getRequestHeaders().getFirst("Authorization")
                        + " body=" + body);
                // Different host name (localhost vs 127.0.0.1) plays the role of script.googleusercontent.com
                ex.getResponseHeaders().add("Location",
                        "http://localhost:" + s.http.getAddress().getPort() + "/echo?user_content_key=abc");
                reply(ex, 302, null, "");
            });
            s.http.createContext("/echo", ex -> {
                s.log.add(ex.getRequestMethod() + " echo auth=" + ex.getRequestHeaders().getFirst("Authorization"));
                reply(ex, 200, "application/json", "{\"ok\":true}");
            });
            s.http.start();

            HttpTransport t = HttpTransport.builder(s.base() + "/macros/s/DEP/exec")
                    .accessToken(() -> "tok").build();
            assertEquals("{\"ok\":true}", t.send("{\"q\":1}"));
            assertEquals(List.of("POST exec auth=Bearer tok body={\"q\":1}", "GET echo auth=null"), s.log);
        }
    }

    @Test
    void htmlPageGivesDeploymentHint() throws Exception {
        try (Server s = new Server()) {
            s.http.createContext("/exec", ex -> reply(ex, 200, "text/html",
                    "<!DOCTYPE html><html><head><title>Sign in - Google Accounts</title></head></html>"));
            s.http.start();
            TransportException e = assertThrows(TransportException.class,
                    () -> HttpTransport.builder(s.base() + "/exec").build().send("{}"));
            assertTrue(e.getMessage().contains("Sign in - Google Accounts"));
            assertFalse(e.isRetryable());
        }
    }

    @Test
    void serverErrorsAreRetryableClientErrorsAreNot() throws Exception {
        try (Server s = new Server()) {
            s.http.createContext("/busy", ex -> reply(ex, 503, "text/plain", "busy"));
            s.http.createContext("/denied", ex -> reply(ex, 403, "text/plain", "no"));
            s.http.start();
            TransportException busy = assertThrows(TransportException.class,
                    () -> HttpTransport.builder(s.base() + "/busy").build().send("{}"));
            assertTrue(busy.isRetryable());
            assertEquals(503, busy.getHttpStatus());
            TransportException denied = assertThrows(TransportException.class,
                    () -> HttpTransport.builder(s.base() + "/denied").build().send("{}"));
            assertFalse(denied.isRetryable());
            assertTrue(denied.getMessage().contains("Who has access"));
        }
    }

    @Test
    void endToEndThroughClient() throws Exception {
        try (Server s = new Server()) {
            s.http.createContext("/exec", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                s.log.add(body);
                reply(ex, 200, "application/json", "{\"success\":true,\"results\":[{\"operationId\":\"op-1\","
                        + "\"worksheet\":\"Orders\",\"columns\":[\"id\",\"qty\"]}]}");
            });
            s.http.start();
            GridBase db = GridBase.builder().endpoint(s.base() + "/exec").defaultSpreadsheetId("S").build();
            assertEquals(List.of("id", "qty"), db.worksheet("Orders").columns().fetch());
            assertTrue(s.log.get(0).contains("\"type\":\"GET_COLUMNS\""));
        }
    }
}
