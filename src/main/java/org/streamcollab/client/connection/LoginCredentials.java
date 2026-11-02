package org.streamcollab.client.connection;

import java.util.Arrays;

/**
 * Temporary in-memory credentials; never use in logs/events. The agent takes
 * ownership of each instance given to login/retry and closes it on completion.
 * The caller must clear the ORIGINAL char[] supplied to this constructor.
 */
public final class LoginCredentials implements AutoCloseable {
    private final String username;
    private final char[] password;
    private boolean closed;

    public LoginCredentials(String username, char[] password) {
        if (username == null || username.isBlank() || username.length() > 128) {
            throw new IllegalArgumentException("Invalid username");
        }
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password required");
        }
        this.username = username;
        this.password = password.clone();
    }

    public String username() {
        return username;
    }

    /** Callers must erase this returned copy after using it. */
    public synchronized char[] copyPassword() {
        if (closed) {
            throw new IllegalStateException("Credentials are already cleared");
        }
        return password.clone();
    }

    @Override
    public synchronized void close() {
        Arrays.fill(password, '\0');
        closed = true;
    }
}
