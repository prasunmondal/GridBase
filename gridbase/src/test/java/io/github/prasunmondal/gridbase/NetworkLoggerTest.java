package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkLoggerTest {

    private final List<String> lines = new ArrayList<>();
    private final Logger jul = Logger.getLogger(NetworkLogger.LOGGER_NAME);
    private final Level julLevel = jul.getLevel();
    private Handler captured;

    @AfterEach
    void restoreJul() {
        if (captured != null) {
            jul.removeHandler(captured);
        }
        jul.setLevel(julLevel);
        jul.setUseParentHandlers(true);
    }

    private GridBase client(FakeTransport transport, NetworkLogger logger) {
        return GridBase.builder()
                .transport(transport)
                .defaultSpreadsheetId("1C8rsAWa0XfpxfHSb")
                .retryPolicy(RetryPolicy.none())
                .logNetworkCalls(logger.to(lines::add))
                .build();
    }

    @Test
    void logsRequestSummaryBodyAndReply() {
        GridBase db = client(FakeTransport.replying("\"rowCount\":0,\"rows\":[]"), NetworkLogger.defaults());

        db.worksheet("Customers").select().where(eq("id", "C-7")).fetch();

        assertEquals(2, lines.size(), lines.toString());
        String req = lines.get(0);
        assertTrue(req.startsWith("GridBase >> "), req);
        assertTrue(req.contains("attempt 1"), req);
        assertTrue(req.contains("1 op: SELECT Customers"), req);
        assertTrue(req.contains("spreadsheet 1C8rsAWa..."), req);
        assertTrue(req.contains("body={\"requestId\""), req);
        assertTrue(req.contains("C-7"), req);
        String reply = lines.get(1);
        assertTrue(reply.startsWith("GridBase << " + req.substring(12, 20) + " OK in "), reply);
        assertTrue(reply.contains(" chars"), reply);
    }

    @Test
    void summarisesEveryOperationOfABatch() {
        GridBase db = client(FakeTransport.replying("\"rowCount\":1,\"rows\":[]", "\"rowCount\":0,\"rows\":[]"),
                NetworkLogger.defaults().maxBodyChars(0));
        Batch batch = db.batch();
        batch.add(db.worksheet("Orders").insert(Map.of("id", "A1")));
        batch.add(db.worksheet("Customers").select());
        batch.execute();

        assertTrue(lines.get(0).contains("2 ops: INSERT Orders, SELECT Customers"), lines.get(0));
        assertFalse(lines.get(0).contains("body="), "maxBodyChars(0) leaves the body out");
    }

    @Test
    void cutsLongBodies() {
        GridBase db = client(FakeTransport.replying("\"rowCount\":1,\"rows\":[]"),
                NetworkLogger.defaults().maxBodyChars(20));
        db.worksheet("Orders").insert(Map.of("notes", "x".repeat(500))).execute();

        assertTrue(lines.get(0).matches(".*body=.{20}\\.\\.\\. \\(\\+\\d+ chars\\)$"), lines.get(0));
    }

    @Test
    void marksEngineErrors() {
        FakeTransport transport = new FakeTransport(req ->
                "{\"success\":false,\"requestId\":\"r\",\"error\":\"Unknown column: stauts\",\"results\":[]}");
        GridBase db = client(transport, NetworkLogger.defaults());

        assertThrows(ServerException.class,
                () -> db.worksheet("Orders").update().set("stauts", "X").where(eq("id", "A1")).execute());
        assertTrue(lines.get(1).contains("ENGINE ERROR in "), lines.get(1));
        assertTrue(lines.get(1).endsWith(": Unknown column: stauts"), lines.get(1));
    }

    @Test
    void logsTransportFailuresAndEachRetry() {
        FakeTransport transport = new FakeTransport(req -> {
            throw new TransportException("connection refused", 0, null, true);
        });
        GridBase db = GridBase.builder()
                .transport(transport)
                .defaultSpreadsheetId("S")
                .retryPolicy(RetryPolicy.of(2, Duration.ZERO))
                .logNetworkCalls(NetworkLogger.defaults().to(lines::add))
                .build();

        assertThrows(TransportException.class, () -> db.worksheet("Orders").select().fetch());
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("attempt 1"));
        assertTrue(lines.get(1).contains("FAILED in "), lines.get(1));
        assertTrue(lines.get(1).contains("TransportException: connection refused"), lines.get(1));
        assertTrue(lines.get(2).contains("attempt 2"));
    }

    @Test
    void responseBodyOnlyWhenAsked() {
        GridBase db = client(FakeTransport.replying("\"rowCount\":0,\"rows\":[]"),
                NetworkLogger.defaults().maxResponseChars(500));
        db.worksheet("Orders").select().fetch();

        assertTrue(lines.get(1).contains("response={\"success\":true"), lines.get(1));
    }

    @Test
    void ansiColorWrapsLinesInRed() {
        GridBase db = client(FakeTransport.replying("\"rowCount\":0,\"rows\":[]"),
                NetworkLogger.defaults().ansiColor(true));
        db.worksheet("Orders").select().fetch();

        assertTrue(lines.get(0).startsWith("\u001B[31mGridBase"), lines.get(0));
        assertTrue(lines.get(0).endsWith("\u001B[0m"), lines.get(0));
    }

    @Test
    void defaultsGoToJulAtSevereSoTheyShowRed() {
        List<LogRecord> records = new ArrayList<>();
        captured = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        jul.addHandler(captured);
        jul.setUseParentHandlers(false);

        SheetProperties p = SheetProperties.builder()
                .transport(FakeTransport.replying("\"rowCount\":0,\"rows\":[]"))
                .dbSheetUrl("S").tabName("Orders")
                .logNetworkCalls()
                .build();
        p.worksheet().select().fetch();

        assertEquals(2, records.size());
        assertEquals(Level.SEVERE, records.get(0).getLevel());
        assertEquals(NetworkLogger.LOGGER_NAME, records.get(0).getLoggerName());
    }

    @Test
    void silentWhenTheLoggerIsTurnedOff() {
        List<LogRecord> records = new ArrayList<>();
        captured = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        jul.addHandler(captured);
        jul.setUseParentHandlers(false);
        jul.setLevel(Level.OFF);

        GridBase db = GridBase.builder()
                .transport(FakeTransport.replying("\"rowCount\":0,\"rows\":[]"))
                .defaultSpreadsheetId("S")
                .logNetworkCalls()
                .build();
        db.worksheet("Orders").select().fetch();

        assertTrue(records.isEmpty());
    }

    @Test
    void levelOffTurnsItOff() {
        GridBase db = client(FakeTransport.replying("\"rowCount\":0,\"rows\":[]"),
                NetworkLogger.defaults().level(Level.OFF));
        db.worksheet("Orders").select().fetch();

        assertTrue(lines.isEmpty(), lines.toString());
    }

    @Test
    void cacheHitsMakeNoCallSoLogNothing() {
        GridBase db = GridBase.builder()
                .transport(FakeTransport.replying("\"rowCount\":0,\"rows\":[]"))
                .defaultSpreadsheetId("S")
                .cache(new io.github.prasunmondal.gridbase.cache.InMemoryResponseCache(),
                        io.github.prasunmondal.gridbase.cache.CacheStrategy.CACHE_FIRST,
                        io.github.prasunmondal.gridbase.cache.CacheExpiry.ttlMinutes(5))
                .logNetworkCalls(NetworkLogger.defaults().to(lines::add))
                .build();
        db.worksheet("Orders").select().fetch();
        db.worksheet("Orders").select().fetch();

        assertEquals(2, lines.size(), "one request + one reply line; the second read was a cache hit");
    }
}
