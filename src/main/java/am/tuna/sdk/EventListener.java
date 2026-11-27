package am.tuna.sdk;

/**
 * Receives tunnel events. Calls never overlap, but they come from the tunnel's own threads, so the listener must
 * return quickly. It may call {@link Tunnel#close()}.
 */
@FunctionalInterface
public interface EventListener {
    /**
     * Handles one event.
     *
     * @param event the event
     */
    void onEvent(Event event);
}
