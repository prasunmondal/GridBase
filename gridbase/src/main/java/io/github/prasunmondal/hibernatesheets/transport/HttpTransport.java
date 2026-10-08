package io.github.prasunmondal.hibernatesheets.transport;

import io.github.prasunmondal.hibernatesheets.exception.TransportException;
import io.github.prasunmondal.hibernatesheets.internal.Compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
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
 *
 * <p>Built on {@link HttpURLConnection} so it runs on both the JDK and Android.</p>
 */
public final class HttpTransport implements Transport {

    private static final int MAX_REDIRECTS = 5;
    private static final Pattern HTML_TITLE = Pattern.compile("<title>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final URI endpoint;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Supplier<String> accessToken;
    private final String userAgent;

    private HttpTransport(Builder b) {
        this.endpoint = Objects.requireNonNull(b.endpoint, "endpoint");
        this.connectTimeout = b.connectTimeout;
        this.requestTimeout = b.requestTimeout;
        this.accessToken = b.accessToken;
        this.userAgent = b.userAgent;
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
        byte[] json = requestJson.getBytes(StandardCharsets.UTF_8);

        URI current = endpoint;
        byte[] body = json;
        String auth = authorization;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Response response = exchange(current, body, auth);
            int status = response.status;

            if (isRedirect(status)) {
                if (response.location == null) {
                    throw new TransportException("Redirect " + status + " without Location header", status, null, false);
                }
                URI next = current.resolve(response.location);
                boolean sameHost = Objects.equals(next.getHost(), endpoint.getHost());
                body = (status == 307 || status == 308) ? json : null;
                auth = sameHost ? authorization : null;
                current = next;
                continue;
            }

            if (status == 200) {
                if (looksLikeHtml(response.body)) {
                    throw new TransportException(htmlHint(response.body), status, null, false);
                }
                return response.body;
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

    private static final class Response {
        final int status;
        final String location;
        final String body;

        Response(int status, String location, String body) {
            this.status = status;
            this.location = location;
            this.body = body;
        }
    }

    /** One HTTP exchange: POST when {@code body} is non-null, otherwise GET. */
    private Response exchange(URI uri, byte[] body, String authorization) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setConnectTimeout(timeoutMillis(connectTimeout));
            conn.setReadTimeout(timeoutMillis(requestTimeout));
            conn.setRequestProperty("Accept", "application/json");
            if (userAgent != null) {
                conn.setRequestProperty("User-Agent", userAgent);
            }
            if (authorization != null) {
                conn.setRequestProperty("Authorization", authorization);
            }
            if (body != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            } else {
                conn.setRequestMethod("GET");
            }
            int status = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(status, location, readUtf8(in));
        } catch (SocketTimeoutException e) {
            throw new TransportException("Timed out calling " + uri.getHost()
                    + " (Apps Script executions can take several seconds on large sheets;"
                    + " consider a longer requestTimeout)", -1, e, true);
        } catch (IOException e) {
            throw new TransportException("I/O error calling " + uri.getHost() + ": " + e.getMessage(),
                    -1, e, true);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readUtf8(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int timeoutMillis(Duration d) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, d.toMillis()));
    }

    private String authorizationHeader() {
        if (accessToken == null) {
            return null;
        }
        String token = accessToken.get();
        return token == null || Compat.isBlank(token) ? null : "Bearer " + token;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean looksLikeHtml(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = Compat.stripLeading(body);
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
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(90);
        private Supplier<String> accessToken;
        private String userAgent = "hibernate-sheets-sdk/0.1";

        private Builder(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout);
            return this;
        }

        /**
         * Read timeout per HTTP exchange (maximum wait for the reply to start or continue).
         * Apps Script itself caps executions at 6 minutes.
         */
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
