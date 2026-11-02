package org.streamcollab.client.connection;

/** Holds a verified server-issued session and any sensitive session state. */
public interface AuthenticatedSession extends AutoCloseable {
    String sessionId();
    @Override void close() throws Exception;
}
