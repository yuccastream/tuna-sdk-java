package am.tuna.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.SshException;
import org.junit.jupiter.api.Test;

class TunaTest {
    private static ServerException error(int status, String code) {
        return new ServerException(status, code, "boom", "", Duration.ZERO);
    }

    @Test
    void permanentCodes() {
        for (String code : List.of("BadRequest", "Unauthorized", "AuthorizationRequired", "Forbidden", "NotFound",
                "PaymentRequired", "UpgradeRequired")) {
            assertTrue(Tuna.isPermanent(error(400, code)), code);
        }
        assertFalse(Tuna.isPermanent(error(503, "ServiceUnavailable")));
        assertFalse(Tuna.isPermanent(error(500, "")));
        assertFalse(Tuna.isPermanent(new KeepAliveTimeoutException()));
        assertFalse(Tuna.isPermanent(new IOException("network")));
        assertFalse(Tuna.isPermanent(null));
        assertTrue(Tuna.isPermanent(new TerminatedException("deleted")));
        assertTrue(Tuna.isPermanent(new IOException("wrapped", new TerminatedException("deleted"))));
    }

    @Test
    void clientErrorsWithoutCodeDependOnFirstConnect() {
        ServerException after = error(404, "");
        assertFalse(Tuna.isPermanent(after));
        ServerException before = error(404, "");
        before.markBeforeFirstConnect();
        assertTrue(Tuna.isPermanent(before));
        for (int status : new int[] {408, 429, 500, 502}) {
            ServerException e = error(status, "");
            e.markBeforeFirstConnect();
            assertFalse(Tuna.isPermanent(e), String.valueOf(status));
        }
        assertTrue(Tuna.isPermanent(error(301, "")));
    }

    @Test
    void apiErrors() {
        ServerException e = assertThrows(ServerException.class, () -> Api.handle(401, "", "",
                "{\"code\":\"Unauthorized\",\"error\":\"bad key\",\"request_id\":\"r1\",\"extra\":[1,{}]}"));
        assertEquals(401, e.status());
        assertEquals("Unauthorized", e.code());
        assertEquals("r1", e.requestId());
        assertEquals("tuna: bad key (request id r1)", e.getMessage());

        e = assertThrows(ServerException.class, () -> Api.handle(404, "", "", "<html>not found</html>"));
        assertEquals("", e.code());
        assertEquals("tuna: server responded with status 404", e.getMessage());

        e = assertThrows(ServerException.class, () -> Api.handle(301, "https://elsewhere/", "", ""));
        assertEquals("tuna: server redirects to \"https://elsewhere/\", check the server URL", e.getMessage());

        e = assertThrows(ServerException.class, () -> Api.handle(503, "", "3", "{\"code\":\"X\",\"error\":\"busy\"}"));
        assertEquals(Duration.ofSeconds(3), e.retryAfter());
        e = assertThrows(ServerException.class, () -> Api.handle(503, "", "3600", ""));
        assertEquals(Duration.ofMinutes(5), e.retryAfter());
        assertEquals(Duration.ZERO, Api.parseRetryAfter("soon"));
        assertEquals(Duration.ZERO, Api.parseRetryAfter("-1"));
    }

    @Test
    void retryDelay() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Duration d = Tunnel.retryDelay(Duration.ofSeconds(1), new IOException());
            assertTrue(d.compareTo(Duration.ofSeconds(1)) >= 0 && d.compareTo(Duration.ofSeconds(2)) < 0, d.toString());
            Duration big = Tunnel.retryDelay(Duration.ofSeconds(64), new IOException());
            assertTrue(big.compareTo(Duration.ofSeconds(15)) <= 0, big.toString());
            seen.add(d.toMillis());
        }
        assertTrue(seen.size() > 1, "no jitter");
        ServerException busy = new ServerException(503, "", "busy", "", Duration.ofSeconds(30));
        assertEquals(Duration.ofSeconds(30), Tunnel.retryDelay(Duration.ofSeconds(1), busy));
    }

    @Test
    void publicUrlFallback() {
        Api.ConnectResponse cr = Api.ConnectResponse.from(Json.parseObject(
                "{\"domain_name\":\"demo.tuna.am\",\"endpoint_port\":40001,\"host_keys\":[{\"key\":\"k\"}]}"));
        assertEquals("https://demo.tuna.am", cr.publicUrl("http"));
        assertEquals("tcp://demo.tuna.am:40001", cr.publicUrl("tcp"));
        assertEquals(List.of("k"), cr.hostKeys());
        cr = Api.ConnectResponse.from(Json.parseObject("{\"public_url\":\"https://x.tuna.am\"}"));
        assertEquals("https://x.tuna.am", cr.publicUrl("http"));
    }

    @Test
    void localAddress() throws IOException {
        Tuna.checkLocalAddr("127.0.0.1:8080");
        Tuna.checkLocalAddr("[::1]:8080");
        Tuna.checkLocalAddr("unix:///tmp/app.sock");
        for (String bad : new String[] {"", "8080", "host:", "host:http", "host:70000", "unix://"}) {
            assertThrows(IllegalArgumentException.class, () -> Tuna.checkLocalAddr(bad), bad);
        }
        InetSocketAddress a = Tunnel.hostPort("[::1]:22");
        assertEquals("::1", a.getHostString());
        assertEquals(22, a.getPort());
    }

    @Test
    void optionsValidation() {
        Options ok = new Options().apiKey("k").protocol("http");
        Options v = Tunnel.validate(ok);
        assertEquals(Tuna.DEFAULT_SERVER_URL, v.serverUrl());
        assertEquals("tuna-sdk-java/" + Tuna.VERSION, v.clientName());
        assertThrows(IllegalArgumentException.class, () -> Tunnel.validate(new Options().apiKey("k").protocol("udp")));
        assertThrows(IllegalArgumentException.class,
                () -> Tunnel.validate(new Options().apiKey("k").protocol("http").clientName("a\r\nb")));
        assertThrows(IllegalArgumentException.class,
                () -> Tunnel.validate(new Options().apiKey("k").protocol("http").serverUrl("ftp://x")));
        assertThrows(IllegalArgumentException.class,
                () -> Tunnel.validate(new Options().apiKey("k").protocol("http").serverUrl("https://")));
        if (System.getenv("TUNA_API_KEY") == null) {
            assertThrows(IllegalArgumentException.class, () -> Tunnel.validate(new Options().protocol("http")));
        }
    }

    @Test
    void json() {
        Map<String, Object> m = Json.parseObject(" {\"a\":\"x\\\"\\n\\u0041\",\"n\":-1.5e2,\"i\":42,\"b\":true,"
                + "\"z\":null,\"l\":[1,\"2\",[]]} ");
        assertEquals("x\"\nA", m.get("a"));
        assertEquals(-150.0, m.get("n"));
        assertEquals(42L, m.get("i"));
        assertEquals(Boolean.TRUE, m.get("b"));
        assertTrue(m.containsKey("z"));
        assertEquals(42, Json.num(m, "i"));
        assertEquals("", Json.str(m, "missing"));
        assertEquals("{\"s\":\"q\\\"\\\\\\u0001\"}", Json.write(Map.of("s", "q\"\\\u0001")));
        assertEquals("[1,\"a\",true]", Json.write(List.of(1, "a", true)));
        for (String bad : new String[] {"", "{", "{\"a\"}", "[1,]", "{\"a\":1}x", "nul"}) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void eventLines() throws IOException {
        List<String> lines = new ArrayList<>();
        try (OutputStream sink = new Tunnel.LineSink(lines::add)) {
            sink.write("{\"type\":\"session_".getBytes(StandardCharsets.UTF_8));
            sink.write("expiring\",\"expires_in\":300}\n{\"type\":\"ter".getBytes(StandardCharsets.UTF_8));
            sink.write("minate\",\"reason\":\"deleted\"}\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(List.of("{\"type\":\"session_expiring\",\"expires_in\":300}",
                "{\"type\":\"terminate\",\"reason\":\"deleted\"}"), lines);
    }

    @Test
    void terminatedMessages() {
        assertEquals("tuna: tunnel was deleted", new TerminatedException("deleted").getMessage());
        assertEquals("tuna: tunnel terminated by server (other)", new TerminatedException("other").getMessage());
    }

    @Test
    void heartbeatFailureIsKeepAliveTimeout() {
        assertTrue(Tunnel.isHeartbeatFailure(new SshException("Got 2 heartbeat requests without reply")));
        assertFalse(Tunnel.isHeartbeatFailure(new SshException("Server key did not validate")));
        assertFalse(Tunnel.isHeartbeatFailure(null));
        assertTrue(new KeepAliveTimeoutException().getMessage().contains("keepalive timeout"));
    }

    @Test
    void keepAliveMatchesProtocol() {
        assertEquals(Duration.ofSeconds(15),
                Tunnel.KEEPALIVE_INTERVAL.multipliedBy(Tunnel.KEEPALIVE_MAX_NO_REPLY + 1));
    }

    @Test
    void goNames() {
        assertEquals("linux", Tunnel.goos("Linux"));
        assertEquals("darwin", Tunnel.goos("Mac OS X"));
        assertEquals("windows", Tunnel.goos("Windows 11"));
        assertEquals("amd64", Tunnel.goarch("x86_64"));
        assertEquals("arm64", Tunnel.goarch("aarch64"));
    }

    @Test
    void permanentApiErrorEndsStartWithoutRetry() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> bodies = new CopyOnWriteArrayList<>();
        AtomicReference<String> agent = new AtomicReference<>();
        srv.createContext("/v1/connect", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            agent.set(ex.getRequestHeaders().getFirst("User-Agent"));
            byte[] b = "{\"code\":\"Unauthorized\",\"error\":\"invalid key\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        srv.start();
        List<Event> events = new CopyOnWriteArrayList<>();
        try {
            Options o = new Options().apiKey("key-1").protocol("tcp")
                    .serverUrl("http://127.0.0.1:" + srv.getAddress().getPort() + "/")
                    .cidrAllow(List.of("10.0.0.0/8")).listener(events::add);
            ServerException e = assertThrows(ServerException.class, () -> Tuna.forward(o, "127.0.0.1:1"));
            assertTrue(Tuna.isPermanent(e));
        } finally {
            srv.stop(0);
        }
        assertEquals(1, bodies.size());
        assertTrue(events.isEmpty(), events.toString());
        Map<String, Object> body = Json.parseObject(bodies.get(0));
        assertEquals(2L, body.get("client_version"));
        assertEquals("key-1", body.get("token"));
        assertEquals("tuna-sdk-java/" + Tuna.VERSION, body.get("client_name"));
        assertEquals(Tuna.VERSION, body.get("sdk_version"));
        assertEquals("", body.get("tunnel_session_id"));
        assertEquals("127.0.0.1:1", body.get("forwards_to"));
        assertEquals(List.of("10.0.0.0/8"), body.get("cidr_allow_list"));
        assertFalse(body.containsKey("cidr_deny_list"));
        assertEquals("tuna-sdk-java/" + Tuna.VERSION, agent.get());
    }

    @Test
    void connectTimeoutReportsLastAttempt() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/v1/connect", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        srv.start();
        try {
            Options o = new Options().apiKey("k").protocol("http").connectTimeout(Duration.ofSeconds(3))
                    .serverUrl("http://127.0.0.1:" + srv.getAddress().getPort());
            IOException e = assertThrows(IOException.class, () -> Tuna.listen(o));
            assertTrue(e.getMessage().startsWith("tuna: tunnel did not come up within 3s (last attempt: "),
                    e.getMessage());
            assertInstanceOf(ServerException.class, e.getCause());
        } finally {
            srv.stop(0);
        }
    }
}
