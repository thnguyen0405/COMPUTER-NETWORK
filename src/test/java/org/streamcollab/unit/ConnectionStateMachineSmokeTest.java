package org.streamcollab.unit;

import org.streamcollab.client.connection.ConnectionState;
import org.streamcollab.client.connection.ConnectionStateMachine;
import org.streamcollab.client.connection.ConnectionTransition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Dependency-free checks; run directly with java until a test framework is chosen. */
public final class ConnectionStateMachineSmokeTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private static int checks;

    public static void main(String[] args) {
        successfulLoginLogout();
        failureAndRetry();
        reconnectAndFailure();
        invalidTransitions();
        logoutFromEveryState();
        exhaustedReconnectBudget();
        System.out.println("PASS: " + checks + " Member 3 connection lifecycle checks");
    }

    private static void successfulLoginLogout() {
        var machine = new ConnectionStateMachine(FIXED_CLOCK);
        eq(machine.state(), ConnectionState.DISCONNECTED);
        ConnectionTransition first = machine.startLogin();
        eq(first.previous(), ConnectionState.DISCONNECTED);
        eq(first.current(), ConnectionState.CONNECTING);
        eq(first.timestamp(), FIXED_CLOCK.instant());
        eq(machine.tlsVerified().current(), ConnectionState.AUTHENTICATING);
        eq(machine.sessionReady().current(), ConnectionState.ONLINE);
        eq(machine.logout().orElseThrow().current(), ConnectionState.DISCONNECTED);
        check(machine.logout().isEmpty(), "Second logout must be idempotent");
    }

    private static void failureAndRetry() {
        var machine = new ConnectionStateMachine();
        machine.startLogin();
        eq(machine.fail("TIMEOUT").current(), ConnectionState.FAILED);
        eq(machine.retry().current(), ConnectionState.CONNECTING);
        machine.tlsVerified();
        eq(machine.fail("AUTH_FAILED").current(), ConnectionState.FAILED);
        eq(machine.logout().orElseThrow().current(), ConnectionState.DISCONNECTED);
    }

    private static void reconnectAndFailure() {
        var machine = new ConnectionStateMachine();
        machine.startLogin();
        machine.tlsVerified();
        machine.sessionReady();
        eq(machine.sessionLost().current(), ConnectionState.RECONNECTING);
        eq(machine.retry().current(), ConnectionState.CONNECTING);
        // The old session must not be reused; orchestrator handles new keys/auth.
        eq(machine.fail("TLS_ERROR").current(), ConnectionState.FAILED);
    }

    private static void logoutFromEveryState() {
        var connecting = new ConnectionStateMachine();
        connecting.startLogin();
        eq(connecting.logout().orElseThrow().current(), ConnectionState.DISCONNECTED);

        var authenticating = new ConnectionStateMachine();
        authenticating.startLogin();
        authenticating.tlsVerified();
        eq(authenticating.logout().orElseThrow().current(), ConnectionState.DISCONNECTED);

        var reconnecting = new ConnectionStateMachine();
        reconnecting.startLogin();
        reconnecting.tlsVerified();
        reconnecting.sessionReady();
        reconnecting.sessionLost();
        eq(reconnecting.logout().orElseThrow().current(), ConnectionState.DISCONNECTED);

        var failed = new ConnectionStateMachine();
        failed.startLogin();
        failed.fail("TIMEOUT");
        eq(failed.logout().orElseThrow().current(), ConnectionState.DISCONNECTED);
    }

    private static void exhaustedReconnectBudget() {
        var machine = new ConnectionStateMachine();
        machine.startLogin();
        machine.tlsVerified();
        machine.sessionReady();
        machine.sessionLost();
        eq(machine.fail("RETRY_EXHAUSTED").current(), ConnectionState.FAILED);
    }

    private static void invalidTransitions() {
        var machine = new ConnectionStateMachine();
        throwsIllegalState(machine::sessionReady);
        throwsIllegalState(machine::retry);
        eq(machine.state(), ConnectionState.DISCONNECTED);
        machine.startLogin();
        throwsIllegalState(machine::startLogin);
        throwsIllegalState(machine::sessionReady);
        try {
            machine.fail("raw user password: 1234");
            throw new AssertionError("Unstructured error reason should be rejected");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        eq(machine.state(), ConnectionState.CONNECTING);
    }

    private static void eq(Object actual, Object expected) {
        check(expected.equals(actual), "Expected " + expected + " but got " + actual);
    }

    private static void check(boolean result, String message) {
        if (!result) {
            throw new AssertionError(message);
        }
        checks++;
    }

    private static void throwsIllegalState(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            checks++;
        }
    }
}
