package org.streamcollab.client.connection;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;

/**
 * Real TLS connector using JSSE. Trust anchors come from the supplied SSLContext
 * (or default JVM truststore). NEVER disable certificate/hostname validation.
 * Application JSON framing and auth happen later through Authenticator.
 */
public final class TlsControlChannelFactory implements ControlChannelFactory {
    private final SSLContext sslContext;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public TlsControlChannelFactory(SSLContext sslContext, Duration connectTimeout,
                                    Duration readTimeout) {
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
        this.connectTimeoutMs = positiveIntMillis(connectTimeout, "connectTimeout");
        this.readTimeoutMs = positiveIntMillis(readTimeout, "readTimeout");
    }

    private static int positiveIntMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        long millis = value.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be between 1ms and Integer.MAX_VALUE ms");
        }
        return (int) millis;
    }

    @Override
    public VerifiedControlChannel connect(ServerEndpoint endpoint) throws IOException {
        Objects.requireNonNull(endpoint, "endpoint");
        SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();
        boolean success = false;
        try {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.tcpPort()), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();
            success = true;
            return new SocketChannel(socket);
        } finally {
            if (!success) {
                socket.close();
            }
        }
    }

    private record SocketChannel(SSLSocket socket) implements VerifiedControlChannel {
        @Override public InputStream input() throws IOException { return socket.getInputStream(); }
        @Override public OutputStream output() throws IOException { return socket.getOutputStream(); }
        @Override public void close() throws IOException { socket.close(); }
    }
}
