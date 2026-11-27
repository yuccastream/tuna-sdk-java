package am.tuna.sdk;

import java.io.IOException;
import java.util.Map;

/**
 * The server closed the tunnel for good.
 *
 * @serial exclude
 */
public final class TerminatedException extends IOException {
    private static final long serialVersionUID = 1L;
    private static final Map<String, String> MESSAGES = Map.of(
            "deleted", "tunnel was deleted",
            "suspended", "tunnel was suspended",
            "session_limit", "session time limit reached",
            "session_ended", "client session ended",
            "reconnect", "tunnel was taken over by another connection");

    private final String reason;

    TerminatedException(String reason) {
        super("tuna: " + describe(reason));
        this.reason = reason;
    }

    /** {@return the machine-readable cause, e.g. {@code deleted} or {@code session_limit}} */
    public String reason() {
        return reason;
    }

    static String describe(String reason) {
        String msg = MESSAGES.get(reason);
        return msg != null ? msg : "tunnel terminated by server (" + reason + ")";
    }
}
