# Member 3 — Stage 2: TLS connector + ClientConnectionSupervisor

**Scope:** ready-to-integrate foundation for verified TCP/TLS and client session lifecycle.
**NOT complete:** the actual login wire protocol, authenticated UDP binding, heartbeat receiver, automatic retry, chat or local HTTP endpoints. Those depend on Member 1/2 interface decisions. Never call the mock tests a real end-to-end server test.

## Where to put files

Merge the contents of the Stage 2 ZIP into the root of `streamcollab-java-main`. This ZIP includes Stage 1 source/tests so it can be used on a fresh copy as well. It only adds Member 3's client/connection sources, Stage 1 + Stage 2 tests, this document and a test script. No `pom.xml` edits or other members' sources are included.

## Code responsibilities

| Java file | Function |
|---|---|
| `ConnectionState.java`, `ConnectionTransition.java`, `ConnectionStateMachine.java` | Stage 1 validated lifecycle |
| `ServerEndpoint.java` | Configured DNS name/IP and TCP port |
| `LoginCredentials.java` | Temporary char[] credentials with explicit zeroing; do not log |
| `VerifiedControlChannel.java`, `ControlChannelFactory.java` | Contract: channel exists only after certificate + hostname checks |
| `TlsControlChannelFactory.java` | JSSE socket connect with TCP timeout, read timeout, verified TLS + endpoint identity check |
| `SessionAuthenticator.java`, `AuthenticatedSession.java` | Member 2 must implement actual authenticated TCP request/response framing |
| `UdpBinder.java`, `BoundUdpSession.java` | Member 1/2/3 must implement actual cryptographically authenticated UDP binding |
| `ConnectionEventSink.java` | Ordered state transitions passed toward future UI event dispatcher |
| `ConnectionFailure.java` | Safe uppercase error code, no raw auth exceptions |
| `ClientConnectionSupervisor.java` | Asynchronous connection, login/bind outcome, state, manual bounded retry, resource cleanup, logout cancellation, stale completion protection |

## Why it is designed this way

`DISCONNECTED -> CONNECTING -> AUTHENTICATING -> ONLINE` is allowed only after the corresponding stage **actually** succeeds. `TlsControlChannelFactory` validates certificate trust and identity (`SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")`) and does not accept all certificates. This is a checked TLS channel, NOT a plain TCP socket.

The supervisor runs blocking I/O on Java 21 virtual threads. It never blocks the HTTP event dispatcher while waiting for TLS/auth/UDP. State updates and event enqueueing are synchronized, with a single ordered event consumer. The initial deployment needs a **bounded** event queue and a real UI event journal, to be agreed with Member 5.

`logout()` invalidates the attempt generation immediately, closes resources, clears credentials and fails a pending login future with cancellation. Any late auth result is rejected and cleaned up. `onSessionLost()` closes the old session and sets `RECONNECTING`. `retry(new LoginCredentials(...))` always requires fresh credentials, so **no password is stored across reconnection**. A maximum number of manual retries can be configured; automatic retry with token refresh/backoff is deliberately NOT yet implemented. The UDP transport will call `onSessionLost()` after its heartbeat/session timeout logic is ready.

`SESSION_READY` is published only when both authenticated login and the UDP binding adapter return success. Mock adapters are for tests **only**; production must NOT use `session -> () -> {}` or any fake binder, as that would wrongly mark unverified sessions ONLINE.

## Running tests on macOS, from repo root

Requires Java 21 and `keytool` (included with JDK):

```bash
java -version
bash scripts/test-member3-stage2.sh
```

The test script compiles only Member 3's connection sources using `javac`; Maven remains optional at this milestone. You may also run `mvn compile` if Maven is installed.

Expected output on Java 21:

```text
PASS: 27 Member 3 connection lifecycle checks
PASS: 43 Member 3 stage 2 supervisor checks (mock auth/UDP)
PASS: 3 real TLS scenarios (trusted host / hostname mismatch / untrusted cert)
```

TLS verification test: the script generates an **ephemeral** self-signed PKCS#12 cert with `DNS:localhost` SAN in a temporary folder, tests a trusted connection to `localhost`, rejects hostname mismatch with `127.0.0.1`, and rejects an untrusted cert. It removes temporary material on exit. These tests do **not** verify the team's real server login or UDP transport.

## Example integration skeleton (do NOT run until real adapters exist)

```java
SSLContext ssl = SSLContext.getDefault(); // Configure the correct trusted CA/server cert.
var supervisor = new ClientConnectionSupervisor(
    new ServerEndpoint("localhost", 9000),
    new TlsControlChannelFactory(ssl, Duration.ofSeconds(5), Duration.ofSeconds(10)),
    realAuthenticator,     // Member 2: not implemented in Stage 2
    realUdpBinder,         // Member 1/2/3: not implemented in Stage 2
    connectionEventSink,  // Member 5 integration later
    2                     // maximum MANUAL retries
);
char[] originalPassword = /* obtained from the user UI */;
try {
    var login = supervisor.login(new LoginCredentials("username", originalPassword));
    // login completes asynchronously only after verified UDP binding
} finally {
    Arrays.fill(originalPassword, '\0');
}
```

For a local self-signed demo server, add its public certificate to a trusted keystore and load that trust into the SSLContext. Do **not** use a trust-all TrustManager or disable the hostname check. The actual certificate should list the server DNS name/IP in its subjectAltName.

## Integration contracts to confirm WITH TEAM before Stage 3

1. **Member 2:** TCP framing, exact JSON login request + response, expiry errors, session ID and user ID, safe password handling, certificate generation and truststore instructions.
2. **Member 1/2:** Server-issued UDP binding challenge/proof, how keys are provisioned over TLS, per-session key/nonce/replay rules, authenticated endpoint transitions, and bounded binding timeout.
3. **Member 1:** PeerTimedOut signal forwarded to `supervisor.onSessionLost()`, session cleanup, no replay of old sends/transfers after reconnect.
4. **Member 5:** `CONNECTION_CHANGED` event schema and bounded ordered event journal, cancellation and snapshot semantics.
5. **Member 2/3:** Decide whether reconnect uses freshly entered credentials or a short-lived refresh grant; define automatic retry budget/backoff if implemented. Never cache plaintext login password for retries.

Additional hardening before E2E: enforce a bounded timeout and cancellation for every auth and UDP binding adapter (SSLSocket read timeout is not a complete UDP timeout), and fail pending chat/file operations once on session loss. Do not claim production readiness from a mock-only `ONLINE` test.
