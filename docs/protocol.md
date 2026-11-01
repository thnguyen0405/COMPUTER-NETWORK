# Application protocol baseline

Owner: Member 1; security review by Member 3; congestion review by Member 4.

**Status:** message semantics are agreed. This is not yet a complete byte-level interoperable specification. Do not independently invent header offsets, ACK encodings or handshake fields; resolve the open items below before implementing encoders.

## Transport channels

- TCP with verified TLS: authentication, session setup and key material establishment.
- UDP: authenticated binding, heartbeat/presence, chat, file transfer, ACK and transport control.
- Local HTTP is specified separately in [local-http-api.md](local-http-api.md).

Application control messages use UTF-8 JSON with `snake_case` fields. File chunks carry binary data, not Base64. Binary integer fields use network byte order. The exact TCP framing and binary file chunk envelope remain to be specified by Member 1.

## UDP packet fields

| Field | Meaning |
|---|---|
| `version` | Protocol version; reject unsupported versions |
| `type` | Packet/control kind |
| `session_id` | Key/session context; untrusted until authentication succeeds |
| `stream_id` | Independent logical ordering context |
| `packet_number` | New value for every outgoing datagram, including retry |
| `stream_sequence` | Logical segment order, stable on retransmission |
| `payload_length` | Validated length |
| `flags` | Documented flags only |
| Encrypted payload and authentication tag | AES-GCM authenticated payload; header is authenticated data |

Maximum initial UDP payload is 1200 bytes including all custom header, metadata and authentication tag. Chunk size must be calculated from the final layout, not hardcoded to 1200 bytes of file data.

ACK processing tracks transport receipt, not application delivery. Receiver admission and all incomplete reassembly state are bounded. Retry uses a new packet number while retaining logical segment identity, so both crypto nonce uniqueness and logical duplicate suppression hold.

## Application message families


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

## Ordering and receipts

Chat order is per stream; there is no total global order across all streams. `CHAT_RECEIPT: ACCEPTED_BY_SERVER` is an application decision by the server, distinct from UDP ACK. A direct recipient agent acknowledges delivery at the application layer. The server verifies that receipts come from the actual recipient before forwarding them to the sender. No read receipt is claimed.

The binding exchange and heartbeats must not depend on a blocked file stream. Presence snapshots carry increasing revisions scoped to a server instance; clients reset revision state on a new server/session context.

## Required additions before wire implementation

| Detail | Owner/reviewer |
|---|---|
| Header field widths/offsets, type/flag IDs, version negotiation | Member 1 / Member 3 |
| TCP message length framing and maximum size | Member 1 / Member 2 |
| Message segmentation, stream opening/closing and ID allocation | Member 1 / Member 4 |
| ACK ranges, ack-eliciting classification, retry timer, expiry rules | Member 1 / Member 4 |
| Receiver credit units, accounting and blocked-peer behavior | Member 1 / Member 2 |
| RTT/loss sample records and congestion accounting | Members 1/4 |
| Session-key installation, nonce bytes and replay window | Member 3 / Member 1 |
| UDP endpoint binding and endpoint change policy | Members 2/3 |
| Routing envelope and encoded binary file metadata | Members 1/2/4 |
| Numeric message/file/buffer/rate limits and counter exhaustion policy | All relevant owners |

Add valid/invalid packet examples and codec fixtures after these decisions. Reject inconsistent lengths, unknown mandatory fields/types and excessive allocations before dispatching to applications. Never trust unauthenticated input to advance ACK state.
