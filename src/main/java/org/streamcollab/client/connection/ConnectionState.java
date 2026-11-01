package org.streamcollab.client.connection;

/** States agreed in docs/state-machines.md and docs/local-http-api.md. */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    ONLINE,
    RECONNECTING,
    FAILED
}
