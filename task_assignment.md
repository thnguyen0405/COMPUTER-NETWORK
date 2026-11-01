# Assignment 1 — Team Responsibilities and Integration Contract

## 1. Architecture and scope

```text
Browser UI <-- localhost HTTP --> Client Agent
                                      |
                        TCP + TLS: authentication/session setup
                        UDP: chat, file chunks, presence/heartbeat
                                      |
                                Central Server
                                      |
                              Other Client Agents
```

Each participant runs one Client Agent and opens its local browser UI. The server authenticates users, maintains presence, and relays messages and files. Clients do not send application data directly to one another in the baseline version.

The UDP transport and security implementation are shared by server and client. The server decrypts incoming application messages and encrypts outgoing messages for each destination; this provides client–server encryption, not end-to-end encryption.

### 1.1 Baseline features

- TCP handshake, authentication, and session setup.
- UDP global chat, direct chat, heartbeat, and file transfer.
- Independent streams, selective acknowledgment, retransmission, ordering, and duplicate suppression.
- Bounded queues, receiver flow control, pacing, and congestion control.
- Authenticated encryption using a standard cryptographic library.
- Browser UI backed by a local HTTP bridge, without HTTP frameworks or WebSockets.
- Concurrent operation with at least 20 clients in the integration test.

Do not add P2P routing, multiple servers, persistent chat history, group administration, read receipts, or resumable transfers until the baseline passes. After reconnecting, pending transfers fail explicitly and can be restarted; automatic resume is out of scope.

### 1.2 Adopted decisions and instructor clarifications

| Decision | Adopted team baseline |
|---|---|
| Language and runtime | Java 21 for server, Client Agent, shared modules, and Java test tools. Maven single-module layout; package root `org.streamcollab`. Browser UI remains HTML/CSS/JavaScript. |
| Concurrency | JDK sockets with virtual threads for blocking I/O, bounded queues, and one logical state owner per peer. No thread/task per packet and no unbounded executor submission. |
| TCP versus UDP | TCP with TLS for handshake/session setup; UDP for chat, file data, and heartbeat. Instructor clarification remains necessary because the assignment contains conflicting descriptions. |
| Browser UI | Local Java Client Agent serves HTTP and browser-side JavaScript using JDK sockets; no HTTP frameworks or WebSockets. Confirm this interpretation with the instructor. |
| Congestion algorithm | CUBIC with pacing. Confirm the required depth with the instructor. A simplified prototype must not be labeled a completed CUBIC implementation. |
| Libraries | JDK networking and JSSE/JCA/JCE for TLS and cryptography; AES-GCM for UDP. No ready-made reliable-UDP or QUIC stack. JSON/test dependency versions will be selected centrally before implementation. |
| Authentication | Pre-created local accounts, per-account salted password hashes using a standard cryptographic implementation; no registration UI. |
| TLS trust | Development CA/certificates and explicit client trust configuration; certificate and server-identity verification remain enabled. |

These are accepted team decisions, not claims of instructor approval. See [the decision register](docs/decisions.md) for rationale, remaining details, and change ownership. Members may work against mocks while instructor clarification is pending.

### 1.3 Instructor guidance reported on 2026-10-28

The team must design its own demo scenarios and be able to explain why each design was chosen and what trade-offs it introduces. Keep the report concise but complete, with emphasis on protocol definition and concurrency design. Congestion benchmarking is not required given the limited number of available machines.

This guidance removes large-scale congestion experiments from the required workload; it does not explicitly remove congestion design/implementation from the assignment. Retain the current congestion target and focused local correctness checks, but do not spend the team's time setting up a multi-machine congestion benchmark. Local multi-client concurrency checks are still useful and do not require 20 physical machines.

| Member | Additional presentation responsibility |
|---|---|
| 1 | Explain packet fields, framing, ACK/retry/order, stream independence, and reliability overhead. Supply a concrete packet/sequence example. |
| 2 | Explain server routing, state ownership, bounded queues, shared-state protection, and the central relay trade-off. Coordinate a concise report. |
| 3 | Explain Java virtual threads, client lifecycle, TLS/UDP security boundaries, and why a browser needs a local agent. Explain how blocking I/O is kept out of peer-state processing. |
| 4 | Explain chunking, reassembly, checksum/completion semantics, and flow control versus congestion control. Prioritize file correctness; do not lead a multi-machine congestion benchmark. |
| 5 | Own the team's reproducible demo script, reset/setup procedure, visible evidence and rehearsal; coordinate functional checks rather than a congestion benchmark campaign. |

Every member prepares a short explanation of their main choice, one realistic alternative, its trade-off, and the evidence shown in the demo. Follow [the demo plan](docs/demo-plan.md) and [decision rationale](docs/decisions.md). No fixed report page count or demo duration was specified by this guidance.

## 2. Ownership matrix

| Member | Primary responsibility | Owned paths | Required reviewer |
|---|---|---|---|
| 1 | Protocol and reliable UDP transport | `src/main/java/org/streamcollab/shared/protocol/`, `src/main/java/org/streamcollab/shared/transport/`, `src/main/java/org/streamcollab/shared/models/` | Member 4 for transport; Member 3 for security boundaries |
| 2 | Server, authentication, sessions, routing, integration coordination | `src/main/java/org/streamcollab/server/`, `config/`, root README and dependency manifest | Member 3 |
| 3 | Client Agent, connection lifecycle, security, local HTTP bridge | `src/main/java/org/streamcollab/client/app/`, `src/main/java/org/streamcollab/client/connection/`, `src/main/java/org/streamcollab/client/chat/`, `src/main/java/org/streamcollab/client/httpbridge/`, `src/main/java/org/streamcollab/shared/security/` | Member 1 for transport/security; Member 5 for HTTP API |
| 4 | File transfer and congestion control | `src/main/java/org/streamcollab/client/files/`, `src/main/java/org/streamcollab/shared/congestion/` | Member 1 |
| 5 | Browser UI and system test tooling | `src/main/resources/ui/`, `tools/`, `src/test/java/org/streamcollab/e2e/` | Member 3 |

Each member owns corresponding unit tests. Integration tests are co-owned by the affected module owners. Member 5 coordinates system testing but is not responsible for writing every member's tests.

Member 2 coordinates integration and dependency changes, not all implementation work. Member 1 maintains the canonical shared types. Other members propose shared-type changes instead of defining duplicate local types.

## 3. Detailed assignments

### Member 1 — Protocol and reliable UDP transport

**Implement:**

1. Versioned packet headers, application message schemas, serialization, validation, and protocol error codes.
2. UDP send/receive loops and per-peer/per-stream state.
3. Message segmentation/reassembly, stream sequencing, selective ACK ranges, loss detection, retransmission, and duplicate suppression.
4. Bounded reassembly/send/receive queues and advertised receiver credit.
5. A scheduler that prioritizes control/chat while still allowing file streams to progress.
6. Hooks into Member 3's security module and Member 4's congestion controller.

**Do not implement:** login policy, file filesystem operations, UI, or a separate congestion algorithm.

**Deliver:** `docs/protocol.md`, transport state diagrams, shared API mocks, protocol/transport tests.

**Acceptance:**

- Lost segments are retransmitted without delivering duplicate application messages.
- Reordering does not corrupt messages or violate order within a stream.
- A missing file segment does not block delivery on an independent chat stream.
- Malformed or oversized packets cannot crash the process or grow buffers without limit.
- Transport ACKs are never presented as final recipient delivery acknowledgments.

### Member 2 — Server and integration coordination

**Implement:**

1. TCP/TLS server handshake using Member 3's shared security facilities.
2. Account verification, session creation/expiry, authenticated UDP endpoint binding, and active user registry.
3. Global/direct chat routing and application-level delivery receipts.
4. File control/data relay, including recipient validation and backpressure.
5. Heartbeat processing, presence snapshots, disconnect cleanup, and asynchronous handling of multiple clients.
6. Central configuration and documented startup commands.

**Do not implement:** a second transport, UDP encryption primitives, file reassembly on the server, or browser-specific logic.

**Deliver:** server modules, server tests, server state diagrams, root README, `docs/decisions.md`.

**Acceptance:**

- Twenty clients can log in, exchange messages, and disconnect without restarting the server.
- The server derives sender identity from the authenticated session rather than trusting payload fields.
- Offline recipients and expired sessions produce defined errors.
- File relay buffers stay bounded when the recipient is slow or disconnects.
- Clients cannot bind another user's session to their UDP endpoint.

### Member 3 — Client Agent, security, and local HTTP bridge

**Implement:**

1. Client TCP/TLS setup, authentication, UDP binding, heartbeat scheduling, and reconnect state machine.
2. Application event dispatcher and chat service on top of Member 1's transport.
3. Shared authenticated-encryption module, directional session keys, nonce rules, replay protection, and key cleanup.
4. Local HTTP endpoints, static UI serving, uploads/downloads, long polling, and error mapping.
5. Translation of chat/file/session events into the shared UI event format.

**Do not implement:** ACK/retransmission independently, file checksum/reassembly logic, or frontend rendering.

**Deliver:** client/security modules, `docs/local-http-api.md`, client/security/HTTP tests, a mock HTTP server or fixtures matching the API.

**Acceptance:**

- HTTP commands return promptly while network transfers continue asynchronously.
- Authentication failures, disconnects, and timeouts produce visible, defined events.
- Modified ciphertext is rejected; retransmission never reuses an encryption nonce.
- Local HTTP access is restricted to loopback, with origin validation and an agent-local API token.
- Browser-provided arbitrary filesystem paths are never accepted as read/write authority.

### Member 4 — File transfer and congestion control

**Implement:**

1. Offer/accept/reject/cancel lifecycle, file metadata, chunk generation, and progress tracking.
2. Bounded-memory file reads/writes, temporary files, checksum verification, and completion acknowledgment.
3. Filename sanitization, destination handling, size limits, and cleanup after failure/cancel.
4. Per-peer congestion control, pacing, and feedback hooks for ACK/loss events.
5. File and congestion mocks so other members can integrate before completion.

**Do not implement:** a second ACK protocol, raw UDP access outside transport, HTTP upload parsing, or server routing.

**Deliver:** file/congestion modules, transfer state diagrams, algorithm/trade-off explanation, and focused correctness tests. Multi-machine congestion benchmarking is not required.

**Acceptance:**

- Empty, binary, and large files reproduce the sender's SHA-256 checksum.
- Memory use is bounded rather than proportional to the entire file size.
- Duplicate chunks do not inflate progress; incomplete files are never exposed as completed downloads.
- Cancel/reject/disconnect leave a defined terminal state and clean up temporary data.
- The congestion controller governs aggregate traffic to a peer, including retransmissions, rather than each file independently.

### Member 5 — UI and system validation

**Implement:**

1. Login, connection status, online users, global/direct chat, file offer dialogs, progress, cancel, and download UI.
2. An HTTP API adapter matching Member 3's contract; UI state driven by events and snapshots.
3. A configurable network simulator for loss, delay, duplication, and reordering.
4. A local multi-client runner, team-designed demo scenarios, result collection, reset procedure, and rehearsal checklist. Physical machines are not required one per client.

**Do not implement:** networking reliability in JavaScript, encryption key management, or a different backend API.

**Deliver:** frontend, `docs/test-plan.md`, `docs/demo-plan.md`, functional test results, demo instructions, and test tooling. Prioritize a reproducible functional demo over benchmark tooling.

**Acceptance:**

- UI distinguishes server acceptance, recipient delivery, byte progress, verification, and final completion.
- Repeated clicks do not create unintended duplicate sends; failures are actionable.
- Event cursor expiry triggers snapshot resynchronization.
- Simulator settings and random seed are recorded so failures can be reproduced.
- Every member can run the demo from the README on a clean setup.

## 4. Project structure

```text
assignment-1/
├── pom.xml
├── README.md
├── task_assignment.md
├── .gitignore
├── docs/
│   ├── README.md
│   ├── decisions.md
│   ├── architecture.md
│   ├── java-development.md
│   ├── module-interfaces.md
│   ├── protocol.md
│   ├── state-machines.md
│   ├── local-http-api.md
│   ├── test-plan.md
│   ├── demo-plan.md
│   ├── diagrams/
│   └── report/
├── src/
│   ├── main/
│   │   ├── java/org/streamcollab/
│   │   │   ├── shared/{models,protocol,transport,security,congestion}/
│   │   │   ├── server/{app,auth,sessions,presence,routing}/
│   │   │   └── client/{app,connection,chat,files,httpbridge}/
│   │   └── resources/ui/{css,js,assets}/
│   └── test/
│       ├── java/org/streamcollab/{unit,integration,e2e}/
│       └── resources/fixtures/
├── tools/{network_simulator,multi_client_runner}/
├── scripts/
├── config/
└── runtime/                 # Git-ignored local data
```

Brace notation abbreviates real subdirectories. Source directories contain placeholders until their owners implement them; there is no runnable application yet. The project uses one Maven module to keep integration simple. Package boundaries still enforce ownership. Do not commit credentials, private keys, generated downloads, or large fixtures. See [Java development conventions](docs/java-development.md).

## 5. Shared data and module interfaces

The following signatures describe behavior in language-neutral notation. Java mapping is fixed in [module interfaces](docs/module-interfaces.md): asynchronous results use `CompletionStage<T>`, immutable data uses records, and closed event families use sealed interfaces. `async` means callers must not block the UI bridge or peer state owner while waiting.

### 5.1 Common types and conventions

| Type | Meaning |
|---|---|
| `UserId` | Stable account identifier |
| `SessionId` | New opaque identifier for each authenticated session |
| `PeerId` | Immediate network peer; normally the server from a client's perspective |
| `StreamId` | Stream identifier scoped to a session; never reused within that session |
| `MessageId` | Logical application message identifier retained during retry |
| `TransferId` | File-transfer identifier retained through its lifecycle |
| `RequestId` | Request/response correlation and command deduplication identifier |

Application-facing IDs are opaque strings. Wire packet numbers, segment sequences, and stream numbers use unsigned integers with widths specified by Member 1. Do not expose 64-bit counters to JavaScript as potentially lossy numbers; encode them as decimal strings where needed.

- Use UTF-8 for text, bytes for file data, and network byte order for binary integer fields.
- Use UTC timestamps for user-facing events and monotonic time for timeout/RTT calculations.
- JSON control messages use `snake_case` field names. Binary chunks are not Base64-encoded on the UDP path.
- Application messages have `{type, request_id?, body}` with a strict schema for each type.
- Errors have `{code, message, retryable}`. Codes include `INVALID_REQUEST`, `AUTH_FAILED`, `SESSION_EXPIRED`, `PEER_OFFLINE`, `TIMEOUT`, `BACKPRESSURE`, `PROTOCOL_ERROR`, `FILE_REJECTED`, `HASH_MISMATCH`, `CANCELLED`, and `IO_ERROR`.

### 5.2 Application ↔ transport

```text
open_stream(peer_id, purpose: CONTROL | CHAT | FILE) -> StreamId

async send(peer_id, stream_id, message_id, payload: bytes, deadline)
    -> SendReceipt {message_id, status: PEER_RECEIVED}

async receive() -> TransportEvent
async close_stream(peer_id, stream_id)
async close_peer(peer_id, reason)

TransportEvent = MessageReceived {peer_id, stream_id, message_id, payload}
               | PeerTimedOut {peer_id}
               | StreamClosed {peer_id, stream_id, reason}
```

`send` completes only when the immediate peer's transport acknowledges the message, or raises a typed error. Do not emit a second independent failure result for the same call. Application tasks translate the result into application events as needed.

ACK means admission into bounded receive state, not application processing or disk durability. The receiver must stop advertising credit when downstream processing cannot keep up. Member 1 and Member 2 must test that server relaying propagates this backpressure.

Use one stream for global chat, separate streams for direct conversations, and a separate stream for each transfer. A stream preserves its own order; independent streams have no shared delivery-order dependency. Acknowledgment processing must not wait behind ordered application data.

Member 1 documents stream opening/closure, bidirectional identification rules, maximum message size, sequence wrap policy, ACK timing, retransmission timers, and receiver-credit units before transport integration.

### 5.3 Protocol ↔ security ↔ UDP

```text
encode_message(AppMessage) -> bytes
decode_message(bytes) -> AppMessage
encode_header(PacketHeader) -> bytes
decode_header(datagram) -> PacketHeader

install_session_keys(peer_id, session_id, key_material)
seal(peer_id, header, plaintext) -> encrypted_datagram
open(peer_id, datagram) -> AuthenticatedPacket
remove_session_keys(peer_id)
```

Send pipeline: encode → segment → assign packet number → authenticate/encrypt → send.

Receive pipeline: bounded header validation → authenticate/decrypt → update ACK/replay state → reassemble/order → decode → dispatch.

Packet headers include version, packet type, session identifier, stream identifier, packet number, stream sequence, payload length, and flags. Member 1 owns the exact byte layout and ACK encoding in `docs/protocol.md`.

The transport allocates a new monotonically increasing packet number for every outbound datagram, including retries. Retried segments retain their logical stream sequence. Security owns directional key derivation, nonce construction, authenticated headers, and replay validation. Never acknowledge or mutate trusted protocol state based on an unauthenticated packet.

Session key material is established over verified TLS. It never enters UI events or logs. Member 3 specifies the key setup and reconnect/key replacement procedure before encrypted transport integration.

### 5.4 Transport ↔ congestion controller

```text
get_send_budget(peer_id, now) -> {allowed_bytes, next_send_at}
on_packet_sent(peer_id, packet_number, wire_bytes, sent_at)
on_packets_acked(peer_id, acked_packets, received_at)
on_packets_lost(peer_id, lost_packets)
on_path_reset(peer_id)
```

Transport detects losses and reports each transition once. The controller calculates congestion window and pacing; it never writes to sockets. Scheduling must satisfy congestion budget and receiver credit. ACK-only traffic uses a separately rate-limited path and does not wait for application-data credit.

Member 1 and Member 4 define the exact ACK/loss metadata record, in-flight byte accounting, and RTT sampling rules together before implementation. All streams to a peer share one controller, including retransmissions.

### 5.5 Sessions and routing

```text
async authenticate(credentials) -> AuthResult
get_session(session_id) -> Session?
get_online_users() -> UserSummary[]
bind_udp_endpoint(session_id, endpoint, proof)
touch_session(session_id, now)
expire_session(session_id, reason)
async route(sender_session_id, message) -> RouteResult

AuthResult = {user_id, session_id, heartbeat_interval_ms, session_timeout_ms}
UserSummary = {user_id, display_name, status}
```

The server validates an authenticated UDP binding proof before associating an endpoint with a session. A source address or claimed session ID alone is insufficient. Member 2 and Member 3 specify this exchange and heartbeat expiry in the shared state diagram.

Application messages:

```text
CHAT_SEND       {message_id, target, text}
CHAT_DELIVER    {message_id, sender_id, text, server_time}
CHAT_RECEIPT    {message_id, recipient_id?, status}
PRESENCE_UPDATE {revision, users}
HEARTBEAT      {timestamp}

target = {kind: GLOBAL} | {kind: DIRECT, user_id}
status = ACCEPTED_BY_SERVER | DELIVERED_TO_RECIPIENT | FAILED
```

Direct-message delivery requires an application receipt from the destination agent. Global chat exposes server acceptance only in the baseline. Neither status means the user has read the message. Presence updates are full snapshots with increasing revisions for the current server instance.

### 5.6 File service

```text
async offer_file(recipient_id, local_file_handle) -> TransferId
async accept_file(transfer_id, destination_handle)
async reject_file(transfer_id, reason)
async cancel_transfer(transfer_id)
get_transfer(transfer_id) -> TransferSnapshot

FILE_OFFER    {transfer_id, filename, size_bytes, chunk_size, sha256}
FILE_ACCEPT   {transfer_id}
FILE_REJECT   {transfer_id, reason}
FILE_CHUNK    {transfer_id, chunk_index, offset, data}
FILE_FINISH   {transfer_id}
FILE_COMPLETE {transfer_id, verified_sha256}
FILE_CANCEL   {transfer_id, reason}
```

The offer is addressed to a recipient in its routing envelope. The server stores the authorized sender/recipient association for the transfer and validates subsequent messages against it. Clients cannot redirect an existing transfer by changing a payload field.

```text
TransferSnapshot = {
    transfer_id,
    direction: SEND | RECEIVE,
    state: OFFERED | ACCEPTED | TRANSFERRING | VERIFYING
         | COMPLETED | FAILED | CANCELLED | REJECTED,
    total_bytes,
    transferred_bytes,
    error?
}
```

Sender progress counts unique file bytes acknowledged by the server; receiver progress counts unique bytes written locally. The UI labels these stages accordingly. `FILE_FINISH` is not completion. Only successful recipient verification followed by `FILE_COMPLETE` permits sender state `COMPLETED`.

Validate offsets, chunk lengths, total size, and transfer identity. Do not trust filenames as paths. Zero-byte files complete through metadata, finish, and checksum verification without requiring a data chunk. Terminal state transitions are idempotent; a completed transfer is not retroactively cancelled. Late or duplicate control messages must have defined behavior.

### 5.7 Browser UI ↔ local HTTP bridge

| Method | Endpoint | Contract |
|---|---|---|
| POST | `/api/login` | `{server_host, server_tcp_port, username, password}`; returns user/session state, never UDP keys |
| POST | `/api/logout` | Ends the active session and cancels pending work |
| GET | `/api/state` | Consistent snapshot plus event cursor |
| POST | `/api/messages` | `{request_id, target, text}`; returns HTTP 202 and `message_id` |
| POST | `/api/uploads` | Raw file bytes with declared size; returns `upload_id` after local staging |
| POST | `/api/transfers` | `{request_id, upload_id, recipient_id, filename}`; returns HTTP 202 and `transfer_id` |
| POST | `/api/transfers/{id}/accept` | Accepts into the configured safe download directory |
| POST | `/api/transfers/{id}/reject` | Rejects an incoming offer |
| POST | `/api/transfers/{id}/cancel` | Cancels an active transfer |
| GET | `/api/events?after={cursor}` | Bounded long poll; returns events and next cursor |
| GET | `/api/transfers/{id}/download` | Serves only a completed, verified received file |

All JSON responses use `{data: ...}` or `{error: {code, message, retryable}}`. Suggested status mapping: 400 invalid input, 401 unauthenticated, 404 unknown ID, 409 invalid state, 413 size limit, 429 backpressure, 500 unexpected local error. Remote transfer failures after HTTP 202 arrive as events.

Members 3 and 5 freeze exact request/response examples, upload headers, token bootstrap, and HTTP framing behavior in `docs/local-http-api.md` before integration. Avoid implementing a general-purpose HTTP server; support a small, bounded documented subset with strict parsing.

```text
Event = {cursor, type, timestamp, data}

type = CONNECTION_CHANGED | PRESENCE_CHANGED
     | MESSAGE_RECEIVED | MESSAGE_STATUS_CHANGED
     | FILE_OFFERED | TRANSFER_PROGRESS | TRANSFER_STATE_CHANGED | ERROR

connection_state = DISCONNECTED | CONNECTING | AUTHENTICATING
                 | ONLINE | RECONNECTING | FAILED
```

`GET /api/state` includes connection state, current user, online users, bounded recent chat/status state, active/recent transfers, and a cursor captured consistently with that snapshot. The UI applies only events after that cursor. An expired or invalid cursor returns `RESYNC_REQUIRED`; the UI refetches the snapshot. Events are retained in a bounded buffer, not indefinitely.

Repeated command requests with the same `request_id` return the original operation ID within the documented bounded deduplication window. Member 3 defines that window; the UI must not assume unlimited exactly-once behavior across reconnects or process restarts.

## 6. Configuration baseline

Member 2 owns names/defaults; module owners supply limits relevant to their module. These are initial project defaults, not assignment requirements or performance guarantees.

| Setting | Initial value / rule |
|---|---|
| `server_tcp_port` | 9000 |
| `server_udp_port` | 9001 |
| `client_http_host` | `127.0.0.1` only |
| `client_http_port` | 8080, configurable per local client |
| Client UDP source port | OS-assigned ephemeral port |
| `max_udp_payload_bytes` | 1200 including custom header and authentication tag |
| File chunk size | Derived from the datagram limit minus all protocol/crypto/file overhead |
| `heartbeat_interval_ms` | 2000 |
| `session_timeout_ms` | 10000 |
| `http_long_poll_timeout_ms` | 25000 |
| Timeouts for ACK/retransmission | RTT-based rules owned by Member 1; not heartbeat timeout |
| Storage roots | Per-client directories under `runtime/` |

Before the first integration, specify numeric limits for messages, filenames, file size, active transfers, send/reassembly/relay queues, upload staging, event retention, and command deduplication. No module may default to unbounded resource use.


## 7. System acceptance checklist

Member 5 records configurations, seeds, elapsed times, and outcomes. Other members fix their own module failures.

- [ ] TCP handshake and invalid/valid login behave as documented.
- [ ] Twenty clients remain usable during simultaneous messaging and disconnects.
- [ ] Direct chat reaches only its recipient; global chat reaches all currently online intended recipients.
- [ ] A lost segment does not generate duplicate application delivery.
- [ ] Loss in a file stream does not hold up an independent chat stream.
- [ ] File tests cover 0 bytes, chunk boundaries, binary data, and a generated large file (for example, 100 MiB).
- [ ] Sender/receiver SHA-256 hashes match after transfer under packet loss and reordering.
- [ ] Slow recipients cannot cause unbounded server/client memory or temporary storage growth.
- [ ] Rejected, cancelled, interrupted, and corrupted transfers never appear completed.
- [ ] Ciphertext tampering and replayed packets are rejected without crashing valid sessions.
- [ ] UI resynchronizes after an expired event cursor and shows defined status after reconnect.
- [ ] Fresh installation and startup succeed using only the documented steps.

Initial impairment scenario: configurable 5% random loss, 50 ms one-way added delay, and limited duplication/reordering, applied in both directions using a recorded seed. Test each fault separately before combining them. This is a team test target, not a claimed assignment threshold. A quantitative latency/throughput benchmark is optional. Required evidence is functional: independent chat continues while a file stream recovers, with honest limits on what the local scenario demonstrates. Do not claim that all head-of-line blocking has been eliminated or that local simulation validates production congestion behavior.

## 8. Report and submission ownership

| Deliverable | Lead | Contributions |
|---|---|---|
| Architecture and session diagrams | Member 2 | All members review boundaries |
| Protocol specification and chat sequence | Member 1 | Member 3 supplies security exchange |
| Security and Client Agent explanation | Member 3 | Member 2 reviews server integration |
| File sequence and congestion explanation | Member 4 | Member 1 reviews transport interaction |
| Testing results, UI screenshots, demo guide | Member 5 | Every member supplies module evidence |
| Challenges and solutions reflection | Member 2 compiles | Each member contributes one concrete issue and resolution |
| README and final package | Member 2 | All members verify on a clean setup |

Keep the report concise and complete: prioritize exact protocol definitions, concurrency/state ownership, key trade-offs, and the team-designed demo. Put detailed setup instructions in README and avoid pasting source code or long logs into the report. No multi-machine congestion benchmark section is required.

The final submission includes source code, dependency/version information, exact setup/run instructions, and the PDF technical report required by the assignment. Do not mark a feature complete based only on a standalone mock: it must pass its module tests and its integrated user flow.
