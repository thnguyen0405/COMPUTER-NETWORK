package org.streamcollab.client.connection;

/** An authenticated UDP endpoint binding, NOT merely an opened UDP socket. */
public interface BoundUdpSession extends AutoCloseable {
    @Override void close() throws Exception;
}
