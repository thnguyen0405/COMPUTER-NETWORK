package org.streamcollab.client.connection;

/** Member 1/2/3 will implement the agreed authenticated bind exchange. */
@FunctionalInterface
public interface UdpBinder {
    BoundUdpSession bind(VerifiedControlChannel channel, AuthenticatedSession session)
            throws Exception;
}
