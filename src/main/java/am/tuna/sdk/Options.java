package am.tuna.sdk;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;

/**
 * Tunnel settings. Only {@link #protocol(String)} is required. Setters return {@code this}; the tunnel copies the
 * values when it starts.
 */
public final class Options {
    private String apiKey = "";
    private String protocol = "";
    private String location = "";
    private String subdomain = "";
    private String domain = "";
    private String port = "";
    private int proxyProtocol;
    private List<String> cidrAllow = new ArrayList<>();
    private List<String> cidrDeny = new ArrayList<>();
    private String serverUrl = "";
    private SSLContext sslContext;
    private String clientName = "";
    private Duration connectTimeout = Duration.ZERO;
    private EventListener listener;

    /** Creates settings with every value unset. */
    public Options() {
    }

    /**
     * Sets a tuna.am API key with the {@code tunnels:connect} scope. Defaults to the {@code TUNA_API_KEY}
     * environment variable.
     *
     * @param v the API key
     * @return this
     */
    public Options apiKey(String v) {
        apiKey = nz(v);
        return this;
    }

    /**
     * Sets the protocol: {@code http} or {@code tcp}.
     *
     * @param v the protocol
     * @return this
     */
    public Options protocol(String v) {
        protocol = nz(v);
        return this;
    }

    /**
     * Sets the zone, needed only for names and ports reserved in the legacy {@code ru} or {@code nl} zones. Empty
     * lets the server pick the zone.
     *
     * @param v the zone
     * @return this
     */
    public Options location(String v) {
        location = nz(v);
        return this;
    }

    /**
     * Sets a reserved subdomain for HTTP tunnels.
     *
     * @param v the subdomain
     * @return this
     */
    public Options subdomain(String v) {
        subdomain = nz(v);
        return this;
    }

    /**
     * Sets a custom domain for HTTP tunnels.
     *
     * @param v the domain
     * @return this
     */
    public Options domain(String v) {
        domain = nz(v);
        return this;
    }

    /**
     * Sets a reserved TCP port or its alias.
     *
     * @param v the port or alias
     * @return this
     */
    public Options port(String v) {
        port = nz(v);
        return this;
    }

    /**
     * Enables the PROXY protocol header, version 1 or 2, for TCP tunnels.
     *
     * @param v the version, 0 to disable
     * @return this
     */
    public Options proxyProtocol(int v) {
        proxyProtocol = v;
        return this;
    }

    /**
     * Sets client CIDRs allowed to reach a TCP tunnel.
     *
     * @param v the CIDRs
     * @return this
     */
    public Options cidrAllow(List<String> v) {
        cidrAllow = new ArrayList<>(v);
        return this;
    }

    /**
     * Sets client CIDRs rejected by a TCP tunnel.
     *
     * @param v the CIDRs
     * @return this
     */
    public Options cidrDeny(List<String> v) {
        cidrDeny = new ArrayList<>(v);
        return this;
    }

    /**
     * Sets the tuna.am API address. Defaults to {@link Tuna#DEFAULT_SERVER_URL}.
     *
     * @param v the address, e.g. {@code https://api.tuna.am}
     * @return this
     */
    public Options serverUrl(String v) {
        serverUrl = nz(v);
        return this;
    }

    /**
     * Sets the TLS context for connections to the API. {@code null} means the JVM default.
     *
     * @param v the TLS context
     * @return this
     */
    public Options sslContext(SSLContext v) {
        sslContext = v;
        return this;
    }

    /**
     * Sets the client name reported to the server. Defaults to {@code tuna-sdk-java/<version>}.
     *
     * @param v the client name
     * @return this
     */
    public Options clientName(String v) {
        clientName = nz(v);
        return this;
    }

    /**
     * Limits how long {@link Tuna#listen} and {@link Tuna#forward} wait for the tunnel to come up for the first
     * time. Zero means no limit. Reconnects are not limited.
     *
     * @param v the limit
     * @return this
     */
    public Options connectTimeout(Duration v) {
        connectTimeout = v == null ? Duration.ZERO : v;
        return this;
    }

    /**
     * Sets the receiver of tunnel events.
     *
     * @param v the listener, {@code null} for none
     * @return this
     */
    public Options listener(EventListener v) {
        listener = v;
        return this;
    }

    String apiKey() {
        return apiKey;
    }

    String protocol() {
        return protocol;
    }

    String location() {
        return location;
    }

    String subdomain() {
        return subdomain;
    }

    String domain() {
        return domain;
    }

    String port() {
        return port;
    }

    int proxyProtocol() {
        return proxyProtocol;
    }

    List<String> cidrAllow() {
        return List.copyOf(cidrAllow);
    }

    List<String> cidrDeny() {
        return List.copyOf(cidrDeny);
    }

    String serverUrl() {
        return serverUrl;
    }

    SSLContext sslContext() {
        return sslContext;
    }

    String clientName() {
        return clientName;
    }

    Duration connectTimeout() {
        return connectTimeout;
    }

    EventListener listener() {
        return listener;
    }

    Options copy() {
        Options o = new Options();
        o.apiKey = apiKey;
        o.protocol = protocol;
        o.location = location;
        o.subdomain = subdomain;
        o.domain = domain;
        o.port = port;
        o.proxyProtocol = proxyProtocol;
        o.cidrAllow = new ArrayList<>(cidrAllow);
        o.cidrDeny = new ArrayList<>(cidrDeny);
        o.serverUrl = serverUrl;
        o.sslContext = sslContext;
        o.clientName = clientName;
        o.connectTimeout = connectTimeout;
        o.listener = listener;
        return o;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
