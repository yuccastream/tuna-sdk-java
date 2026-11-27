package am.tuna.sdk;

import java.io.IOException;

/**
 * The node stopped answering keepalive requests. The tunnel reconnects after it.
 *
 * @serial exclude
 */
public final class KeepAliveTimeoutException extends IOException {
    private static final long serialVersionUID = 1L;

    KeepAliveTimeoutException() {
        super("tuna: keepalive timeout");
    }
}
