package am.tuna.sdk;

import java.time.Duration;

/**
 * A change in the tunnel state. Fields that do not apply to the event type are empty strings, {@code null} or
 * {@link Duration#ZERO}.
 *
 * @param type the kind of the event
 * @param url the public address, set for {@code CONNECTED} and {@code RECONNECTED}
 * @param message human-readable text, set for {@code BANNER} and {@code TERMINATED}
 * @param reason the machine-readable cause, set for {@code TERMINATED}
 * @param error the cause, set for {@code RECONNECTING} and {@code LOCAL_ERROR}
 * @param retryIn the delay before the next attempt, set for {@code RECONNECTING}
 * @param expiresIn the time left, set for {@code SESSION_EXPIRING}
 */
public record Event(
        EventType type, String url, String message, String reason, Throwable error, Duration retryIn,
        Duration expiresIn) {

    static Event of(EventType type) {
        return new Event(type, "", "", "", null, Duration.ZERO, Duration.ZERO);
    }

    Event withUrl(String v) {
        return new Event(type, v, message, reason, error, retryIn, expiresIn);
    }

    Event withMessage(String v) {
        return new Event(type, url, v, reason, error, retryIn, expiresIn);
    }

    Event withReason(String v) {
        return new Event(type, url, message, v, error, retryIn, expiresIn);
    }

    Event withError(Throwable v) {
        return new Event(type, url, message, reason, v, retryIn, expiresIn);
    }

    Event withRetryIn(Duration v) {
        return new Event(type, url, message, reason, error, v, expiresIn);
    }

    Event withExpiresIn(Duration v) {
        return new Event(type, url, message, reason, error, retryIn, v);
    }
}
