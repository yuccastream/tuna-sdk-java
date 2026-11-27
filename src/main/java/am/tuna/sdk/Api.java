package am.tuna.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLContext;

final class Api {
    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final int MAX_RESPONSE_SIZE = 1 << 20;
    static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(5);

    private final HttpClient hc;

    Api(SSLContext sslContext) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT);
        if (sslContext != null) {
            b.sslContext(sslContext);
        }
        hc = b.build();
    }

    record ConnectResponse(
            String sessionId, String token, String address, String banner, String publicUrl, String domainName,
            long endpointPort, List<String> hostKeys) {

        static ConnectResponse from(Map<String, Object> m) {
            List<String> keys = new ArrayList<>();
            if (m.get("host_keys") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> hk && hk.get("key") instanceof String k) {
                        keys.add(k);
                    }
                }
            }
            return new ConnectResponse(
                    Json.str(m, "tunnel_session_id"), Json.str(m, "token"), Json.str(m, "address"),
                    Json.str(m, "banner"), Json.str(m, "public_url"), Json.str(m, "domain_name"),
                    Json.num(m, "endpoint_port"), keys);
        }

        String publicUrl(String protocol) {
            if (!publicUrl.isEmpty()) {
                return publicUrl;
            }
            if (protocol.equals("tcp")) {
                return "tcp://" + domainName + ":" + endpointPort;
            }
            return "https://" + domainName;
        }
    }

    static void checkServerUrl(String s) {
        URI u;
        try {
            u = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("tuna: invalid server URL: " + e.getMessage(), e);
        }
        if (!("https".equals(u.getScheme()) || "http".equals(u.getScheme())) || u.getHost() == null) {
            throw new IllegalArgumentException("tuna: invalid server URL \"" + s + "\": want https://host");
        }
    }

    Map<String, Object> post(String serverUrl, String path, String userAgent, Map<String, Object> body,
            CompletableFuture<Void> cancel) throws IOException {
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(TIMEOUT)
                    .header("User-Agent", userAgent)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new IOException("tuna: invalid server URL: " + e.getMessage(), e);
        }
        CompletableFuture<HttpResponse<InputStream>> f = hc.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> resp = await(f, cancel);
        byte[] raw;
        try (InputStream in = resp.body()) {
            raw = in.readNBytes(MAX_RESPONSE_SIZE);
        }
        return handle(resp.statusCode(), resp.headers().firstValue("Location").orElse(""),
                resp.headers().firstValue("Retry-After").orElse(""), new String(raw, StandardCharsets.UTF_8));
    }

    static Map<String, Object> handle(int status, String location, String retryAfter, String body)
            throws IOException {
        if (status >= 200 && status <= 299) {
            try {
                return Json.parseObject(body);
            } catch (IllegalArgumentException e) {
                throw new IOException("tuna: invalid response from server: " + e.getMessage(), e);
            }
        }
        String code = "";
        String text = "";
        String requestId = "";
        if (status >= 300 && status < 400) {
            text = "server redirects to \"" + location + "\", check the server URL";
        } else {
            try {
                Map<String, Object> m = Json.parseObject(body);
                code = Json.str(m, "code");
                text = Json.str(m, "error");
                requestId = Json.str(m, "request_id");
            } catch (IllegalArgumentException ignored) {
                code = "";
            }
            if (text.isEmpty()) {
                text = "server responded with status " + status;
            }
        }
        throw new ServerException(status, code, text, requestId, parseRetryAfter(retryAfter));
    }

    static Duration parseRetryAfter(String v) {
        try {
            long sec = Long.parseLong(v.trim());
            if (sec > 0) {
                Duration d = Duration.ofSeconds(sec);
                return d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d;
            }
        } catch (NumberFormatException ignored) {
            return Duration.ZERO;
        }
        return Duration.ZERO;
    }

    static <T> T await(CompletableFuture<T> f, CompletableFuture<Void> cancel) throws IOException {
        try {
            CompletableFuture.anyOf(f, cancel).get();
            if (!f.isDone()) {
                f.cancel(true);
                throw new ClosedChannelException();
            }
            return f.get();
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("tuna: interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            throw new IOException(c.getMessage(), c);
        }
    }
}
