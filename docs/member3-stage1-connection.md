# Member 3 — Stage 1: Connection lifecycle state machine

This milestone is a **state-only foundation**. It does not claim to implement TCP/TLS, authentication, UDP binding, heartbeat or reconnect scheduling. It follows `docs/state-machines.md`, `docs/module-interfaces.md` and `task_assignment.md`.

## Files

- `client/connection/ConnectionState.java` — six states shared with UI.
- `client/connection/ConnectionTransition.java` — immutable transition event with UTC time and non-sensitive reason code.
- `client/connection/ConnectionStateMachine.java` — validates transitions, synchronizes state updates; returns immutable events.
- `src/test/java/org/streamcollab/unit/ConnectionStateMachineSmokeTest.java` — standalone checks, no external test library.

## State flow

`DISCONNECTED -> CONNECTING -> AUTHENTICATING -> ONLINE`

- `startLogin()` means the user requested login. It **does not** open a socket.
- `tlsVerified()` must be called only after successful JSSE TLS verification of certificate and host identity.
- `sessionReady()` must be called only after successful account authentication **and** authenticated UDP binding.
- `sessionLost()` moves `ONLINE -> RECONNECTING`; a supervisor closes old session resources, fails pending work once, and negotiates a fresh session/key context before `retry()`.
- `retry()` moves `RECONNECTING|FAILED -> CONNECTING`; a supervisor enforces retry budget and backoff.
- `fail("TIMEOUT")` (or another stable uppercase code) moves `CONNECTING|AUTHENTICATING|RECONNECTING -> FAILED`.
- `logout()` returns to `DISCONNECTED` from any state and is idempotent. The supervisor cancels tasks/operations and closes sockets.

The state machine intentionally does not own I/O or run callbacks. Its caller owns routing each returned `ConnectionTransition` into the single ordered UI event dispatcher, producing `CONNECTION_CHANGED` events with the current state; never put credentials or keys in the event. Transitions are synchronized, but **if multiple threads call it**, the caller must serialize dispatch in state-transition order to avoid reordering visible UI events.

## How to run (project root)

Requires Java 21. `mvn compile` verifies main sources when Maven is available.

```bash
mvn compile
mkdir -p target/member3-smoke
javac -d target/member3-smoke \
  src/main/java/org/streamcollab/client/connection/*.java \
  src/test/java/org/streamcollab/unit/ConnectionStateMachineSmokeTest.java
java -cp target/member3-smoke org.streamcollab.unit.ConnectionStateMachineSmokeTest
```

Expected output: `PASS: 27 Member 3 connection lifecycle checks` (count may change as tests grow). `mvn test` is **not** a substitute for this command: no JUnit dependency has been agreed/installed yet.

## Integration gates / next milestone

1. With Member 2, freeze TLS client authentication payload/response, certificate trust/hostname strategy, session expiry, fresh key provisioning and UDP binding confirmation.
2. With Member 1, freeze transport-facing send/receive/event types before implementing chat or binding the dispatcher.
3. Build a `ClientConnectionSupervisor` that owns TLS, authentication, UDP bind, heartbeat, bounded reconnection and socket/resource cleanup; invoke the state machine from actual outcomes only.
4. With Member 5, freeze event snapshot/cursor semantics and HTTP token bootstrap in `docs/local-http-api.md` before writing HTTP endpoints.
