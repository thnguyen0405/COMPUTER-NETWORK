# Member 3 — Stage 3: UDP security building block

## Scope and status

This stage adds Java 21-only security primitives under `org.streamcollab.shared.security`:

- `PeerRole`: CLIENT or SERVER determines directional send/receive keys.
- `UdpSessionCrypto`: AES-256-GCM packet sealing/opening, HKDF session separation,
  authenticated header bytes, bounded replay protection, 1200-byte packet cap.
- `HkdfSha256`: internal RFC 5869 extract/expand with HmacSHA256.
- `ReplayWindow`: internal 256-packet sliding window (configurable 64–4096).
- `UdpSecurityException`: stable error codes without secrets.

**Not yet integrated:** TLS login/key provisioning, actual UDP binding, wire packet
parsing/serialization, transport retransmissions/ACKs, server routing, chat/file
protocol, local HTTP bridge. Stage 2's TLS supervisor still injects mock authentication
and UDP binding. **Do not claim end-to-end encryption:** the server decrypts traffic.

## Key installation and authenticated packet format

1. Server creates **fresh random session secret** via `UdpSessionCrypto.newSessionSecret()`.
2. Server transfers the secret and a session identifier to the authenticated client
   **over the verified TLS channel**, not over UDP or local HTTP. The actual TLS
   message schema must be agreed with Member 2; this stage does not define one.
3. Client calls `install(secret, sessionId, PeerRole.CLIENT)`, server calls
   `install(secret, sessionId, PeerRole.SERVER)`.
4. HKDF-SHA256 binds output to protocol version and `sessionId`; it derives
   independent 32-byte AES keys for client-to-server and server-to-client plus
   independent 4-byte nonce prefixes for both directions.
5. For each datagram, the transport **allocates a new monotonically increasing
   64-bit nonnegative packet number** including retransmissions. This must be
   unique for the entire lifetime of that directional key.
6. Nonce = 4-byte derived directional prefix || 8-byte big-endian packet number.
   GCM uses a 128-bit tag. Caller-supplied serialized header **plus** packet
   number are authenticated (AAD); body bytes are encrypted.
7. `seal(packetNumber, header, payload)` returns `ciphertext || tag`; the final
   network packet is the header followed by the returned bytes. The exact header
   byte offsets and encoding are **owned by Member 1** and intentionally not
   defined by this stage.
8. `open(packetNumber, header, encryptedBody)` verifies the GCM tag **before**
   recording a packet number in the anti-replay window. Replay/too-old packets
   throw `REPLAY_REJECTED` and are not delivered to ACK/state processing.

Important interface preconditions:

- **Member 1 must parse the packet number from the exact header bytes used in
  `open` and must check session ID before choosing the session key.** The number
  argument MUST equal the number encoded in the header; this security module
  does not parse the (still undecided) wire format.
- **Member 1 must keep one active `UdpSessionCrypto` per peer and session.**
  Never restart packet numbers with the same keys in another instance; the
  monotonicity guard is local to each instance and cannot prevent cross-process
  reuse. Retransmitting the same stream segment must use a NEW packet number.
- **Member 2 must establish fresh independently generated secrets on every
  login/reconnect** and never derive UDP keys directly from user passwords.
- Clear the caller-owned secret byte array after both local endpoint instances
  have derived their keys (on separate devices, each endpoint clears its copy).
  `close()` best-effort clears application key buffers; the JVM cannot promise
  zeroization of provider/JIT/GC copies.
- This implementation does not negotiate key rotation while a session is live;
  set a session lifetime/rekey policy before production use. Long packet-number
  exhaustion is a hard failure: close and rekey, do not wrap.
- Fail closed if the header or encrypted data exceed the agreed packet limit.
  1200 bytes includes header + ciphertext + GCM tag.

## Sample integration (pseudocode)

```java
// Both sides received the same random session secret via VERIFIED TLS.
UdpSessionCrypto crypto = UdpSessionCrypto.install(secret, sessionId, PeerRole.CLIENT);
java.util.Arrays.fill(secret, (byte) 0);

// Member 1's codec produces the actual header, containing sessionId and packetNumber.
byte[] ciphertextAndTag = crypto.seal(packetNumber, encodedHeader, payload);
// Send: encodedHeader || ciphertextAndTag

// For incoming UDP: Member 1 bounds/parses header, selects the correct session
// and calls open() before allowing any transport state/ACK changes.
byte[] plaintext = crypto.open(parsedPacketNumber, receivedHeader, receivedBody);

// On logout, loss of session, or reconnect:
crypto.close();
```

Do not reuse one `UdpSessionCrypto` for both client and server, or across two sessions.

## Concurrency and error behavior

- Per-instance methods are synchronized; unique send packet-number checks and
  replay checks are atomic for the instance. This is correctness, not a throughput
  optimization; a single state owner per peer remains the project architecture.
- Duplicate numbers, older numbers, modified header/ciphertext/tag, incorrect
  session secret/ID/role, invalid sizes, and closed crypto sessions fail.
- Do not log or display raw secrets, GCM keys, plaintext, or complete ciphertext
  in exception messages. Do not let rejected packets mutate trusted ACK state.
- `ReplayWindow` admits out-of-order authenticated packets in the most recent
  256-number window, but does not replace Member 1's logical stream duplicate
  suppression or selective ACK accounting.

## Local tests

```bash
bash scripts/test-member3-stage3.sh
bash scripts/test-member3-stage2.sh
```

Stage 3 requires only `javac`/`java` (Java 21) and no additional Maven dependencies.
Stage 2 tests remain unchanged. `mvn compile` can be run independently when Maven
is installed. Test results describe isolated behavior, **not live server integration**.

## Coordination before merging production integration

1. Member 1: freeze UDP header layout; packet number allocation and retry behavior;
   enforce the 1200-byte packet cap at the combined header/body boundary.
2. Member 2: freeze TLS session-secret provision, session IDs, UDP binding proof,
   key install/remove lifecycle, and reconnect policy.
3. Member 3: adapt these primitives to the agreed codec/UDP transport, ensure
   cleanup in `ClientConnectionSupervisor` active-session lifecycle, and test
   against a live server/agent pair.
4. The security code is shared, so review from Member 1 (transport) and Member 2
   (authentication/session lifecycle) is required.
