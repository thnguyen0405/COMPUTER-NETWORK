# Module interfaces

Owners: Member 1 maintains shared types; every affected producer/consumer reviews changes.

This document records the Java mapping and agreed behavior. Signatures below are contract notation, not generated Java code. Create actual interfaces in the shared packages before splitting implementation work.

## Java mapping

| Contract notation | Java representation |
|---|---|
| `async operation(...) -> T` | `CompletionStage<T> operation(...)` |
| `async operation(...)` with no result | `CompletionStage<Void>` |
| Immutable object | `record` with validated fields and safe byte-buffer ownership |
| Event alternatives | `sealed interface` and record implementations |
| State/status | `enum` |
| Binary payload | `byte[]` or read-only `ByteBuffer`, ownership documented |
| Missing session | `Optional<Session>` |
| Deadline | Monotonic deadline internally; a documented adapter at the API boundary |

For example, the transport's Java name is `send`, and `open_stream` maps to `openStream`. `receive` maps to `CompletionStage<TransportEvent>`; only the agent/server dispatcher consumes transport events, then routes them to chat, files or session services. Multiple modules must not race to consume the same queue.

The dispatcher and file service run on both sending and receiving paths as appropriate. The server relays file content but does not call the client's filesystem reassembly service.

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

## Interface freeze checklist

Before writing concrete implementations, commit DTOs/interfaces and fake implementations for transport, file service, congestion control and HTTP responses. Resolve exact feedback metadata, cancellation behavior, buffer ownership and bounded-queue admission rules with the affected owners. The contract's one-result rule applies to both success and exceptional completion.
