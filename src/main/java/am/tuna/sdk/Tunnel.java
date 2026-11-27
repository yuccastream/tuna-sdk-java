package am.tuna.sdk;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.core.CoreModuleProperties;

/**
 * A running tunnel. It survives network failures: it reconnects with backoff and keeps its public address. It ends
 * on {@link #close()} or a permanent error (see {@link Tuna#isPermanent(Throwable)}).
 */
public final class Tunnel implements AutoCloseable {
    static final AttributeRepository.AttributeKey<Attempt> ATTEMPT = new AttributeRepository.AttributeKey<>();
    static final int CLIENT_VERSION = 2;
    static final String SSH_USER = "tuna";
    static final Duration DIAL_TIMEOUT = Duration.ofSeconds(10);
    static final Duration KEEPALIVE_INTERVAL = Duration.ofSeconds(5);
    static final int KEEPALIVE_MAX_NO_REPLY = 2;
    static final String KEEPALIVE_REQUEST = "keepalive@openssh.com";
    static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(15);
    static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(15);
    static final String EVENTS_COMMAND = "events";
    static final int MAX_EVENT_LINE = 64 << 10;
    private static final Object CLOSED = new Object();

    private final Options o;
    private final String forwardsTo;
    private final Api api;
    private final SshClient ssh;
    private final ExecutorService pipes;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> closeSignal = new CompletableFuture<>();
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final LinkedBlockingQueue<Object> conns = new LinkedBlockingQueue<>();
    private final Object emitLock = new Object();
    private volatile IOException err;
    private volatile IOException lastErr;
    private volatile String url = "";
    private volatile Attempt current;
    private volatile List<PublicKey> expectedKeys = List.of();
    private boolean everUp;
    private String sessionId = "";
    private boolean bannerShown;

    private record Outcome(boolean connected, IOException error) {
    }

    private Tunnel(Options o, String forwardsTo) {
        this.o = o;
        this.forwardsTo = forwardsTo;
        this.api = new Api(o.sslContext());
        this.pipes = forwardsTo.isEmpty() ? null : Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tuna-forward");
            t.setDaemon(true);
            return t;
        });
        this.ssh = newSshClient();
    }

    static Tunnel start(Options opts, String forwardsTo) throws IOException, InterruptedException {
        Options o = validate(opts);
        Tunnel t = new Tunnel(o, forwardsTo);
        t.ssh.start();
        Thread run = new Thread(t::run, "tuna-tunnel");
        run.setDaemon(true);
        run.start();
        CompletableFuture<Object> first = CompletableFuture.anyOf(t.ready, t.done);
        Duration limit = o.connectTimeout();
        try {
            if (limit.isZero() || limit.isNegative()) {
                first.get();
            } else {
                first.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException e) {
            if (t.ready.isDone()) {
                return t;
            }
            t.abort();
            t.done.join();
            throw t.startError("tuna: tunnel did not come up within " + format(limit));
        } catch (InterruptedException e) {
            t.abort();
            throw e;
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        }
        if (t.ready.isDone()) {
            return t;
        }
        throw t.err;
    }

    static Options validate(Options opts) {
        Options o = opts.copy();
        if (o.apiKey().isEmpty()) {
            String env = System.getenv("TUNA_API_KEY");
            o.apiKey(env == null ? "" : env);
        }
        if (o.apiKey().isEmpty()) {
            throw new IllegalArgumentException("tuna: API key is required: set Options.apiKey or TUNA_API_KEY");
        }
        if (!o.protocol().equals("http") && !o.protocol().equals("tcp")) {
            throw new IllegalArgumentException("tuna: unsupported protocol \"" + o.protocol() + "\"");
        }
        if (o.serverUrl().isEmpty()) {
            o.serverUrl(Tuna.DEFAULT_SERVER_URL);
        }
        Api.checkServerUrl(o.serverUrl());
        if (o.clientName().isEmpty()) {
            o.clientName("tuna-sdk-java/" + Tuna.VERSION);
        }
        if (o.clientName().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("tuna: clientName must not contain control characters");
        }
        return o;
    }

    private IOException startError(String msg) {
        IOException last = lastErr;
        if (last == null) {
            return new IOException(msg);
        }
        return new IOException(msg + " (last attempt: " + last.getMessage() + ")", last);
    }

    static String format(Duration d) {
        long ms = d.toMillis();
        return ms % 1000 == 0 ? (ms / 1000) + "s" : (ms / 1000.0) + "s";
    }

    /**
     * Waits for the next connection to the public address. Only for tunnels opened with {@link Tuna#listen}.
     *
     * @return the connection
     * @throws ClosedChannelException after {@link #close()}
     * @throws IOException the permanent error that ended the tunnel
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public Connection accept() throws IOException, InterruptedException {
        if (!forwardsTo.isEmpty()) {
            throw new IllegalStateException("tuna: accept is only for tunnels opened with Tuna.listen");
        }
        Object x = conns.take();
        if (x instanceof Connection c) {
            return c;
        }
        conns.add(x);
        IOException e = err;
        if (e != null) {
            throw e;
        }
        throw new ClosedChannelException();
    }

    /**
     * Blocks until the tunnel ends. Returns normally after {@link #close()}.
     *
     * @throws IOException the permanent error that ended the tunnel
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void await() throws IOException, InterruptedException {
        try {
            done.get();
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        }
        IOException e = err;
        if (e != null) {
            throw e;
        }
    }

    /**
     * Returns the current public address, e.g. {@code https://name.tuna.am} or {@code tcp://ru.tuna.am:40000}.
     *
     * @return the public address
     */
    public String url() {
        return url;
    }

    /**
     * Stops accepting connections and reconnecting. Does not wait.
     *
     * <p>For {@link Tuna#listen}, connections accepted earlier keep working: the tunnel stays connected to the node
     * until the last of them is closed. For {@link Tuna#forward}, forwarded connections are cut.
     */
    @Override
    public void close() {
        if (!closeSignal.complete(null)) {
            return;
        }
        Attempt a = current;
        if (a != null) {
            if (forwardsTo.isEmpty()) {
                a.beginClosing();
            } else {
                a.fail(new ClosedChannelException());
            }
        }
        drain();
    }

    private void abort() {
        closeSignal.complete(null);
        Attempt a = current;
        if (a != null) {
            a.fail(new ClosedChannelException());
        }
        drain();
    }

    private void drain() {
        Object x;
        while ((x = conns.poll()) != null) {
            if (x instanceof Connection c) {
                c.close();
            }
        }
        conns.add(CLOSED);
    }

    private SshClient newSshClient() {
        SshClient c = SshClient.setUpDefaultClient();
        c.setChannelFactories(List.of(StreamChannel.FACTORY));
        c.setServerKeyVerifier((session, addr, key) -> hostKeyAllowed(expectedKeys, key));
        c.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        c.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        c.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE));
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(c, KEEPALIVE_INTERVAL);
        CoreModuleProperties.HEARTBEAT_REQUEST.set(c, KEEPALIVE_REQUEST);
        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(c, KEEPALIVE_MAX_NO_REPLY);
        CoreModuleProperties.IDLE_TIMEOUT.set(c, Duration.ZERO);
        CoreModuleProperties.REQUEST_EXEC_REPLY.set(c, true);
        c.addSessionListener(new SessionListener() {
            @Override
            public void sessionException(Session session, Throwable t) {
                Attempt a = session.getAttribute(ATTEMPT);
                if (a != null) {
                    a.cause(t);
                }
            }

            @Override
            public void sessionClosed(Session session) {
                Attempt a = session.getAttribute(ATTEMPT);
                if (a != null) {
                    a.fail(a.lost());
                }
            }
        });
        return c;
    }

    static boolean hostKeyAllowed(List<PublicKey> allowed, PublicKey key) {
        for (PublicKey k : allowed) {
            if (KeyUtils.compareKeys(k, key)) {
                return true;
            }
        }
        return false;
    }

    static List<PublicKey> parseHostKeys(List<String> lines) throws IOException {
        List<PublicKey> keys = new ArrayList<>();
        for (String line : lines) {
            try {
                AuthorizedKeyEntry e = AuthorizedKeyEntry.parseAuthorizedKeyEntry(line);
                if (e == null) {
                    throw new IOException("empty key");
                }
                keys.add(e.resolvePublicKey(null, PublicKeyEntryResolver.FAILING));
            } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
                throw new IOException("tuna: parse host key: " + e.getMessage(), e);
            }
        }
        return keys;
    }

    static boolean isHeartbeatFailure(Throwable t) {
        return t instanceof SshException && t.getMessage() != null
                && t.getMessage().contains("heartbeat requests without reply");
    }

    private void emit(Event e) {
        EventListener l = o.listener();
        if (l == null) {
            return;
        }
        synchronized (emitLock) {
            try {
                l.onEvent(e);
            } catch (RuntimeException ignored) {
                return;
            }
        }
    }

    private void finish(IOException e) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        err = e;
        conns.add(CLOSED);
        Attempt a = current;
        if (a != null) {
            a.fail(new ClosedChannelException());
        }
        ssh.stop();
        if (pipes != null) {
            pipes.shutdown();
        }
        done.complete(null);
    }

    private void run() {
        Duration backoff = MIN_BACKOFF;
        while (true) {
            Outcome r;
            try {
                r = session();
            } catch (RuntimeException ex) {
                r = new Outcome(false, new IOException(ex.getMessage(), ex));
            }
            if (closeSignal.isDone()) {
                finish(null);
                return;
            }
            IOException e = r.error() != null ? r.error() : new IOException("tuna: connection to node lost");
            if (!everUp && e instanceof ServerException se) {
                se.markBeforeFirstConnect();
            }
            lastErr = e;
            if (Tuna.isPermanent(e)) {
                if (e instanceof TerminatedException te) {
                    emit(Event.of(EventType.TERMINATED).withReason(te.reason())
                            .withMessage(TerminatedException.describe(te.reason())));
                }
                finish(e);
                return;
            }
            if (r.connected()) {
                backoff = MIN_BACKOFF;
            }
            Duration wait = retryDelay(backoff, e);
            emit(Event.of(EventType.RECONNECTING).withError(e).withRetryIn(wait));
            try {
                closeSignal.get(wait.toMillis(), TimeUnit.MILLISECONDS);
                finish(null);
                return;
            } catch (TimeoutException ignored) {
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            } catch (InterruptedException | ExecutionException ex) {
                finish(null);
                return;
            }
        }
    }

    static Duration retryDelay(Duration backoff, Throwable err) {
        long ms = MIN_BACKOFF.toMillis() + ThreadLocalRandom.current().nextLong(backoff.toMillis());
        Duration wait = Duration.ofMillis(Math.min(MAX_BACKOFF.toMillis(), ms));
        if (err instanceof ServerException se && se.retryAfter().compareTo(wait) > 0) {
            wait = se.retryAfter();
        }
        return wait;
    }

    Map<String, Object> connectRequest() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client_version", CLIENT_VERSION);
        m.put("client_name", o.clientName());
        m.put("sdk_version", Tuna.VERSION);
        m.put("forwards_to", forwardsTo);
        m.put("protocol", o.protocol());
        m.put("location", o.location());
        m.put("subdomain", o.subdomain());
        m.put("domain", o.domain());
        m.put("token", o.apiKey());
        m.put("os", goos(System.getProperty("os.name", "")));
        m.put("arch", goarch(System.getProperty("os.arch", "")));
        m.put("port_or_alias", o.port());
        m.put("tunnel_session_id", sessionId);
        m.put("proxy_protocol", o.proxyProtocol());
        if (!o.cidrAllow().isEmpty()) {
            m.put("cidr_allow_list", o.cidrAllow());
        }
        if (!o.cidrDeny().isEmpty()) {
            m.put("cidr_deny_list", o.cidrDeny());
        }
        return m;
    }

    static String goos(String osName) {
        String n = osName.toLowerCase(Locale.ROOT);
        if (n.startsWith("mac") || n.startsWith("darwin")) {
            return "darwin";
        }
        if (n.startsWith("windows")) {
            return "windows";
        }
        return n.replace(" ", "");
    }

    static String goarch(String osArch) {
        switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64":
            case "x86_64":
                return "amd64";
            case "aarch64":
            case "arm64":
                return "arm64";
            case "x86":
            case "i386":
            case "i686":
                return "386";
            default:
                return osArch.toLowerCase(Locale.ROOT);
        }
    }

    static InetSocketAddress hostPort(String addr) throws IOException {
        int i = addr.lastIndexOf(':');
        if (i <= 0 || i == addr.length() - 1) {
            throw new IOException("tuna: invalid address \"" + addr + "\": want host:port");
        }
        String host = addr.substring(0, i);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port;
        try {
            port = Integer.parseInt(addr.substring(i + 1));
        } catch (NumberFormatException e) {
            throw new IOException("tuna: invalid port in \"" + addr + "\"", e);
        }
        if (port < 1 || port > 65535) {
            throw new IOException("tuna: invalid port in \"" + addr + "\"");
        }
        return InetSocketAddress.createUnresolved(host, port);
    }

    private Outcome session() {
        Api.ConnectResponse cr;
        List<PublicKey> keys;
        InetSocketAddress node;
        try {
            cr = Api.ConnectResponse.from(api.post(o.serverUrl(), "/v1/connect", o.clientName(), connectRequest(),
                    closeSignal));
            sessionId = cr.sessionId();
            if (!cr.banner().isEmpty() && !bannerShown) {
                bannerShown = true;
                emit(Event.of(EventType.BANNER).withMessage(cr.banner()));
            }
            keys = parseHostKeys(cr.hostKeys());
            node = hostPort(cr.address());
        } catch (IOException e) {
            return new Outcome(false, e);
        }
        expectedKeys = keys;

        ClientSession s;
        try {
            s = ssh.connect(SSH_USER, node.getHostString(), node.getPort()).verify(DIAL_TIMEOUT).getSession();
        } catch (IOException e) {
            return new Outcome(false, e);
        }
        Attempt a = new Attempt(s);
        s.setAttribute(ATTEMPT, a);
        current = a;
        if (closeSignal.isDone()) {
            a.fail(new ClosedChannelException());
        } else if (!s.isOpen()) {
            a.fail(a.lost());
        }

        try {
            s.addPasswordIdentity(cr.token());
            s.auth().verify(DIAL_TIMEOUT);
        } catch (IOException | RuntimeException e) {
            return a.setupFailed(e, "");
        }
        try {
            Buffer buf = s.createBuffer(SshConstants.SSH_MSG_GLOBAL_REQUEST);
            buf.putString("tcpip-forward");
            buf.putBoolean(true);
            buf.putString("127.0.0.1");
            buf.putUInt(0);
            Buffer reply = s.request("tcpip-forward", buf, FORWARD_TIMEOUT);
            if (reply == null) {
                throw new IOException("request denied by node");
            }
            a.bind(reply.getUInt());
        } catch (IOException | RuntimeException e) {
            return a.setupFailed(e, "tuna: remote forward: ");
        }

        openEvents(s, a);

        url = cr.publicUrl(o.protocol());
        if (everUp) {
            emit(Event.of(EventType.RECONNECTED).withUrl(url));
        } else {
            everUp = true;
            emit(Event.of(EventType.CONNECTED).withUrl(url));
            ready.complete(null);
        }
        a.dead.join();
        return new Outcome(true, a.failErr);
    }

    private void openEvents(ClientSession s, Attempt a) {
        try {
            ChannelExec ch = s.createExecChannel(EVENTS_COMMAND);
            ch.setOut(new LineSink(line -> nodeEvent(line, a)));
            ch.setErr(OutputStream.nullOutputStream());
            ch.open();
        } catch (IOException | RuntimeException ignored) {
            return;
        }
    }

    void nodeEvent(String line, Attempt a) {
        Map<String, Object> m;
        try {
            m = Json.parseObject(line);
        } catch (IllegalArgumentException e) {
            return;
        }
        switch (Json.str(m, "type")) {
            case "terminate" -> a.fail(new TerminatedException(Json.str(m, "reason")));
            case "session_expiring" -> emit(Event.of(EventType.SESSION_EXPIRING)
                    .withExpiresIn(Duration.ofSeconds(Json.num(m, "expires_in"))));
            default -> {
            }
        }
    }

    private void deliver(Connection c) {
        if (forwardsTo.isEmpty()) {
            conns.add(c);
            if (closeSignal.isDone()) {
                drain();
            }
            return;
        }
        try {
            pipes.execute(() -> pipe(c));
        } catch (RejectedExecutionException e) {
            c.close();
        }
    }

    private void pipe(Connection remote) {
        try (remote) {
            Closeable local;
            InputStream lin;
            OutputStream lout;
            try {
                if (forwardsTo.startsWith("unix://")) {
                    SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                    try {
                        ch.connect(UnixDomainSocketAddress.of(forwardsTo.substring("unix://".length())));
                    } catch (IOException e) {
                        ch.close();
                        throw e;
                    }
                    local = ch;
                    lin = Channels.newInputStream(ch);
                    lout = Channels.newOutputStream(ch);
                } else {
                    InetSocketAddress hp = hostPort(forwardsTo);
                    Socket sock = new Socket();
                    try {
                        sock.connect(new InetSocketAddress(hp.getHostString(), hp.getPort()),
                                (int) DIAL_TIMEOUT.toMillis());
                    } catch (IOException e) {
                        sock.close();
                        throw e;
                    }
                    local = sock;
                    lin = sock.getInputStream();
                    lout = sock.getOutputStream();
                }
            } catch (IOException e) {
                emit(Event.of(EventType.LOCAL_ERROR).withError(e));
                return;
            }
            try (local) {
                CompletableFuture<Void> up = CompletableFuture.runAsync(() -> copy(remote.getInputStream(), lout), pipes);
                CompletableFuture<Void> down = CompletableFuture.runAsync(() -> copy(lin, remote.getOutputStream()),
                        pipes);
                CompletableFuture.anyOf(up, down).join();
            } catch (IOException | RuntimeException ignored) {
                return;
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) {
        byte[] buf = new byte[32 << 10];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            return;
        }
    }

    final class Attempt {
        private final ClientSession session;
        final CompletableFuture<Void> dead = new CompletableFuture<>();
        private volatile IOException failErr;
        private volatile Throwable cause;
        private volatile long port = -1;
        private int active;
        private boolean closing;

        Attempt(ClientSession session) {
            this.session = session;
        }

        long port() {
            return port;
        }

        void bind(long p) {
            port = p;
        }

        void cause(Throwable t) {
            if (cause == null) {
                cause = t;
            }
        }

        IOException lost() {
            Throwable c = cause;
            if (isHeartbeatFailure(c)) {
                return new KeepAliveTimeoutException();
            }
            if (c == null) {
                return new IOException("tuna: connection to node lost");
            }
            return new IOException("tuna: connection to node lost: " + c.getMessage(), c);
        }

        Outcome setupFailed(Exception e, String prefix) {
            IOException own = e instanceof IOException io && prefix.isEmpty() ? io
                    : new IOException(prefix + e.getMessage(), e);
            fail(own);
            IOException got = failErr;
            return new Outcome(false, got instanceof KeepAliveTimeoutException ? got : own);
        }

        void fail(IOException e) {
            synchronized (this) {
                if (failErr != null) {
                    return;
                }
                failErr = e;
            }
            session.close(true);
            dead.complete(null);
        }

        synchronized boolean admit() {
            if (closing || failErr != null) {
                return false;
            }
            active++;
            return true;
        }

        void release() {
            boolean idle;
            synchronized (this) {
                active--;
                idle = closing && active == 0;
            }
            if (idle) {
                fail(new ClosedChannelException());
            }
        }

        void beginClosing() {
            boolean idle;
            synchronized (this) {
                closing = true;
                idle = active == 0;
            }
            if (idle) {
                fail(new ClosedChannelException());
            }
        }

        void deliver(Connection c) {
            Tunnel.this.deliver(c);
        }
    }

    static final class LineSink extends OutputStream {
        private final Consumer<String> handler;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private boolean overflow;

        LineSink(Consumer<String> handler) {
            this.handler = handler;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                if (!overflow) {
                    handler.accept(line.toString(StandardCharsets.UTF_8));
                }
                line.reset();
                overflow = false;
                return;
            }
            if (line.size() >= MAX_EVENT_LINE) {
                overflow = true;
                return;
            }
            line.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
                write(b[i]);
            }
        }
    }
}
