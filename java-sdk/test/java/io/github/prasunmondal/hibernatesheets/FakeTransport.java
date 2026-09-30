package io.github.prasunmondal.hibernatesheets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.prasunmondal.hibernatesheets.transport.Transport;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Records request bodies and answers with a canned reply computed from the request. */
final class FakeTransport implements Transport {

    static final ObjectMapper JSON = new ObjectMapper();

    final List<JsonNode> requests = new ArrayList<>();
    private final Function<JsonNode, String> responder;

    FakeTransport(Function<JsonNode, String> responder) {
        this.responder = responder;
    }

    /** Replies success with the given per-operation result JSON (operationId filled in). */
    static FakeTransport replying(String... resultBodies) {
        return new FakeTransport(req -> {
            StringBuilder sb = new StringBuilder("{\"success\":true,\"requestId\":\"")
                    .append(req.path("requestId").asText()).append("\",\"executionTime\":12,\"results\":[");
            for (int i = 0; i < resultBodies.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"operationId\":\"op-").append(i + 1).append("\",")
                        .append(resultBodies[i]).append('}');
            }
            return sb.append("],\"errors\":[],\"debug\":[]}").toString();
        });
    }

    @Override
    public String send(String requestJson) {
        try {
            JsonNode req = JSON.readTree(requestJson);
            requests.add(req);
            return responder.apply(req);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    JsonNode lastOperation() {
        return requests.get(requests.size() - 1).path("operations").get(0);
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
