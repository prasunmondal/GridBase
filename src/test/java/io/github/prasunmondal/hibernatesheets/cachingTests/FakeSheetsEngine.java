package io.github.prasunmondal.hibernatesheets.cachingTests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.prasunmondal.hibernatesheets.exception.TransportException;
import io.github.prasunmondal.hibernatesheets.transport.Transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the hibernate.sheets engine, plugged in as the SDK's {@link Transport}.
 *
 * <p>Holds real rows per worksheet and answers with the engine's JSON contract, so tests can check
 * not only how many network calls were made but whether the data returned was fresh or stale.
 * Mirrors the engine's batch semantics: operations run in order, later ones see earlier ones, and
 * row changes are committed only if every operation succeeds.</p>
 */
final class FakeSheetsEngine implements Transport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final class Sheet {
        final List<String> columns;
        final List<ObjectNode> rows;

        Sheet(List<String> columns, List<ObjectNode> rows) {
            this.columns = columns;
            this.rows = rows;
        }

        Sheet copy() {
            List<ObjectNode> copied = new ArrayList<>();
            rows.forEach(r -> copied.add(r.deepCopy()));
            return new Sheet(new ArrayList<>(columns), copied);
        }
    }

    private final Map<String, Sheet> sheets = new LinkedHashMap<>();
    private final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> failingWorksheets = ConcurrentHashMap.newKeySet();
    private volatile boolean offline;

    // ------------------------------------------------------------------ setup / external edits

    /** Creates (or replaces) a worksheet with a header row and data rows. */
    synchronized FakeSheetsEngine sheet(String spreadsheetId, String worksheet, List<String> columns,
                                        List<Map<String, Object>> rows) {
        List<ObjectNode> nodes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            nodes.add(toRow(columns, row));
        }
        sheets.put(key(spreadsheetId, worksheet), new Sheet(new ArrayList<>(columns), nodes));
        return this;
    }

    /** Changes a cell as if someone edited the sheet by hand — the SDK never sees this write. */
    synchronized void editDirectly(String spreadsheetId, String worksheet, String keyColumn, Object key,
                                   String column, Object value) {
        for (ObjectNode row : require(spreadsheetId, worksheet).rows) {
            if (row.path(keyColumn).asText().equals(String.valueOf(key))) {
                row.set(column, JSON.valueToTree(value));
            }
        }
    }

    /** Appends a row by hand, bypassing the SDK. */
    synchronized void addRowDirectly(String spreadsheetId, String worksheet, Map<String, Object> values) {
        Sheet sheet = require(spreadsheetId, worksheet);
        sheet.rows.add(toRow(sheet.columns, values));
    }

    synchronized int rowCount(String spreadsheetId, String worksheet) {
        return require(spreadsheetId, worksheet).rows.size();
    }

    /** Every request now fails at the network level, like a timeout or lost connection. */
    void goOffline() {
        offline = true;
    }

    void goOnline() {
        offline = false;
    }

    /** Requests touching this worksheet get {@code success:false} from the engine (nothing is written). */
    void failRequestsTouching(String worksheet) {
        failingWorksheets.add(worksheet);
    }

    void stopFailing() {
        failingWorksheets.clear();
    }

    // ------------------------------------------------------------------ inspection

    /** Number of HTTP calls the SDK made (including failed ones). */
    int calls() {
        return requests.size();
    }

    JsonNode request(int index) {
        return requests.get(index);
    }

    JsonNode lastRequest() {
        return requests.get(requests.size() - 1);
    }

    /** Worksheets targeted by each operation of a call, in order. */
    List<String> worksheetsIn(JsonNode request) {
        List<String> names = new ArrayList<>();
        request.path("operations").forEach(op -> names.add(op.path("worksheet").asText()));
        return names;
    }

    // ------------------------------------------------------------------ Transport

    @Override
    public synchronized String send(String requestJson) {
        JsonNode request;
        try {
            request = JSON.readTree(requestJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        requests.add(request);
        if (offline) {
            throw new TransportException("connection refused (fake engine offline)", 0, null, true);
        }
        JsonNode operations = request.path("operations");
        for (JsonNode op : operations) {
            if (failingWorksheets.contains(op.path("worksheet").asText())) {
                return error("Worksheet not found: " + op.path("worksheet").asText());
            }
        }

        Map<String, Sheet> working = new LinkedHashMap<>();
        sheets.forEach((k, v) -> working.put(k, v.copy()));
        ArrayNode results = NODES.arrayNode();
        try {
            for (JsonNode op : operations) {
                ObjectNode result = execute(working, op);
                result.put("operationId", op.path("id").asText());
                results.add(result);
            }
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        sheets.clear();
        sheets.putAll(working);

        ObjectNode reply = NODES.objectNode();
        reply.put("success", true);
        reply.put("requestId", request.path("requestId").asText());
        reply.put("executionTime", 3);
        reply.set("results", results);
        reply.set("errors", NODES.arrayNode());
        reply.set("debug", NODES.arrayNode());
        return reply.toString();
    }

    private ObjectNode execute(Map<String, Sheet> working, JsonNode op) {
        String type = op.path("type").asText();
        String spreadsheetId = op.path("spreadsheetId").asText();
        String worksheet = op.path("worksheet").asText();
        ObjectNode result = NODES.objectNode();

        if (type.equals("CREATE_WORKSHEET")) {
            if (working.containsKey(key(spreadsheetId, worksheet))) {
                throw new IllegalArgumentException("Worksheet already exists: " + worksheet);
            }
            working.put(key(spreadsheetId, worksheet), new Sheet(new ArrayList<>(), new ArrayList<>()));
            result.put("worksheet", worksheet);
            result.put("sheetId", working.size());
            return result;
        }

        Sheet sheet = working.get(key(spreadsheetId, worksheet));
        if (sheet == null) {
            throw new IllegalArgumentException("Worksheet not found: " + worksheet);
        }

        switch (type) {
            case "SELECT": {
                List<ObjectNode> matched = matching(sheet, op);
                sort(matched, op.path("orderBy"));
                matched = paginate(matched, op);
                return rows(result, project(matched, op.path("select")));
            }
            case "GET_COLUMNS": {
                ArrayNode cols = result.putArray("columns");
                sheet.columns.forEach(cols::add);
                result.put("worksheet", worksheet);
                return result;
            }
            case "INSERT": {
                List<ObjectNode> inserted = new ArrayList<>();
                for (JsonNode values : op.path("rows")) {
                    ObjectNode row = blankRow(sheet);
                    assign(row, values);
                    sheet.rows.add(row);
                    inserted.add(row);
                }
                return rows(result, inserted);
            }
            case "UPDATE": {
                List<ObjectNode> matched = matching(sheet, op);
                matched.forEach(r -> assign(r, op.path("values")));
                return rows(result, matched);
            }
            case "DELETE": {
                List<ObjectNode> matched = paginate(matching(sheet, op), op);
                sheet.rows.removeIf(r -> matched.stream().anyMatch(m -> m == r));
                return rows(result, matched);
            }
            case "UPSERT": {
                List<ObjectNode> matched = matching(sheet, op);
                if (matched.isEmpty()) {
                    ObjectNode row = blankRow(sheet);
                    assign(row, op.path("values"));
                    sheet.rows.add(row);
                    matched = List.of(row);
                } else {
                    matched.forEach(r -> assign(r, op.path("values")));
                }
                return rows(result, matched);
            }
            case "CLONE": {
                List<ObjectNode> clones = new ArrayList<>();
                for (ObjectNode source : matching(sheet, op)) {
                    ObjectNode copy = source.deepCopy();
                    assign(copy, op.path("values"));
                    clones.add(copy);
                }
                if (clones.isEmpty()) {
                    throw new IllegalArgumentException("CLONE matched no rows");
                }
                sheet.rows.addAll(clones);
                return rows(result, clones);
            }
            case "CLEAR_WORKSHEET": {
                result.put("worksheet", worksheet);
                result.put("rowsCleared", sheet.rows.size());
                result.put("columnsCleared", sheet.columns.size());
                sheet.rows.clear();
                return result;
            }
            case "ADD_COLUMNS": {
                ArrayNode added = result.putArray("columns");
                ArrayNode skipped = result.putArray("skippedColumns");
                result.put("startColumn", sheet.columns.size() + 1);
                for (JsonNode c : op.path("columns")) {
                    if (sheet.columns.contains(c.asText())) {
                        skipped.add(c.asText());
                    } else {
                        sheet.columns.add(c.asText());
                        sheet.rows.forEach(r -> r.put(c.asText(), ""));
                        added.add(c.asText());
                    }
                }
                result.put("count", added.size());
                result.put("worksheet", worksheet);
                return result;
            }
            default:
                throw new IllegalArgumentException("Unsupported operation type: " + type);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<ObjectNode> matching(Sheet sheet, JsonNode op) {
        List<ObjectNode> matched = new ArrayList<>();
        for (ObjectNode row : sheet.rows) {
            boolean all = true;
            for (JsonNode filter : op.path("where")) {
                if (!matches(row, filter)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                matched.add(row);
            }
        }
        return matched;
    }

    private static boolean matches(ObjectNode row, JsonNode filter) {
        JsonNode cell = row.path(filter.path("column").asText());
        String text = cell.isMissingNode() || cell.isNull() ? "" : cell.asText();
        JsonNode value = filter.path("value");
        switch (filter.path("operator").asText()) {
            case "EQUALS":
                return text.equals(value.asText());
            case "NOT_EQUALS":
                return !text.equals(value.asText());
            case "CONTAINS":
                return text.contains(value.asText());
            case "STARTS_WITH":
                return text.startsWith(value.asText());
            case "ENDS_WITH":
                return text.endsWith(value.asText());
            case "GREATER_THAN":
                return number(text) > value.asDouble();
            case "GREATER_THAN_EQUALS":
                return number(text) >= value.asDouble();
            case "LESS_THAN":
                return number(text) < value.asDouble();
            case "LESS_THAN_EQUALS":
                return number(text) <= value.asDouble();
            case "BETWEEN":
                double n = number(text);
                return n >= filter.path("minimum").asDouble() && n <= filter.path("maximum").asDouble();
            case "IN":
                for (JsonNode v : filter.path("values")) {
                    if (v.asText().equals(text)) {
                        return true;
                    }
                }
                return false;
            case "IS_NULL":
                return text.isEmpty();
            case "IS_NOT_NULL":
                return !text.isEmpty();
            default:
                throw new IllegalArgumentException("Unsupported predicate: " + filter.path("operator").asText());
        }
    }

    private static double number(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static void sort(List<ObjectNode> rows, JsonNode orderBy) {
        if (orderBy.isEmpty()) {
            return;
        }
        JsonNode first = orderBy.get(0);
        String column = first.path("column").asText();
        Comparator<ObjectNode> byColumn = Comparator.comparing((ObjectNode r) -> r.path(column).asText());
        rows.sort("DESC".equals(first.path("direction").asText()) ? byColumn.reversed() : byColumn);
    }

    private static List<ObjectNode> paginate(List<ObjectNode> rows, JsonNode op) {
        int offset = op.path("offset").asInt(0);
        int limit = op.has("limit") ? op.get("limit").asInt() : -1;
        List<ObjectNode> out = rows.subList(Math.min(offset, rows.size()), rows.size());
        return new ArrayList<>(limit >= 0 && limit < out.size() ? out.subList(0, limit) : out);
    }

    private static List<ObjectNode> project(List<ObjectNode> rows, JsonNode select) {
        if (select.isEmpty()) {
            return rows;
        }
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode row : rows) {
            ObjectNode p = NODES.objectNode();
            select.forEach(c -> p.set(c.asText(), row.path(c.asText()).deepCopy()));
            out.add(p);
        }
        return out;
    }

    private static void assign(ObjectNode row, JsonNode assignments) {
        assignments.fields().forEachRemaining(e -> {
            JsonNode value = e.getValue().path("value");
            String current = row.path(e.getKey()).asText("");
            switch (e.getValue().path("operation").asText("SET")) {
                case "APPEND" -> row.put(e.getKey(), current + value.asText());
                case "PREPEND" -> row.put(e.getKey(), value.asText() + current);
                default -> row.set(e.getKey(), value.isNull() ? TextNode.valueOf("") : value.deepCopy());
            }
        });
    }

    private static ObjectNode blankRow(Sheet sheet) {
        ObjectNode row = NODES.objectNode();
        sheet.columns.forEach(c -> row.put(c, ""));
        return row;
    }

    private static ObjectNode toRow(List<String> columns, Map<String, Object> values) {
        ObjectNode row = NODES.objectNode();
        for (String c : columns) {
            Object v = values.get(c);
            row.set(c, v == null ? TextNode.valueOf("") : JSON.valueToTree(v));
        }
        return row;
    }

    private static ObjectNode rows(ObjectNode result, List<ObjectNode> rows) {
        ArrayNode array = result.putArray("rows");
        rows.forEach(r -> array.add(r.deepCopy()));
        result.put("rowCount", rows.size());
        return result;
    }

    private static String error(String message) {
        ObjectNode reply = NODES.objectNode();
        reply.put("success", false);
        reply.put("error", message);
        reply.put("exceptionType", "Error");
        reply.set("stackTrace", NODES.arrayNode());
        reply.set("debug", NODES.arrayNode());
        return reply.toString();
    }

    private Sheet require(String spreadsheetId, String worksheet) {
        Sheet sheet = sheets.get(key(spreadsheetId, worksheet));
        if (sheet == null) {
            throw new IllegalArgumentException("No worksheet " + worksheet + " in " + spreadsheetId);
        }
        return sheet;
    }

    private static String key(String spreadsheetId, String worksheet) {
        return spreadsheetId + "/" + worksheet;
    }
}
