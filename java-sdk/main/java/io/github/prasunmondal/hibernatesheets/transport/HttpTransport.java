package io.github.prasunmondal.hibernatesheets.transport;

import io.github.prasunmondal.hibernatesheets.exception.TransportException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP transport for an Apps Script web app deployment ({@code https://script.google.com/macros/s/.../exec}).
 *
 * <p>Apps Script answers a POST with a {@code 302} to a one-time {@code script.googleusercontent.com}
 * URL that must be fetched with GET. Redirects are followed manually so that behaviour is explicit and
 * the {@code Authorization} header is never forwarded to a different host.</p>
 */
public final class HttpTransport implements Transport {

    private static final int MAX_REDIRECTS = 5;
    private static final Pattern HTML_TITLE = Pattern.compile("<title>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final URI endpoint;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final Supplier<String> accessToken;
    private final String userAgent;

    private HttpTransport(Builder b) {
        this.endpoint = Objects.requireNonNull(b.endpoint, "endpoint");
        this.requestTimeout = b.requestTimeout;
        this.accessToken = b.accessToken;
        this.userAgent = b.userAgent;
        this.http = b.httpClient != null ? b.httpClient : HttpClient.newBuilder()
                .connectTimeout(b.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static Builder builder(String endpoint) {
        return new Builder(URI.create(endpoint));
    }

    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    public URI endpoint() {
        return endpoint;
    }

    @Override
    public String send(String requestJson) {
        String authorization = authorizationHeader();
        HttpRequest.Builder first = base(endpoint)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));
        if (authorization != null) {
            first.header("Authorization", authorization);
        }
        HttpRequest request = first.build();

        URI current = endpoint;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = exchange(request);
            int status = response.statusCode();

            if (isRedirect(status)) {
                String location = response.headers().firstValue("Location").orElseThrow(() ->
                        new TransportException("Redirect " + status + " without Location header", status, null, false));
                URI next = current.resolve(location);
                boolean sameHost = Objects.equals(next.getHost(), endpoint.getHost());
                HttpRequest.Builder follow = base(next);
                if (status == 307 || status == 308) {
                    follow.header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));
                } else {
                    follow.GET();
                }
                if (sameHost && authorization != null) {
                    follow.header("Authorization", authorization);
                }
                request = follow.build();
                current = next;
                continue;
            }

            String body = response.body();
            if (status == 200) {
                if (looksLikeHtml(body)) {
                    throw new TransportException(htmlHint(body), status, null, false);
                }
                return body;
            }
            boolean retryable = status == 408 || status == 429 || status >= 500;
            String hint = (status == 401 || status == 403)
                    ? " — the deployment rejected the caller; check 'Who has access' on the web app deployment"
                    + " or supply an OAuth access token"
                    : "";
            throw new TransportException("HTTP " + status + " from " + current.getHost() + hint, status, null, retryable);
        }
        throw new TransportException("Too many redirects (>" + MAX_REDIRECTS + ")", -1, null, false);
    }

    private HttpRequest.Builder base(URI uri) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (userAgent != null) {
            b.header("User-Agent", userAgent);
        }
        return b;
    }

    private String authorizationHeader() {
        if (accessToken == null) {
            return null;
        }
        String token = accessToken.get();
        return token == null || token.isBlank() ? null : "Bearer " + token;
    }

    private HttpResponse<String> exchange(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new TransportException("Timed out calling " + request.uri().getHost()
                    + " (Apps Script executions can take several seconds on large sheets;"
                    + " consider a longer requestTimeout)", -1, e, true);
        } catch (IOException e) {
            throw new TransportException("I/O error calling " + request.uri().getHost() + ": " + e.getMessage(),
                    -1, e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Interrupted while calling the engine", -1, e, false);
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean looksLikeHtml(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.stripLeading();
        return trimmed.startsWith("<");
    }

    private static String htmlHint(String body) {
        Matcher m = HTML_TITLE.matcher(body);
        String title = m.find() ? m.group(1).replaceAll("\\s+", " ").trim() : "untitled page";
        return "Expected JSON from the engine but received an HTML page (\"" + title + "\"). Usual causes:"
                + " the URL is not the /exec URL of a web app deployment, the deployment's 'Who has access'"
                + " requires sign-in, or the script failed before reaching doPost.";
    }

    public static final class Builder {
        private final URI endpoint;
        private HttpClient httpClient;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(90);
        private Supplier<String> accessToken;
        private String userAgent = "hibernate-sheets-sdk/0.1";

        private Builder(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        }

        /** Supply your own client. It must be configured with {@code Redirect.NEVER}. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout);
            return this;
        }

        /** Per HTTP exchange. Apps Script itself caps executions at 6 minutes. */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout);
            return this;
        }

        /** OAuth access token, sent as {@code Authorization: Bearer}. Called once per request. */
        public Builder accessToken(Supplier<String> accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public HttpTransport build() {
            return new HttpTransport(this);
        }
    }
}
