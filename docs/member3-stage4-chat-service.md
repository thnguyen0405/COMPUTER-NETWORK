# Member 3 — Stage 4: Chat service (mock transport integration)

## What is finished

The `org.streamcollab.client.chat` package provides a client-side chat service with:

- `ChatService.send(requestId, target, text)` that returns a `messageId` immediately for the future `POST /api/messages` HTTP 202 response.
- Duplicate `requestId` + identical payload -> the same `messageId`, with no second transport send, **while the request is retained in memory**. Reusing a request ID with different content is rejected.
- Explicit status transitions and events: `PENDING` -> `ACCEPTED_BY_SERVER` -> `DELIVERED_TO_RECIPIENT` (direct chat only), or `FAILED`.
- `ChatTransport` adapter that is async and does not implement UDP retransmissions or wire encodings. A transport ACK does **not** imply a server application receipt.
- Validated incoming `CHAT_DELIVER` events and bounded best-effort duplicate suppression.
- Bounded snapshots and a chat-local event journal with opaque cursor + `RESYNC_REQUIRED` on expiry or restart.
- `onSessionEnded()` that fails incomplete outbound requests once, resets session dedup state and never retries them automatically after reconnect.
- No additional Maven dependencies and no modifications to Member 1/2/4/5 packages.

## Important boundaries

**The shared protocol/transport is Member 1's responsibility.** `ChatTransport` is a *temporary integration seam*, not a second reliable UDP implementation. The transport owner must select one global-chat stream and separate direct-conversation streams, implement `CHAT_SEND` and `CHAT_DELIVER` serialization per the final frozen schema, and provide a single validated event dispatcher. Chat service must not independently consume Member 1's transport receive queue.

**Receipts are application-level messages.** `transport.sendChat(...)` completes on immediate peer transport ACK. That does not promote chat status. Instead, only the validated authenticated server's `CHAT_RECEIPT` should invoke `onReceipt(...)`:

- `ACCEPTED_BY_SERVER`: terminal for baseline global chat, nonterminal for direct chat.
- `DELIVERED_TO_RECIPIENT`: terminal for direct chat, must match the target user ID when one is supplied.
- `FAILED`: terminal; optional application error code defaults to `REMOTE_FAILED`.

**Incoming chat:** after Member 1's dispatcher decodes and authenticates `CHAT_DELIVER`, call `onDelivery(ChatDelivery)`. `true` means first admission; `false` means duplicate seen in the bounded cache. The coordinating agent/server dispatcher must return the destination's application receipt to the server **only after** the event has actually been admitted. The dedup cache holds 512 IDs per session; long-lived exactly-once admission is not guaranteed after restart or cache eviction. Member 1's transport still owns reliable ordering and duplicate suppression.

**No actual remote server yet:** the Stage 4 test uses fake `ChatTransport` and synthetic receipts. Login and authenticated UDP binding from Members 1/2 are still only mocked at this point. No actual browser/HTTP endpoint or multi-client test is included in Stage 4.

## Wiring example (pseudocode)

```java
ChatTransport adapter = command -> member1Transport.sendChat(command);
ChatService chat = new ChatService(adapter,
    () -> supervisor.state() == ConnectionState.ONLINE);

// HTTP POST /api/messages, after validating agent-local token and JSON:
String messageId = chat.send(requestId, new ChatTarget.Direct("user-b"), "Hello");
// Return HTTP 202 with {"data":{"message_id": messageId}}

// In the authenticated UDP application-event dispatcher:
chat.onReceipt(messageId, ChatMessageStatus.ACCEPTED_BY_SERVER, null, null);
chat.onDelivery(deliveredMessage); // true -> enqueue destination application receipt

// On login/session disconnect/logout:
chat.onSessionEnded();
```

Do not wire the pseudocode directly without adapting Member 1's actual transport API, codecs, callback threading and auth checks. `ChatTransport.sendChat()` must **return promptly** (no blocking on socket sends, ACKs or retries) or the UI HTTP handler could block. The implementation limits pending requests but does not enforce a timeout for a transport adapter that hangs forever. Owner must define a deadline and timeout policy with Member 1.

## Limits and local API

| Setting | Value in this stage |
|---|---|
| Maximum UTF-8 chat text | 4,096 bytes; nonempty, reject malformed Unicode |
| Maximum in-flight chat requests | 64 |
| Maximum remembered outgoing messages/request IDs | 256 |
| Maximum recent incoming message snapshots | 100 |
| Incoming message ID dedup cache | 512 IDs per session |
| Maximum chat-local journal events | 512 |
| Max events returned per page | 128 |

The memory limits are provisional **member-specific defaults**, not finalized global protocol/HTTP quotas. They must be reviewed with Member 1 (transport), Member 2 (routing) and Member 5 (UI). Transport/send backpressure and HTTP quota need end-to-end integration tests.

### Events and cursor details

- Each event has a chat-local cursor, timestamp and either `MESSAGE_RECEIVED` or `MESSAGE_STATUS_CHANGED` payload.
- `snapshot()` reads current chat data and its cursor atomically under the chat state lock.
- `eventsAfter(cursor, limit)` returns ordered chat events and a next cursor. An old/invalid/different-instance cursor throws `RESYNC_REQUIRED`; the caller should reload a fresh snapshot.
- These cursors are **NOT** directly `/api/events` cursors. Stage 5's HTTP bridge must merge connection/file/chat events into one bounded journal and provide a single ordered cursor for its unified API. Do not expose the chat-only cursor to the browser as the unified cursor.
- Only call `onDelivery` and `onReceipt` for validated server-origin application messages. Never accept `sender_id`, status or receipts from the browser as authority.

### Limitations for integration

- ID dedup is intentionally bounded, volatile and session-scoped. It is not a persistent guarantee, and is not exactly-once across restarts or expiration.
- The chat module intentionally does not store sender credentials, session keys, file paths, or UDP packets.
- No heartbeat, reconnect scheduler, actual session binding, inbound-dispatcher wiring, delivery receipt outbound adapter, or actual server routing is provided here.
- The orchestrator must stop/close real transport resources on logout, and call `onSessionEnded` to invalidate queued chat operations. It must prevent an already scheduled send from escaping after logout (by invalidating the session in the real transport adapter).
- The final integrated client should route server receipts to chat only after verifying the peer/session identity through the actual authenticated transport.

## Test and Git guidance

```sh
bash scripts/test-member3-stage4.sh
bash scripts/test-member3-stage3.sh
bash scripts/test-member3-stage2.sh
```

The tests check idempotency, concurrent calls, UTF-8 limits, transport failures, status transitions, dedup, bounded state, and event cursor expiry. They do not test network reliability, server behavior or the browser UI.

Push Stage 4 to `feature/member3-connection` (or a new Member 3 feature branch), and request transport schema review before merging to `main`.
