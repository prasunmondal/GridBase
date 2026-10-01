package io.github.prasunmondal.hibernatesheets.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.prasunmondal.hibernatesheets.exception.ServerException;
import io.github.prasunmondal.hibernatesheets.exception.TransportException;
import io.github.prasunmondal.hibernatesheets.result.AddColumnsResult;
import io.github.prasunmondal.hibernatesheets.result.ClearResult;
import io.github.prasunmondal.hibernatesheets.result.ColumnsResult;
import io.github.prasunmondal.hibernatesheets.result.ExecutionResponse;
import io.github.prasunmondal.hibernatesheets.result.OperationResult;
import io.github.prasunmondal.hibernatesheets.result.Row;
import io.github.prasunmondal.hibernatesheets.result.RowsResult;
import io.github.prasunmondal.hibernatesheets.result.WorksheetCreated;
import io.github.prasunmondal.hibernatesheets.spec.Operation;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the engine's JSON reply (see {@code SheetEngine.handlePost} / {@code ApiResponse}). */
public final class ResponseParser {

    private static final TypeReference<LinkedHashMap<String, Object>> ROW_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper;
    private final ZoneId zone;

    public ResponseParser(ObjectMapper mapper, ZoneId zone) {
        this.mapper = mapper;
        this.zone = zone;
    }

    public ExecutionResponse parse(String body, List<Operation> operations) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new TransportException("Engine reply is not valid JSON: " + abbreviate(body), 200, e, false);
        }
        if (root == null || !root.isObject()) {
            throw new TransportException("Engine reply is not a JSON object: " + abbreviate(body), 200, null, false);
        }

        if (!root.path("success").asBoolean(false)) {
            throw serverError(root);
        }

        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode result : root.path("results")) {
            byId.put(result.path("operationId").asText(), result);
        }

        List<OperationResult> results = new ArrayList<>(operations.size());
        for (int i = 0; i < operations.size(); i++) {
            String id = RequestSerializer.operationId(i);
            JsonNode node = byId.get(id);
            if (node == null) {
                throw new TransportException("Engine reply has no result for operation " + id
                        + " (" + operations.get(i).type() + ")", 200, null, false);
            }
            results.add(result(operations.get(i), id, node));
        }

        JsonNode debug = root.get("debug");
        return new ExecutionResponse(root.path("requestId").asText(null),
                root.path("executionTime").asLong(0), results, debug);
    }

    /**
     * The part of a successful combined reply that belongs to operations {@code offset .. offset+count-1},
     * renumbered from {@code op-1} as if those operations had been sent alone.
     */
    public String slice(String body, int offset, int count) {
        try {
            JsonNode root = mapper.readTree(body);
            Map<String, JsonNode> byId = new HashMap<>();
            for (JsonNode result : root.path("results")) {
                byId.put(result.path("operationId").asText(), result);
            }
            ObjectNode copy = ((ObjectNode) root).deepCopy();
            ArrayNode results = copy.putArray("results");
            for (int i = 0; i < count; i++) {
                ObjectNode result = ((ObjectNode) byId.get(RequestSerializer.operationId(offset + i))).deepCopy();
                result.put("operationId", RequestSerializer.operationId(i));
                results.add(result);
            }
            return mapper.writeValueAsString(copy);
        } catch (Exception e) {
            throw new TransportException("Cannot split engine reply: " + e.getMessage(), 200, e, false);
        }
    }

    private ServerException serverError(JsonNode root) {
        String message = root.hasNonNull("error") ? root.get("error").asText() : null;
        if (message == null) {
            // ApiResponse.errors[] path (not used by the engine today, but part of its contract)
            List<String> errors = new ArrayList<>();
            root.path("errors").forEach(e -> errors.add(e.isTextual() ? e.asText() : e.toString()));
            message = errors.isEmpty() ? "request failed without an error message" : String.join("; ", errors);
        }
        List<String> stack = new ArrayList<>();
        root.path("stackTrace").forEach(s -> stack.add(s.asText()));
        return new ServerException(message, root.path("exceptionType").asText(""), stack, root.get("debug"));
    }

    private OperationResult result(Operation op, String id, JsonNode node) {
        switch (op.type()) {
            case SELECT:
            case INSERT:
            case UPDATE:
            case DELETE:
            case UPSERT:
            case CLONE: {
                List<Row> rows = new ArrayList<>();
                for (JsonNode r : node.path("rows")) {
                    rows.add(new Row(mapper.convertValue(r, ROW_TYPE), mapper, zone));
                }
                int count = node.has("rowCount") ? node.get("rowCount").asInt() : rows.size();
                return new RowsResult(id, count, rows);
            }
            case CREATE_WORKSHEET:
                return new WorksheetCreated(id, node.path("worksheet").asText(op.worksheet()),
                        node.path("sheetId").asLong(-1));
            case GET_COLUMNS:
                return new ColumnsResult(id, node.path("worksheet").asText(op.worksheet()),
                        texts(node.path("columns")));
            case ADD_COLUMNS:
                return new AddColumnsResult(id, node.path("worksheet").asText(op.worksheet()),
                        texts(node.path("columns")), texts(node.path("skippedColumns")),
                        node.path("startColumn").asInt(), node.path("count").asInt());
            case CLEAR_WORKSHEET:
                return new ClearResult(id, node.path("worksheet").asText(op.worksheet()),
                        node.path("rowsCleared").asInt(), node.path("columnsCleared").asInt());
            default:
                throw new IllegalStateException("Unhandled operation type " + op.type());
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.isNull() ? "" : n.asText()));
        return out;
    }

    static String abbreviate(String s) {
        if (s == null) {
            return "<empty>";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}
