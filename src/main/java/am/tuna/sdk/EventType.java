package am.tuna.sdk;

/** The kind of an {@link Event}. */
public enum EventType {
    /** The tunnel came up for the first time. */
    CONNECTED("connected"),
    /** The tunnel came up again after a failure. */
    RECONNECTED("reconnected"),
    /** An attempt failed or the connection was lost; the next attempt follows after {@link Event#retryIn()}. */
    RECONNECTING("reconnecting"),
    /** A server message for the user. */
    BANNER("banner"),
    /** The session time limit is near. */
    SESSION_EXPIRING("session_expiring"),
    /** The server closed the tunnel for good. */
    TERMINATED("terminated"),
    /** A forwarded connection could not reach the local address. */
    LOCAL_ERROR("local_error");

    private final String value;

    EventType(String value) {
        this.value = value;
    }

    /** {@return the name shared with tuna-tiny and the other tuna.am SDKs, e.g. {@code session_expiring}} */
    public String value() {
        return value;
    }
}
