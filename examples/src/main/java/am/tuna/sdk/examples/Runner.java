package am.tuna.sdk.examples;

import am.tuna.sdk.Connection;
import am.tuna.sdk.Event;
import am.tuna.sdk.EventType;
import am.tuna.sdk.Options;
import am.tuna.sdk.TerminatedException;
import am.tuna.sdk.Tuna;
import am.tuna.sdk.Tunnel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class Runner {
    private static final String USAGE = """
            Usage:
              runner http|tcp <addr> [-s SUBDOMAIN] [-d DOMAIN] [-p PORT] [--proxy-protocol N]
                                     [--cidr-allow CIDR]... [--cidr-deny CIDR]... [--listen] [--log-format json]

            addr: 8080, 10.0.0.1:8080, http://localhost:8080, unix:///path/to.sock
            Environment: TUNA_API_KEY, TUNA_ADDR, TUNA_LOCATION
            """;

    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    private static final Set<Proxy> ACTIVE = ConcurrentHashMap.newKeySet();
    private static volatile boolean exiting;
    private static boolean json;

    private Runner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("http") || args[0].equals("tcp"))) {
            usage();
        }
        Options o = new Options().protocol(args[0]).listener(Runner::print);
        String addr = null;
        boolean listen = false;
        List<String> allow = new ArrayList<>();
        List<String> deny = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-s" -> o.subdomain(value(args, ++i));
                case "-d" -> o.domain(value(args, ++i));
                case "-p" -> o.port(value(args, ++i));
                case "--proxy-protocol" -> o.proxyProtocol(Integer.parseInt(value(args, ++i)));
                case "--cidr-allow" -> allow.add(value(args, ++i));
                case "--cidr-deny" -> deny.add(value(args, ++i));
                case "--listen" -> listen = true;
                case "--log-format" -> json = value(args, ++i).equals("json");
                default -> {
                    if (a.startsWith("-") || addr != null) {
                        usage();
                    }
                    addr = localAddr(a);
                }
            }
        }
        if (addr == null) {
            usage();
        }
        o.cidrAllow(allow).cidrDeny(deny).serverUrl(env("TUNA_ADDR")).location(env("TUNA_LOCATION"));
        if (env("TUNA_API_KEY").isEmpty()) {
            System.err.println("Error: API key is required: set TUNA_API_KEY");
            System.exit(2);
        }
        if (listen) {
            listen(o, addr);
        } else {
            forward(o, addr);
        }
    }

    private static void forward(Options o, String addr) throws InterruptedException {
        Tunnel[] tunnel = new Tunnel[1];
        onSignal(() -> {
            if (tunnel[0] != null) {
                tunnel[0].close();
            }
        });
        try {
            tunnel[0] = Tuna.forward(o, addr);
            tunnel[0].await();
        } catch (IOException e) {
            fail(e);
        }
    }

    private static void listen(Options o, String addr) throws InterruptedException {
        Tunnel tunnel;
        try {
            tunnel = Tuna.listen(o);
        } catch (IOException e) {
            fail(e);
            return;
        }
        onSignal(() -> {
            tunnel.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (ACTIVE.stream().anyMatch(Proxy::busy) && System.nanoTime() < deadline) {
                sleep(50);
            }
        });
        while (true) {
            Connection c;
            try {
                c = tunnel.accept();
            } catch (ClosedChannelException e) {
                Thread.currentThread().join();
                return;
            } catch (IOException e) {
                fail(e);
                return;
            }
            Proxy p = new Proxy();
            ACTIVE.add(p);
            Thread t = new Thread(() -> p.run(c, addr));
            t.setDaemon(true);
            t.start();
        }
    }

    private static final class Proxy {
        private final AtomicLong lastUp = new AtomicLong(System.nanoTime());
        private final AtomicLong lastDown = new AtomicLong(System.nanoTime());

        boolean busy() {
            long up = lastUp.get();
            long down = lastDown.get();
            return up > down || System.nanoTime() - Math.max(up, down) < TimeUnit.MILLISECONDS.toNanos(300);
        }

        void run(Connection c, String addr) {
            try (c; Socket local = dial(addr)) {
                Thread up = new Thread(() -> {
                    copy(c.getInputStream(), outputOf(local), lastUp);
                    shutdownOutput(local);
                });
                up.setDaemon(true);
                up.start();
                copy(local.getInputStream(), c.getOutputStream(), lastDown);
            } catch (IOException e) {
                print(new Event(EventType.LOCAL_ERROR, "", "", "", e, Duration.ZERO, Duration.ZERO));
            } finally {
                ACTIVE.remove(this);
            }
        }
    }

    private static Socket dial(String addr) throws IOException {
        int i = addr.lastIndexOf(':');
        return new Socket(addr.substring(0, i), Integer.parseInt(addr.substring(i + 1)));
    }

    private static OutputStream outputOf(Socket s) {
        try {
            return s.getOutputStream();
        } catch (IOException e) {
            return OutputStream.nullOutputStream();
        }
    }

    private static void shutdownOutput(Socket s) {
        try {
            s.shutdownOutput();
        } catch (IOException ignored) {
            return;
        }
    }

    private static void copy(InputStream in, OutputStream out, AtomicLong last) {
        byte[] buf = new byte[32 << 10];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                last.set(System.nanoTime());
                out.write(buf, 0, n);
                out.flush();
                last.set(System.nanoTime());
            }
        } catch (IOException ignored) {
            return;
        }
    }

    private static void onSignal(Runnable stop) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (exiting) {
                return;
            }
            stop.run();
            OUT.flush();
            Runtime.getRuntime().halt(0);
        }));
    }

    private static void fail(IOException e) {
        if (!(e instanceof TerminatedException)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", "error");
            m.put("error", e.getMessage());
            emit(m, "Error: " + e.getMessage());
        }
        exiting = true;
        OUT.flush();
        Runtime.getRuntime().halt(1);
    }

    private static void print(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", e.type().value());
        m.put("url", e.url());
        m.put("message", e.message());
        m.put("reason", e.reason());
        if (e.error() != null) {
            m.put("error", e.error().getMessage());
        }
        m.put("retry_in", e.retryIn().toMillis() / 1000.0);
        m.put("expires_in", (double) e.expiresIn().toSeconds());
        String text = switch (e.type()) {
            case CONNECTED, RECONNECTED -> "Forwarding " + e.url();
            case RECONNECTING -> "Connection error (" + e.error().getMessage() + "), retry in " + e.retryIn();
            case SESSION_EXPIRING -> "Session expires in " + e.expiresIn();
            case LOCAL_ERROR -> "Local service unavailable: " + e.error().getMessage();
            default -> e.message();
        };
        emit(m, text);
    }

    private static synchronized void emit(Map<String, Object> fields, String text) {
        if (!json) {
            System.err.println(text);
            return;
        }
        StringBuilder b = new StringBuilder("{\"time\":");
        quote(b, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        for (Map.Entry<String, Object> f : fields.entrySet()) {
            Object v = f.getValue();
            if (v == null || "".equals(v) || Double.valueOf(0).equals(v)) {
                continue;
            }
            b.append(",\"").append(f.getKey()).append("\":");
            if (v instanceof Double d) {
                b.append(d);
            } else {
                quote(b, v.toString());
            }
        }
        OUT.println(b.append('}'));
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        b.append('"');
    }

    private static String localAddr(String s) {
        if (s.startsWith("unix://")) {
            return s;
        }
        if (s.contains("://")) {
            URI u = URI.create(s);
            if (!"http".equals(u.getScheme())) {
                System.err.println("Error: scheme \"" + u.getScheme() + "\" is not supported");
                System.exit(2);
            }
            return u.getHost() + ":" + (u.getPort() < 0 ? 80 : u.getPort());
        }
        if (!s.contains(":")) {
            return "127.0.0.1:" + s;
        }
        return s;
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            usage();
        }
        return args[i];
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void usage() {
        System.err.print(USAGE);
        exiting = true;
        System.exit(2);
    }
}
