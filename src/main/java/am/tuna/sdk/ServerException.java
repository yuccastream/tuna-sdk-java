package am.tuna.sdk;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * An error response from the tuna.am API.
 *
 * @serial exclude
 */
public final class ServerException extends IOException {
    private static final long serialVersionUID = 1L;
    private static final Set<String> PERMANENT_CODES = Set.of(
            "PaymentRequired", "Unauthorized", "AuthorizationRequired", "Forbidden", "BadRequest", "NotFound",
            "UpgradeRequired");

    private final int status;
    private final String code;
    private final String text;
    private final String requestId;
    private final Duration retryAfter;
    private volatile boolean beforeFirstConnect;

    ServerException(int status, String code, String text, String requestId, Duration retryAfter) {
        super(requestId.isEmpty() ? "tuna: " + text : "tuna: " + text + " (request id " + requestId + ")");
        this.status = status;
        this.code = code;
        this.text = text;
        this.requestId = requestId;
        this.retryAfter = retryAfter;
    }

    /** {@return the HTTP status code} */
    public int status() {
        return status;
    }

    /** {@return the machine-readable error code, empty if the response had none} */
    public String code() {
        return code;
    }

    /** {@return the human-readable error text} */
    public String text() {
        return text;
    }

    /** {@return the request ID for tuna.am support, empty if the response had none} */
    public String requestId() {
        return requestId;
    }

    /** {@return the delay requested by the server, {@link Duration#ZERO} if none} */
    public Duration retryAfter() {
        return retryAfter;
    }

    void markBeforeFirstConnect() {
        beforeFirstConnect = true;
    }

    boolean permanent() {
        if (status >= 300 && status < 400) {
            return true;
        }
        if (beforeFirstConnect && status >= 400 && status < 500 && status != 408 && status != 429) {
            return true;
        }
        return PERMANENT_CODES.contains(code);
    }
}
