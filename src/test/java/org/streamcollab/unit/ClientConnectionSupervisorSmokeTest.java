package org.streamcollab.unit;

import org.streamcollab.client.connection.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free mock-boundary tests; NEVER claims to test actual UDP/authentication. */
public final class ClientConnectionSupervisorSmokeTest {
    private static int checks;
    private static final ServerEndpoint ENDPOINT = new ServerEndpoint("localhost", 9000);

    public static void main(String[] args) throws Exception {
        happyPathAndLossAndRetry();
        tlsFailure();
        authFailure();
        bindFailureCleansUp();
        logoutDuringAuthentication();
        retryBudget();
        endpointAndCredentialValidation();
        System.out.println("PASS: " + checks + " Member 3 stage 2 supervisor checks (mock auth/UDP)");
    }

    private static LoginCredentials creds() {
        char[] original = "test-only-password".toCharArray();
        LoginCredentials copy = new LoginCredentials("student", original);
        java.util.Arrays.fill(original, '\0');
        return copy;
    }

    private static void happyPathAndLossAndRetry() throws Exception {
        AtomicInteger channelCloses = new AtomicInteger();
        AtomicInteger sessionCloses = new AtomicInteger();
        AtomicInteger udpCloses = new AtomicInteger();
        AtomicInteger fresh = new AtomicInteger();
        List<ConnectionState> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch delivered = new CountDownLatch(8);
        ControlChannelFactory factory = unused -> new FakeChannel(channelCloses);
        SessionAuthenticator auth = (ch, credential) -> {
            eq(credential.username(), "student");
            char[] pw = credential.copyPassword();
            try { eq(new String(pw), "test-only-password"); }
            finally { java.util.Arrays.fill(pw, '\0'); }
            return new FakeAuth("fresh-" + fresh.incrementAndGet(), sessionCloses);
        };
        UdpBinder udp = (channel, session) -> new FakeUdp(udpCloses);
        try (var supervisor = new ClientConnectionSupervisor(ENDPOINT, factory, auth, udp,
                transition -> { events.add(transition.current()); delivered.countDown(); }, 2)) {
            eq(await(supervisor.login(creds())), ConnectionState.ONLINE);
            eq(supervisor.state(), ConnectionState.ONLINE);
            supervisor.onSessionLost();
            eq(supervisor.state(), ConnectionState.RECONNECTING);
            eq(sessionCloses.get(), 1);
            eq(udpCloses.get(), 1);
            eq(channelCloses.get(), 1);
            eq(await(supervisor.retry(creds())), ConnectionState.ONLINE);
            eq(fresh.get(), 2);
            supervisor.logout();
            eq(supervisor.state(), ConnectionState.DISCONNECTED);
            eq(sessionCloses.get(), 2);
            eq(udpCloses.get(), 2);
            eq(channelCloses.get(), 2);
            supervisor.logout();
            check(delivered.await(3, TimeUnit.SECONDS), "expected 8 ordered state transitions");
            eq(List.copyOf(events), List.of(
                    ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING, ConnectionState.ONLINE,
                    ConnectionState.RECONNECTING, ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING,
                    ConnectionState.ONLINE, ConnectionState.DISCONNECTED));
        }
    }

    private static void tlsFailure() throws Exception {
        AtomicInteger authCalled = new AtomicInteger();
        try (var supervisor = new ClientConnectionSupervisor(ENDPOINT,
                unused -> { throw new javax.net.ssl.SSLHandshakeException("Untrusted cert"); },
                (ch, c) -> { authCalled.incrementAndGet(); throw new AssertionError("Should not run auth"); },
                (ch, s) -> { throw new AssertionError("Should not bind"); }, t -> {}, 1)) {
            failCode(supervisor.login(creds()), "TLS_ERROR");
            eq(supervisor.state(), ConnectionState.FAILED);
            eq(authCalled.get(), 0);
        }
    }

    private static void authFailure() throws Exception {
        AtomicInteger channelCloses = new AtomicInteger();
        try (var supervisor = new ClientConnectionSupervisor(ENDPOINT,
                unused -> new FakeChannel(channelCloses),
                (ch, c) -> { throw new ConnectionFailure("AUTH_FAILED"); },
                (ch, s) -> { throw new AssertionError("Should not bind"); }, t -> {}, 1)) {
            failCode(supervisor.login(creds()), "AUTH_FAILED");
            eq(supervisor.state(), ConnectionState.FAILED);
            eq(channelCloses.get(), 1);
        }
    }

    private static void bindFailureCleansUp() throws Exception {
        AtomicInteger channelCloses = new AtomicInteger();
        AtomicInteger sessionCloses = new AtomicInteger();
        try (var supervisor = new ClientConnectionSupervisor(ENDPOINT,
                unused -> new FakeChannel(channelCloses),
                (ch, c) -> new FakeAuth("session", sessionCloses),
                (ch, s) -> { throw new ConnectionFailure("UDP_BIND_FAILED"); }, t -> {}, 1)) {
            failCode(supervisor.login(creds()), "UDP_BIND_FAILED");
            eq(supervisor.state(), ConnectionState.FAILED);
            eq(channelCloses.get(), 1);
            eq(sessionCloses.get(), 1);
        }
    }

    private static void logoutDuringAuthentication() throws Exception {
        CountDownLatch authStarted = new CountDownLatch(1);
        CountDownLatch releaseAuth = new CountDownLatch(1);
        CountDownLatch cleanupDone = new CountDownLatch(1);
        AtomicInteger callsToBind = new AtomicInteger();
        AtomicInteger closedChannel = new AtomicInteger();
        try (var supervisor = new ClientConnectionSupervisor(ENDPOINT,
                unused -> new FakeChannel(closedChannel),
                (ch, c) -> {
                    authStarted.countDown();
                    if (!releaseAuth.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test latch");
                    return new FakeAuth("late-result", new AtomicInteger()) {
                        @Override public void close() { super.close(); cleanupDone.countDown(); }
                    };
                },
                (ch, s) -> { callsToBind.incrementAndGet(); return new FakeUdp(new AtomicInteger()); },
                t -> {}, 1)) {
            CompletionStage<ConnectionState> pending = supervisor.login(creds());
            check(authStarted.await(3, TimeUnit.SECONDS), "authenticator must start");
            supervisor.logout();
            eq(supervisor.state(), ConnectionState.DISCONNECTED);
            failCancelled(pending);
            releaseAuth.countDown();
            check(cleanupDone.await(3, TimeUnit.SECONDS), "late auth session should be cleaned");
            eq(callsToBind.get(), 0);
            check(closedChannel.get() >= 1, "channel must close on logout");
            eq(supervisor.state(), ConnectionState.DISCONNECTED);
        } finally { releaseAuth.countDown(); }
    }

    private static void retryBudget() throws Exception {
        try (var supervisor = new ClientConnectionSupervisor(ENDPOINT,
                unused -> { throw new java.io.IOException("Connection refused"); },
                (c, p) -> { throw new AssertionError(); },
                (c, p) -> { throw new AssertionError(); }, t -> {}, 1)) {
            failCode(supervisor.login(creds()), "IO_ERROR");
            failCode(supervisor.retry(creds()), "IO_ERROR");
            failCode(supervisor.retry(creds()), "RETRY_EXHAUSTED");
            eq(supervisor.state(), ConnectionState.FAILED);
        }
    }

    private static void endpointAndCredentialValidation() {
        checkThrows(() -> new ServerEndpoint("localhost", 0));
        checkThrows(() -> new ServerEndpoint("  ", 9000));
        checkThrows(() -> new LoginCredentials("", new char[] {'x'}));
        LoginCredentials value = creds();
        value.close();
        checkThrows(value::copyPassword);
    }

    private static ConnectionState await(CompletionStage<ConnectionState> stage) throws Exception {
        return stage.toCompletableFuture().get(4, TimeUnit.SECONDS);
    }

    private static void failCancelled(CompletionStage<ConnectionState> stage) throws Exception {
        try {
            await(stage);
            throw new AssertionError("Expected cancellation");
        } catch (ExecutionException e) {
            check(e.getCause() instanceof java.util.concurrent.CancellationException,
                    "expected CancellationException");
        } catch (java.util.concurrent.CancellationException expected) {
            checks++;
        }
    }

    private static void failCode(CompletionStage<ConnectionState> stage, String code) throws Exception {
        try {
            await(stage);
            throw new AssertionError("Expected " + code);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof ConnectionFailure failure)) {
                throw new AssertionError("Unexpected failure", e);
            }
            eq(failure.code(), code);
        }
    }

    private static void checkThrows(Runnable runnable) {
        try { runnable.run(); throw new AssertionError("Expected failure"); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
    private static void eq(Object actual, Object expected) {
        check(java.util.Objects.equals(actual, expected), "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean actual, String message) {
        if (!actual) throw new AssertionError(message);
        checks++;
    }

    private record FakeChannel(AtomicInteger closes) implements VerifiedControlChannel {
        @Override public InputStream input() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream output() { return new ByteArrayOutputStream(); }
        @Override public void close() { closes.incrementAndGet(); }
    }
    private static class FakeAuth implements AuthenticatedSession {
        private final String id;
        private final AtomicInteger closes;
        FakeAuth(String id, AtomicInteger closes) { this.id = id; this.closes = closes; }
        @Override public String sessionId() { return id; }
        @Override public void close() { closes.incrementAndGet(); }
    }
    private record FakeUdp(AtomicInteger closes) implements BoundUdpSession {
        @Override public void close() { closes.incrementAndGet(); }
    }
}
