package am.tuna.sdk;

import java.io.IOException;

/**
 * Opens tuna.am tunnels from Java code.
 *
 * <p>{@link #listen(Options)} returns a tunnel whose {@link Tunnel#accept()} yields connections arriving at the
 * public address. {@link #forward(Options, String)} proxies them to a local address.
 */
public final class Tuna {
    /** The SDK version. */
    public static final String VERSION = "0.1.0";
    /** The tuna.am API used when {@link Options#serverUrl(String)} is not set. */
    public static final String DEFAULT_SERVER_URL = "https://api.tuna.am";

    private Tuna() {
    }

    /**
     * Connects a tunnel whose connections are handed to the caller through {@link Tunnel#accept()}. Returns once
     * the tunnel is up.
     *
     * @param options tunnel settings
     * @return the running tunnel
     * @throws IOException a permanent error, or {@link Options#connectTimeout(java.time.Duration)} ran out
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalArgumentException if the options are invalid
     */
    public static Tunnel listen(Options options) throws IOException, InterruptedException {
        return Tunnel.start(options, "");
    }

    /**
     * Connects a tunnel that proxies its connections to {@code localAddr}, {@code host:port} or
     * {@code unix:///path.sock}. Returns once the tunnel is up; use {@link Tunnel#await()} to block until it ends.
     *
     * @param options tunnel settings
     * @param localAddr the local address
     * @return the running tunnel
     * @throws IOException a permanent error, or {@link Options#connectTimeout(java.time.Duration)} ran out
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IllegalArgumentException if the options or the address are invalid
     */
    public static Tunnel forward(Options options, String localAddr) throws IOException, InterruptedException {
        checkLocalAddr(localAddr);
        return Tunnel.start(options, localAddr);
    }

    /**
     * Reports whether retrying after {@code err} is pointless: the API rejected the request
     * ({@link ServerException}) or the server closed the tunnel ({@link TerminatedException}).
     *
     * @param err the error, may be {@code null}
     * @return {@code true} for permanent errors
     */
    public static boolean isPermanent(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof TerminatedException) {
                return true;
            }
            if (t instanceof ServerException se) {
                return se.permanent();
            }
        }
        return false;
    }

    static void checkLocalAddr(String addr) {
        if (addr == null || addr.isEmpty()) {
            throw new IllegalArgumentException("tuna: local address is required");
        }
        if (addr.startsWith("unix://")) {
            if (addr.length() == "unix://".length()) {
                throw new IllegalArgumentException("tuna: invalid local address \"" + addr + "\"");
            }
            return;
        }
        try {
            Tunnel.hostPort(addr);
        } catch (IOException e) {
            throw new IllegalArgumentException("tuna: invalid local address \"" + addr + "\": want host:port or "
                    + "unix:///path.sock", e);
        }
    }
}
