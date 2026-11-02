package org.streamcollab.client.connection;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stage 2 orchestration. TLS is real when using TlsControlChannelFactory;
 * login wire framing and authenticated UDP binding are deliberately injected
 * until Members 1/2/3 freeze those protocols.
 *
 * Network work uses virtual threads; lock guards ONLY short state transitions.
 * Logout increments an epoch, closes resources, and invalidates late results.
 * No plaintext password, secret, or full exception is published to UI events.
 */
public final class ClientConnectionSupervisor implements AutoCloseable {
    private final Object lock = new Object();
    private final ServerEndpoint endpoint;
    private final ControlChannelFactory channelFactory;
    private final SessionAuthenticator authenticator;
    private final UdpBinder udpBinder;
    private final ConnectionEventSink eventSink;
    private final ConnectionStateMachine stateMachine;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService eventWorker =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("client-events-", 0).factory());
    private final int maxManualRetries;

    private long epoch;
    private int manualRetries;
    private boolean closed;
    private VerifiedControlChannel openingChannel;
    private LoginCredentials pendingCredentials;
    private CompletableFuture<ConnectionState> pendingResult;
    private ActiveSession active;

    public ClientConnectionSupervisor(ServerEndpoint endpoint,
                                      ControlChannelFactory channelFactory,
                                      SessionAuthenticator authenticator,
                                      UdpBinder udpBinder,
                                      ConnectionEventSink eventSink,
                                      int maxManualRetries) {
        this(endpoint, channelFactory, authenticator, udpBinder,
                eventSink, maxManualRetries, new ConnectionStateMachine());
    }

    public ClientConnectionSupervisor(ServerEndpoint endpoint,
                                      ControlChannelFactory channelFactory,
                                      SessionAuthenticator authenticator,
                                      UdpBinder udpBinder,
                                      ConnectionEventSink eventSink,
                                      int maxManualRetries,
                                      ConnectionStateMachine stateMachine) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.channelFactory = Objects.requireNonNull(channelFactory, "channelFactory");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.udpBinder = Objects.requireNonNull(udpBinder, "udpBinder");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        if (maxManualRetries < 0 || maxManualRetries > 100) {
            throw new IllegalArgumentException("maxManualRetries must be between 0 and 100");
        }
        this.maxManualRetries = maxManualRetries;
    }

    public ConnectionState state() {
        return stateMachine.state();
    }

    /** Consumes credentials; returns a future completed only after UDP binding succeeds. */
    public CompletionStage<ConnectionState> login(LoginCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials");
        synchronized (lock) {
            if (closed || stateMachine.state() != ConnectionState.DISCONNECTED) {
                credentials.close();
                throw new IllegalStateException("Login requires an open, disconnected supervisor");
            }
            manualRetries = 0;
            publish(stateMachine.startLogin());
            return beginAttempt(credentials);
        }
    }

    /** Explicit user retry: no password is stored between attempts. */
    public CompletionStage<ConnectionState> retry(LoginCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials");
        synchronized (lock) {
            ConnectionState state = stateMachine.state();
            if (closed || (state != ConnectionState.RECONNECTING && state != ConnectionState.FAILED)) {
                credentials.close();
                throw new IllegalStateException("Retry requires RECONNECTING or FAILED state");
            }
            if (manualRetries >= maxManualRetries) {
                credentials.close();
                if (state == ConnectionState.RECONNECTING) {
                    publish(stateMachine.fail("RETRY_EXHAUSTED"));
                }
                return CompletableFuture.failedFuture(new ConnectionFailure("RETRY_EXHAUSTED"));
            }
            manualRetries++;
            publish(stateMachine.retry());
            return beginAttempt(credentials);
        }
    }

    /** Called by the future transport/heartbeat monitor when a LIVE session is lost. */
    public void onSessionLost() {
        ActiveSession resources;
        synchronized (lock) {
            if (closed || stateMachine.state() != ConnectionState.ONLINE) {
                return;
            }
            resources = active;
            active = null;
            ++epoch;
            publish(stateMachine.sessionLost());
        }
        closeQuietly(resources);
        // Stop pending app messages/transfers in the future shared dispatcher.
        // A fresh login via retry(credentials) is required for a new key context.
    }

    /** Synchronous state change and best-effort socket closure; safe to call twice. */
    public void logout() {
        ActiveSession resources;
        VerifiedControlChannel unfinished;
        LoginCredentials credentials;
        CompletableFuture<ConnectionState> result;
        synchronized (lock) {
            ++epoch; // invalidate all in-flight results even if a socket races with logout
            resources = active;
            active = null;
            unfinished = openingChannel;
            openingChannel = null;
            credentials = pendingCredentials;
            pendingCredentials = null;
            result = pendingResult;
            pendingResult = null;
            stateMachine.logout().ifPresent(this::publish);
        }
        closeQuietly(resources);
        closeQuietly(unfinished);
        closeQuietly(credentials);
        if (result != null) {
            result.completeExceptionally(new CancellationException("Login cancelled"));
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        logout();
        workers.shutdownNow();
        // Keep already queued state events (including DISCONNECTED) in order.
        eventWorker.shutdown();
    }

    /** Called under lock; every event is queued in the same transition order. */
    private void publish(ConnectionTransition transition) {
        eventWorker.execute(() -> {
            try {
                eventSink.onTransition(transition);
            } catch (RuntimeException ignored) {
                // An event consumer must not stop connection management.
                // Integrators should add a safe diagnostic counter here.
            }
        });
    }

    /** Called under lock after startLogin()/retry(). */
    private CompletionStage<ConnectionState> beginAttempt(LoginCredentials credentials) {
        final long attemptEpoch = ++epoch;
        final CompletableFuture<ConnectionState> result = new CompletableFuture<>();
        pendingResult = result;
        pendingCredentials = credentials;
        workers.execute(() -> performAttempt(attemptEpoch, credentials, result));
        return result;
    }

    private boolean isCurrent(long attemptEpoch) {
        return !closed && epoch == attemptEpoch;
    }

    private void performAttempt(long attemptEpoch, LoginCredentials credentials,
                                CompletableFuture<ConnectionState> result) {
        VerifiedControlChannel channel = null;
        AuthenticatedSession authenticated = null;
        BoundUdpSession bound = null;
        boolean transferred = false;
        String completionError = null;
        Phase phase = Phase.TLS;
        try {
            synchronized (lock) {
                if (!isCurrent(attemptEpoch)) return;
            }
            // This call returns ONLY after verified TLS handshake.
            channel = Objects.requireNonNull(channelFactory.connect(endpoint), "TLS channel");
            synchronized (lock) {
                if (!isCurrent(attemptEpoch)) return;
                openingChannel = channel;
                publish(stateMachine.tlsVerified());
            }

            phase = Phase.AUTH;
            authenticated = Objects.requireNonNull(
                    authenticator.authenticate(channel, credentials), "Authenticated session");
            if (authenticated.sessionId() == null || authenticated.sessionId().isBlank()) {
                throw new ConnectionFailure("AUTH_FAILED");
            }
            synchronized (lock) {
                if (!isCurrent(attemptEpoch)) return;
            }

            phase = Phase.UDP;
            bound = Objects.requireNonNull(udpBinder.bind(channel, authenticated), "UDP binding");
            synchronized (lock) {
                if (!isCurrent(attemptEpoch)) return;
                active = new ActiveSession(bound, authenticated, channel);
                openingChannel = null;
                pendingCredentials = null;
                pendingResult = null;
                publish(stateMachine.sessionReady());
                transferred = true;
            }
            result.complete(ConnectionState.ONLINE);
        } catch (Exception failure) {
            String code = failureCode(failure, phase);
            synchronized (lock) {
                if (isCurrent(attemptEpoch)) {
                    openingChannel = null;
                    pendingCredentials = null;
                    pendingResult = null;
                    publish(stateMachine.fail(code));
                    completionError = code;
                }
            }
        } finally {
            credentials.close();
            if (!transferred) {
                closeQuietly(bound);
                closeQuietly(authenticated);
                closeQuietly(channel);
            }
            if (completionError != null) {
                result.completeExceptionally(new ConnectionFailure(completionError));
            }
        }
    }

    private static String failureCode(Exception failure, Phase phase) {
        if (failure instanceof ConnectionFailure typed) {
            return typed.code();
        }
        if (failure instanceof SocketTimeoutException) {
            return "TIMEOUT";
        }
        if (phase == Phase.TLS && failure instanceof SSLException) {
            return "TLS_ERROR";
        }
        return switch (phase) {
            case TLS -> "IO_ERROR";
            case AUTH -> "AUTH_FAILED";
            case UDP -> "UDP_BIND_FAILED";
        };
    }

    private enum Phase { TLS, AUTH, UDP }

    private record ActiveSession(BoundUdpSession binding,
                                 AuthenticatedSession authenticated,
                                 VerifiedControlChannel channel) implements AutoCloseable {
        @Override
        public void close() {
            closeQuietly(binding);
            closeQuietly(authenticated);
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // Cleanup is best-effort; suppress diagnostic details containing secrets.
        }
    }
}
