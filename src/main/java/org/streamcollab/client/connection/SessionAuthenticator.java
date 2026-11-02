package org.streamcollab.client.connection;

/**
 * Member 2's login request/response framing will be implemented here later.
 * Implementations must validate server acceptance and return a FRESH session.
 */
@FunctionalInterface
public interface SessionAuthenticator {
    AuthenticatedSession authenticate(VerifiedControlChannel channel, LoginCredentials credentials)
            throws Exception;
}
