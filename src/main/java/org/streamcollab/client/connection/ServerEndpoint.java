package org.streamcollab.client.connection;

/** DNS hostname (or IP address) whose identity must match the server certificate. */
public record ServerEndpoint(String host, int tcpPort) {
    public ServerEndpoint {
        if (host == null || host.isBlank() || host.length() > 253 || !host.equals(host.trim())) {
            throw new IllegalArgumentException("Invalid server host");
        }
        if (tcpPort < 1 || tcpPort > 65535) {
            throw new IllegalArgumentException("Invalid TCP port");
        }
    }
}
