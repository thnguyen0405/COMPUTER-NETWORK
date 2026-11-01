package org.streamcollab.client.connection;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe, dependency-free connection lifecycle for the Java Client Agent.
 *
 * This class ONLY validates state transitions. It does not open sockets,
 * authenticate users, negotiate UDP keys, schedule heartbeats, or retry by
 * itself. The future Client Agent orchestrator must invoke its methods only
 * AFTER the corresponding network operation has actually succeeded/failed.
 *
 * Each method returns a transition for the agent's SINGLE event dispatcher.
 * The dispatcher should publish events in the same order as transitions; it
 * must not block a transport peer-state owner to perform browser I/O.
 */
public final class ConnectionStateMachine {
    private final Clock clock;
    private ConnectionState state = ConnectionState.DISCONNECTED;

    public ConnectionStateMachine() {
        this(Clock.systemUTC());
    }

    public ConnectionStateMachine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized ConnectionState state() {
        return state;
    }

    /** User starts a new login after being disconnected. */
    public synchronized ConnectionTransition startLogin() {
        return move(ConnectionState.CONNECTING, "LOGIN_REQUESTED", ConnectionState.DISCONNECTED);
    }

    /** Called only after TLS trust AND server identity verification succeed. */
    public synchronized ConnectionTransition tlsVerified() {
        return move(ConnectionState.AUTHENTICATING, "TLS_VERIFIED", ConnectionState.CONNECTING);
    }

    /** Called only when BOTH authentication and authenticated UDP binding succeed. */
    public synchronized ConnectionTransition sessionReady() {
        return move(ConnectionState.ONLINE, "SESSION_READY", ConnectionState.AUTHENTICATING);
    }

    /** Online session is no longer usable; session resources must be cleaned up. */
    public synchronized ConnectionTransition sessionLost() {
        return move(ConnectionState.RECONNECTING, "SESSION_LOST", ConnectionState.ONLINE);
    }

    /** Explicit/bounded retry after a loss or a previous failure. */
    public synchronized ConnectionTransition retry() {
        return move(ConnectionState.CONNECTING, "RETRY_REQUESTED",
                ConnectionState.RECONNECTING, ConnectionState.FAILED);
    }

    /** TLS, authentication, UDP binding or bounded reconnect attempt failed. */
    public synchronized ConnectionTransition fail(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        // Use a stable error CODE; never pass usernames, passwords, tokens or keys.
        if (!errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("errorCode must be an uppercase code without secrets");
        }
        return move(ConnectionState.FAILED, errorCode,
                ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING, ConnectionState.RECONNECTING);
    }

    /** Idempotent user logout/stop. Caller closes sockets and cancels pending work. */
    public synchronized Optional<ConnectionTransition> logout() {
        if (state == ConnectionState.DISCONNECTED) {
            return Optional.empty();
        }
        // Logout is allowed from any active/failure state, as defined in docs.
        ConnectionState previous = state;
        state = ConnectionState.DISCONNECTED;
        return Optional.of(new ConnectionTransition(previous, state, clock.instant(), "LOGOUT"));
    }

    private ConnectionTransition move(ConnectionState target, String reason, ConnectionState... allowed) {
        boolean permitted = false;
        for (ConnectionState source : allowed) {
            if (state == source) {
                permitted = true;
                break;
            }
        }
        if (!permitted) {
            throw new IllegalStateException("Invalid connection transition: " + state + " -> " + target);
        }
        ConnectionState previous = state;
        state = target;
        return new ConnectionTransition(previous, target, clock.instant(), reason);
    }
}
