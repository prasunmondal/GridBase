package io.github.prasunmondal.gridbase.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.prasunmondal.gridbase.query.Filter;
import io.github.prasunmondal.gridbase.query.Sort;
import io.github.prasunmondal.gridbase.spec.Assignment;
import io.github.prasunmondal.gridbase.spec.Operation;

import java.time.ZoneId;
import java.util.List;

/**
 * Turns {@link Operation}s into the request JSON read by the engine's {@code RequestParser}:
 * <pre>
 * { "requestId": "...", "operations": [ { "id", "type", "spreadsheetId", "worksheet",
 *   "where": [...], "orderBy": [...], "select": [...], "values": {...}, "rows": [...],
 *   "columns": [...], "skipExisting", "limit", "offset" } ] }
 * </pre>
 */
public final class RequestSerializer {

    private final ObjectMapper mapper;
    private final ZoneId zone;

    public RequestSerializer(ObjectMapper mapper, ZoneId zone) {
        this.mapper = mapper;
        this.zone = zone;
    }

    /** Operation ids are positional ({@code op-1}, {@code op-2}, ...) so results can be matched back. */
    public static String operationId(int index) {
        return "op-" + (index + 1);
    }

    public String serialize(String requestId, List<Operation> operations) {
        ObjectNode root = mapper.createObjectNode();
        root.put("requestId", requestId);
        ArrayNode ops = root.putArray("operations");
        for (int i = 0; i < operations.size(); i++) {
            ops.add(operation(operationId(i), operations.get(i)));
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize request", e);
        }
    }

    ObjectNode operation(String id, Operation op) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        node.put("type", op.type().name());
        node.put("spreadsheetId", op.spreadsheetId());
        node.put("worksheet", op.worksheet());

        if (!op.filters().isEmpty()) {
            ArrayNode where = node.putArray("where");
            op.filters().forEach(f -> where.add(filter(f)));
        }
        if (!op.orderBy().isEmpty()) {
            ArrayNode orderBy = node.putArray("orderBy");
            for (Sort s : op.orderBy()) {
                orderBy.addObject().put("column", s.column()).put("direction", s.direction().name());
            }
        }
        if (!op.select().isEmpty()) {
            ArrayNode select = node.putArray("select");
            op.select().forEach(select::add);
        }
        if (!op.values().isEmpty()) {
            node.set("values", assignments(op.values()));
        }
        if (!op.rows().isEmpty()) {
            ArrayNode rows = node.putArray("rows");
            op.rows().forEach(r -> rows.add(assignments(r)));
        }
        if (!op.columns().isEmpty()) {
            ArrayNode columns = node.putArray("columns");
            op.columns().forEach(columns::add);
        }
        if (op.skipExisting()) {
            node.put("skipExisting", true);
        }
        if (op.limit() >= 0) {
            node.put("limit", op.limit());
        }
        if (op.offset() > 0) {
            node.put("offset", op.offset());
        }
        return node;
    }

    private ObjectNode filter(Filter f) {
        ObjectNode node = mapper.createObjectNode();
        node.put("column", f.column());
        node.put("operator", f.operator().name());
        switch (f.operator()) {
            case IS_NULL:
            case IS_NOT_NULL:
                break;
            case EQUALS:
            case NOT_EQUALS:
            case CONTAINS:
            case STARTS_WITH:
            case ENDS_WITH:
                // Engine evaluates String(cell) === expected, without String() on expected.
                node.put("value", JsCompat.toJsString(f.value()));
                break;
            case GREATER_THAN:
            case GREATER_THAN_EQUALS:
            case LESS_THAN:
            case LESS_THAN_EQUALS:
                node.set("value", toNode(JsCompat.toComparable(f.value(), zone)));
                break;
            case BETWEEN:
                node.set("minimum", toNode(JsCompat.toComparable(f.minimum(), zone)));
                node.set("maximum", toNode(JsCompat.toComparable(f.maximum(), zone)));
                break;
            case IN:
                ArrayNode values = node.putArray("values");
                f.values().forEach(v -> values.add(toNode(v)));
                break;
            default:
                throw new IllegalStateException("Unhandled operator " + f.operator());
        }
        return node;
    }

    /**
     * Always uses the explicit {@code {"value": v, "operation": "SET"}} form: the engine treats any
     * JSON object as an operation descriptor, so a bare object/map value would be misread.
     */
    private ObjectNode assignments(List<Assignment> assignments) {
        ObjectNode node = mapper.createObjectNode();
        for (Assignment a : assignments) {
            ObjectNode entry = node.putObject(a.column());
            entry.set("value", toNode(a.value()));
            entry.put("operation", a.kind().name());
        }
        return node;
    }

    private JsonNode toNode(Object value) {
        return value == null ? NullNode.getInstance() : mapper.valueToTree(value);
    }
}
