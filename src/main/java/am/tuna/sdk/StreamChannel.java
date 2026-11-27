package am.tuna.sdk;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.channel.ChannelOutputStream;
import org.apache.sshd.common.channel.ChannelPipedInputStream;
import org.apache.sshd.common.channel.exception.SshChannelOpenException;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.channel.AbstractServerChannel;

final class StreamChannel extends AbstractServerChannel {
    static final String TYPE = "forwarded-tcpip";
    static final Duration WRITE_WAIT = Duration.ofDays(365);

    static final ChannelFactory FACTORY = new ChannelFactory() {
        @Override
        public String getName() {
            return TYPE;
        }

        @Override
        public Channel createChannel(Session session) {
            return new StreamChannel();
        }
    };

    private volatile ChannelPipedInputStream in;
    private final AtomicReference<Tunnel.Attempt> attempt = new AtomicReference<>();

    private StreamChannel() {
        super("", Collections.emptyList(), null);
    }

    @Override
    protected OpenFuture doInit(Buffer buffer) {
        buffer.getString();
        long port = buffer.getUInt();
        String originHost = buffer.getString();
        int originPort = (int) buffer.getUInt();
        DefaultOpenFuture f = new DefaultOpenFuture(this, this);
        Tunnel.Attempt a = getSession().getAttribute(Tunnel.ATTEMPT);
        if (a == null || port != a.port() || !a.admit()) {
            try {
                f.setException(new SshChannelOpenException(getChannelId(),
                        SshConstants.SSH_OPEN_ADMINISTRATIVELY_PROHIBITED, "Connection denied"));
            } finally {
                super.close(true);
            }
            return f;
        }
        attempt.set(a);
        in =new ChannelPipedInputStream(this, getLocalWindow());
        ChannelOutputStream out = new ChannelOutputStream(this, getRemoteWindow(), WRITE_WAIT, log,
                SshConstants.SSH_MSG_CHANNEL_DATA, true);
        Connection c = new Connection(this, in, out, InetSocketAddress.createUnresolved(originHost, originPort));
        signalChannelOpenSuccess();
        f.setOpened();
        a.deliver(c);
        return f;
    }

    @Override
    protected void doWriteData(byte[] data, int off, long len) throws IOException {
        ChannelPipedInputStream pipe = in;
        try {
            if (pipe == null) {
                throw new IOException("no pipe");
            }
            pipe.receive(data, off, (int) len);
        } catch (IOException e) {
            getLocalWindow().release(len);
        }
    }

    @Override
    protected void doWriteExtendedData(byte[] data, int off, long len) throws IOException {
        getLocalWindow().release(len);
    }

    @Override
    public void handleEof() throws IOException {
        super.handleEof();
        ChannelPipedInputStream pipe = in;
        if (pipe != null) {
            pipe.eof();
        }
    }

    @Override
    protected void preClose() {
        ChannelPipedInputStream pipe = in;
        if (pipe != null) {
            pipe.eof();
        }
        Tunnel.Attempt a = attempt.getAndSet(null);
        if (a != null) {
            a.release();
        }
        super.preClose();
    }
}
