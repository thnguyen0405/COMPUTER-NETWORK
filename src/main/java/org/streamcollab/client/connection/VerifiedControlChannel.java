package org.streamcollab.client.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Authenticated TLS channel. The factory must verify the server identity. */
public interface VerifiedControlChannel extends AutoCloseable {
    InputStream input() throws IOException;
    OutputStream output() throws IOException;
    @Override void close() throws IOException;
}
