# Local HTTP API

Owners: Members 3 and 5. The browser talks only to the agent on loopback. Application state is asynchronous even though local HTTP requests use request/response semantics.

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

## Examples

Send a direct message:

```http
POST /api/messages HTTP/1.1
Content-Type: application/json
X-Agent-Token: <local-token>
```

```json
{"request_id":"req-001","target":{"kind":"DIRECT","user_id":"user-b"},"text":"Hello"}
```

HTTP 202:

```json
{"data":{"message_id":"msg-001"}}
```

An application status event inside the long-poll event list:

```json
{"cursor":"12","type":"MESSAGE_STATUS_CHANGED","timestamp":"2026-10-28T02:00:00Z","data":{"message_id":"msg-001","recipient_id":"user-b","status":"DELIVERED_TO_RECIPIENT"}}
```

Long poll response shape:

```json
{"data":{"events":[],"next_cursor":"12"}}
```

A normal timeout returns an empty event list with the current cursor, not an error. Keep cursor representation opaque/string-valued; an agent restart invalidates old cursors. The event buffer and snapshot must share one ordered state boundary so UI recovery cannot lose events between snapshot and subscription.

Error response:

```json
{"error":{"code":"PEER_OFFLINE","message":"The recipient is offline.","retryable":true}}
```

A successful upload returns `{"data":{"upload_id":"upload-001"}}` after local staging. The agent computes/validates file metadata through the file service before sending an offer. The browser never supplies a trusted local filesystem path. Raw upload progress and remote transfer progress are separate phases.

## HTTP implementation boundary

Serve packaged static assets and only documented endpoints. The agent binds `127.0.0.1`; each instance uses its own port, token and runtime directory. API authentication is separate from remote account authentication. Browser state and logs must not contain UDP session keys.

Use a strict documented HTTP/1.1 subset over JDK sockets. Before implementation, Members 3/5 must specify Content-Length handling, unsupported transfer encoding behavior, header/body limits, upload timeout/quota, connection cleanup, token bootstrap and download-token behavior. Reject ambiguous framing rather than trying to repair it. Restrict static paths to packaged assets and validate Origin/Host appropriately; do not enable permissive cross-origin access.

## Remaining schema work

Define full JSON examples for login, state snapshot, file offer/progress, cancellation, logout and errors; specify whether errors are retryable and their HTTP status. Define bounds for event retention, command deduplication and pending long polls. These are implementation gates, not alternative APIs individual members may invent.
