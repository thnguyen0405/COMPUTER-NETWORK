# State machines

Owners: Members 1–4. These diagrams capture agreed application behavior; detailed transport sub-states follow the final wire specification.

## Client connection

```mermaid
stateDiagram-v2
    [*] --> DISCONNECTED
    DISCONNECTED --> CONNECTING: login requested
    CONNECTING --> AUTHENTICATING: verified TLS ready
    AUTHENTICATING --> ONLINE: credentials and UDP binding accepted
    CONNECTING --> FAILED: timeout or TLS error
    AUTHENTICATING --> FAILED: rejected or binding failed
    ONLINE --> RECONNECTING: connection/session lost
    RECONNECTING --> CONNECTING: bounded retry or user retry
    RECONNECTING --> FAILED: retry budget exhausted
    FAILED --> CONNECTING: user retry
    ONLINE --> DISCONNECTED: logout
    FAILED --> DISCONNECTED: reset
```

From any active state, logout/stop cancels work and closes resources. Going offline fails pending sends/transfers once. Reconnection uses a fresh session and key context; it does not silently retry old file transfers. Members 2/3 must specify retry budgets and how fresh authentication is obtained without persisting plaintext passwords.

## Chat status

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> ACCEPTED_BY_SERVER: application acceptance
    PENDING --> FAILED: rejection or timeout
    ACCEPTED_BY_SERVER --> DELIVERED_TO_RECIPIENT: direct recipient receipt
    ACCEPTED_BY_SERVER --> FAILED: direct delivery failure or deadline
```

`PENDING` is local presentation state, not a network receipt value. Global messages stop at server acceptance in the baseline. Direct-message completion is not proof of reading. Ignore duplicate receipts and prevent a late receipt from incorrectly regressing a terminal state; owners must define timeout-versus-late-receipt precedence before implementation.

## File transfer

```mermaid
stateDiagram-v2
    [*] --> OFFERED
    OFFERED --> ACCEPTED: recipient accepts
    OFFERED --> REJECTED: recipient rejects
    ACCEPTED --> TRANSFERRING: chunks begin
    TRANSFERRING --> VERIFYING: all bytes received and finish observed
    ACCEPTED --> VERIFYING: empty file and finish observed
    VERIFYING --> COMPLETED: checksum succeeds
    VERIFYING --> FAILED: size or checksum mismatch
    OFFERED --> CANCELLED: cancel
    ACCEPTED --> CANCELLED: cancel
    TRANSFERRING --> CANCELLED: cancel
    VERIFYING --> CANCELLED: cancel before completion
```

Every nonterminal state may transition to `FAILED` on session loss, I/O error or deadline. `COMPLETED`, `FAILED`, `REJECTED`, and `CANCELLED` are terminal for that transfer ID. Retrying creates a new transfer ID.

The diagram primarily describes the receiving side. The sender has a separate local snapshot and may only enter `COMPLETED` after authenticated `FILE_COMPLETE` from the authorized recipient via the server. A sender may remain at 100% byte progress while waiting for confirmation; it must not claim recipient verification has begun unless that information has actually been received.

Cancellation and duplicate control messages are idempotent. Handle cancel/completion races by serialized state transitions, and never delete an already finalized user download as a delayed cancellation side effect.
