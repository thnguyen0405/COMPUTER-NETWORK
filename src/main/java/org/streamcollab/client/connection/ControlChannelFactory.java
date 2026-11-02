package org.streamcollab.client.connection;

/** Opening a channel MUST complete certificate-chain AND server-name checks. */
@FunctionalInterface
public interface ControlChannelFactory {
    VerifiedControlChannel connect(ServerEndpoint endpoint) throws Exception;
}
