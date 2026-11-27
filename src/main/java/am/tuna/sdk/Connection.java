package am.tuna.sdk;

import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * A connection to the tunnel's public address, returned by {@link Tunnel#accept()}. Writes are sent at once.
 * Closing the output stream signals end of data to the visitor; {@link #close()} closes the connection.
 */
public final class Connection implements Closeable {
    private final StreamChannel channel;
    private final InputStream in;
    private final OutputStream out;
    private final InetSocketAddress remote;

    Connection(StreamChannel channel, InputStream in, OutputStream out, InetSocketAddress remote) {
        this.channel = channel;
        this.in = in;
        this.out = new FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                this.out.write(b, off, len);
                this.out.flush();
            }

            @Override
            public void write(int b) throws IOException {
                this.out.write(b);
                this.out.flush();
            }
        };
        this.remote = remote;
    }

    /**
     * Returns the stream of bytes sent by the visitor.
     *
     * @return the input stream
     */
    public InputStream getInputStream() {
        return in;
    }

    /**
     * Returns the stream of bytes sent to the visitor.
     *
     * @return the output stream
     */
    public OutputStream getOutputStream() {
        return out;
    }

    /**
     * Returns the visitor's address as reported by the tuna.am node.
     *
     * @return the address
     */
    public InetSocketAddress getRemoteAddress() {
        return remote;
    }

    /** Closes the connection in both directions. */
    @Override
    public void close() {
        channel.close(false);
    }
}
