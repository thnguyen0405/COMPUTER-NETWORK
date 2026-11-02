# Member 3 — Stage 5: Local HTTP Bridge (Java 21)

## What this stage adds

An actual loopback-only HTTP/1.1 socket listener and a JSON adapter exposing bounded asynchronous client state. All sources use JDK 21, no new third-party dependency or `pom.xml` changes. This ZIP is an **incremental patch**: install Stages 2, 3 and 4 first.

**Not yet an end-to-end application:** Real server authentication, UDP transport and receipts are still dependency interfaces from other members. Member 4's file service does not exist in this patch. Do not run the fake test gateway in production or claim that its ONLINE state is authenticated. Member 5's UI directory is empty in the original repository; static assets are only served after Member 5 supplies packaged, reviewed files.

## Files

- `LoopbackHttpBridge`: local HTTP/1.1 parser + server, virtual workers, 16 concurrent slots, loopback socket, strict Host/Origin/token, static classpath assets under `ui/`, JSON error responses. Each request closes its connection.
- `MiniJson`: bounded JSON parser/serializer with duplicate-property rejection, Unicode validation and strict schemas at the API adapter.
- `ApiProblem`: stable code/status for safe HTTP errors (never dump raw exceptions or credentials).
- `AgentEventJournal`: 256-entry recommended bounded unified event log; per-process string cursor, detects old/foreign cursors, supports cursor invalidation.
- `AgentBridgeApi`: maps `/api/login`, `/api/logout`, `/api/messages`, `/api/state`, `/api/events` to Stage 2/4 adapters; imports chat-local events to a single ordered HTTP cursor; exposes connection callback and trusted chat delivery/receipt hooks.
- `LoopbackHttpBridgeSmokeTest`: real socket HTTP tests with FAKE gateway and ChatTransport, no actual team server.

## API status

| Method | Path | Stage-5 behavior |
|---|---|---|
| POST | `/api/login` | Takes `server_host`, numeric `server_tcp_port`, `username`, `password`; returns 202 accepted + connection state. Real authentication runs through injected `LoginGateway` and publishes connection events asynchronously. |
| POST | `/api/logout` | Takes `{}`; closes session through gateway, clears chat pending operations, returns 200. |
| GET | `/api/state` | Consistent chat snapshot and unified cursor, `connection_state`, `current_user_id`, recent incoming/outbound chat. `transfers` currently an empty placeholder, NOT an implemented file view. |
| POST | `/api/messages` | Accepts `{request_id,target,text}`, returns 202 `{message_id}`; uses Stage-4 ChatService dedup/backpressure. Target: `{"kind":"GLOBAL"}` or `{"kind":"DIRECT","user_id":"..."}`. |
| GET | `/api/events?after={cursor}` | Up to 128 ordered events and `next_cursor`; bounded poll of up to one second. Unknown/expired cursor returns HTTP 409 `RESYNC_REQUIRED`. |
| POST | `/api/uploads`, `/api/transfers`, `/api/transfers/{id}/accept`, `/reject`, `/cancel` | HTTP 501 `FILE_SERVICE_NOT_READY` (correct endpoints recognized but NOT wired until Member 4 file service). |
| GET | `/api/transfers/{id}/download` | HTTP 501 `FILE_SERVICE_NOT_READY` until safe verified download adapter exists. |
| GET | `/` or eligible relative path | Only serves an actual classpath file under `ui/`. Original project has no `ui/index.html`, so `/` returns 404 until Member 5 packages UI. |

Sample JSON for `/api/state` (fields may be empty):

```json
{"data":{"connection_state":"DISCONNECTED","current_user_id":null,"recent_received":[],"recent_outbound":[],"transfers":[],"cursor":"<instance-uuid>:0"}}
```

Sample `/api/events`:

```json
{"data":{"events":[{"cursor":"<instance-uuid>:1","type":"MESSAGE_STATUS_CHANGED","timestamp":"2026-11-02T10:00:00Z","data":{"message_id":"msg-1","status":"PENDING"}}],"next_cursor":"<instance-uuid>:1"}}
```

Errors:

```json
{"error":{"code":"UNAUTHENTICATED","message":"unauthenticated","retryable":false}}
```

Statuses include 400 malformed/schema, 401 bad token or expired chat session, 403 incorrect Host/Origin, 404 unknown URL, 409 resync/invalid state, 411 missing length, 413 oversize, 415 wrong JSON media type, 429 backpressure, 431 too many headers, 501 file service missing, 503 interrupted poll, 500 unexpected local error.

## Threat model and limits

- Bind explicitly to `127.0.0.1`; never `0.0.0.0` or LAN. `ServerSocket` OS allocates a port if caller passes 0.
- Per-instance random 256-bit local token supplied only through `tokenForTrustedLauncher()` or `launchUri()` to trusted local launch code. `launchUri()` stores it **in fragment**, which the browser does not transmit as HTTP request target. Member 5 must immediately extract it and call `history.replaceState(...)` to remove it from browser history; never log, copy to URLs/querystrings, send to remote servers, or store in cookies/localStorage. The original UI is not provided by this stage. The token is never available from an API endpoint. Any untrusted extension/page with access to the token defeats this boundary.
- For API requests enforce `X-Agent-Token`, exact `Host: 127.0.0.1:<port>` and any present `Origin: http://127.0.0.1:<port>`. No permissive CORS header and no cookie-based authentication. GET requests may lack Origin. This mitigates CSRF and DNS rebinding in the expected browser deployment, but does not prevent malicious local processes from connecting to loopback.
- HTTP/1.1 only, strict CRLF, no chunked `Transfer-Encoding`, `Expect`, compression, upgrades, duplicate headers, keep-alive, percent-encoded paths or multipart handling. POST requires `Content-Length`. Request headers limited to 8 KiB, each line 2 KiB, body 16 KiB (JSON commands only), 16 concurrent connections, 3-second socket timeout, one-second event poll, response always `Connection: close`. Raw file uploads will need a separate, carefully bounded handler before activation.
- JSON nesting max 10, bounded field sizes, rejects duplicate object keys including null-valued keys, refuses malformed UTF-8. The JSON parser is intentionally tiny and not a general-purpose JSON library.
- Allowed classpath assets: `.html`, `.js`, `.css`, `.png`, `.ico`, `.woff2`. Path traversal, percent encoding and directories rejected. CSP restricts to same-origin scripts/styles/connect; no inline JS. Member 5 must avoid XSS because a trusted UI can read the token.
- Chat Service keeps its own bounded `request_id` dedup, not persistent; no guarantee across restarts. Raw passwords necessarily exist as short-lived Java Strings during JSON parsing; gateway receives a `LoginCredentials` copy and MUST consume/erase it. Do not log request bodies.
- `AgentBridgeApi.onChatReceipt` and `onChatDelivery` must ONLY be invoked by the authenticated network dispatcher. HTTP/browser clients cannot submit forged receipts or messages-from-server.
- Unified journal import uses chat-local cursor. If chat history exceeds its retention before reconciliation, Stage 5 rotates the unified cursor; browsers receive `RESYNC_REQUIRED` and reload `/api/state` instead of silently missing data. This prototype syncs chat during API operations and callback hooks; integrate trusted callbacks promptly to minimize event latency.

## Required integration with Member 1/2/4/5

1. **Member 2:** Implement `AgentBridgeApi.LoginGateway` with a real Stage-2 ClientConnectionSupervisor. Stage 2's supervisor fixes `ServerEndpoint` at construction: the gateway must manage supervisor lifecycle per selected server; it cannot blindly reuse an instance for arbitrary `server_host`. Pass through verified TLS, authenticated login and bound UDP; only then may supervisor publish ONLINE. Wire its `ConnectionEventSink` to `api.onConnectionTransition` and call `api.onSessionLost` when the authenticated connection is lost.
2. **Member 1:** Implement the real Stage-4 `ChatTransport`. Route authenticated `CHAT_DELIVER` to `api.onChatDelivery`, and verified application receipts to `api.onChatReceipt`. The current smoke test uses a FAKE transport and fake state only.
3. **Member 4:** Build a bounded staging directory, file hash verification, safe file IDs and download paths; then implement the 501 endpoints and real transfers snapshot. Do not accept arbitrary absolute paths or trust browser-supplied names; add upload quota, upload Content-Length handling, disk capacity checks, and cleaned-up staged bytes.
4. **Member 5:** Freeze JSON field names with Member 3; package audited `index.html`, JS/CSS in `src/main/resources/ui/` (classpath `ui/`). Local UI obtains token from fragment, immediately clears it from address bar; calls fetch with `X-Agent-Token`, `Content-Type: application/json` for POST; uses `/api/state` then `/api/events?after=cursor`; on 409 refetches state. Do not store token in localStorage or include keys in console output.
5. Integrate real session loss: call `chat.onSessionEnded()` via `api.onSessionLost`, close/discard transport, deny sends until authenticated session is rebound. Check error cases, concurrent sends and UI response behavior on 202.

## Installation / tests

Unzip into repo root after Stage 4:

```sh
unzip -o "$HOME/Downloads/member3-http-stage5.zip" -d .
bash scripts/test-member3-stage5.sh
bash scripts/test-member3-stage4.sh
bash scripts/test-member3-stage3.sh
bash scripts/test-member3-stage2.sh
```

Tests cover actual loopback HTTP requests and raw malformed HTTP framing, but use FAKE gateway and chat transport. They do NOT validate a real team server, real UDP, real file transfer, browser UI, or malicious local process compromise. The test is source-only javac, no Maven needed. Use JDK 21.
