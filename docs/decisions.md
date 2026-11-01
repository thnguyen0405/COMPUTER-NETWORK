# Decision register

Date: 2026-10-28. Coordinator: Member 2.

**Accepted** means the team has adopted a baseline. **Open** means an implementation detail or instructor clarification remains. Do not confuse the two.

| ID | Decision | Status and rationale |
|---|---|---|
| D01 | Java 21 for server, agent, shared code and Java tools | Accepted. One typed backend language reduces integration differences. No preview features. |
| D02 | Maven single-module project; `org.streamcollab` package root | Accepted scaffold convention. Standard `src/main` and `src/test` layouts; no multiple build systems. |
| D03 | JDK sockets plus virtual threads and bounded queues | Accepted. One UDP receiver per socket, one logical state owner per peer. Avoid a task per packet and unbounded pending tasks. |
| D04 | Central server relays chat and files | Accepted. Matches the routing milestone and simplifies the first version. No direct P2P data path. |
| D05 | TCP/TLS control; custom reliable UDP data | Accepted team interpretation. Instructor clarification remains open due to inconsistent TCP/UDP statements in the PDF. |
| D06 | Browser UI + localhost Java HTTP agent | Accepted team interpretation. Implement a restricted HTTP subset over JDK sockets, without frameworks/WebSockets. Instructor confirmation remains open. |
| D07 | CUBIC with pacing | Accepted target. Exact required depth needs instructor clarification. Flow control is separate; a simple window/rate limiter is only a prototype. |
| D08 | JSSE/JCA/JCE; AES-GCM for UDP | Accepted. Standard crypto, directional session keys, authenticated headers, unique nonce per send, replay protection. Server can decrypt content. |
| D09 | Pre-created accounts, salted password hashes | Accepted. No registration UI. Hash parameters/storage schema remain open for Members 2/3. |
| D10 | Development CA and explicit trust configuration | Accepted. Validate certificates and expected server identity; no trust-all fallback. |
| D11 | HTML/CSS/plain browser JavaScript | Accepted. No frontend framework required; communicate only with the local agent. |
| D12 | No automatic resume, persistent chat, multi-server, read receipts or group administration | Accepted baseline scope. Reconnect creates a fresh session and interrupted operations fail explicitly. |
| D13 | One shared protocol/security implementation | Accepted. Server and client import the same Java packages; no copied codecs or reliability code. |

## Open decisions and ownership

| Item | Owners | Must be resolved before |
|---|---|---|
| Instructor clarification on D05/D06/D07 and permitted socket/crypto APIs | Member 2 with group | Final compliance claim; adapt affected implementation if necessary |
| Exact JDK 21 distribution/patch and Maven 3.9 patch | Member 2 | Shared development environment setup |
| JSON serialization and test dependency versions | Members 1/2/5 | Codec implementation and executable tests |
| Exact wire header offsets, type IDs, segmentation and ACK encoding | Member 1 with Members 3/4 | Production packet encoding |
| Key installation/binding handshake, nonce layout and replay window | Members 2/3 with Member 1 | Encrypted UDP integration |
| Congestion feedback metadata, loss/RTT rules and CUBIC parameters | Members 1/4 | Congestion implementation |
| Account hash parameters and TLS certificate creation workflow | Members 2/3 | Authentication integration |
| All resource caps and timeout/dedup retention limits | Relevant owners, coordinated by Member 2 | Integration beyond mocks |
| HTTP token bootstrap, framing limits and upload quotas | Members 3/5 | Real HTTP bridge |

Default ports: TCP 9000, UDP 9001, local HTTP 8080. Initial UDP payload cap is 1200 bytes including application protocol header and crypto tag. Heartbeat interval is 2000 ms and expiry is 10000 ms. Local HTTP long polling waits at most 25000 ms. These are tunable team defaults, not assignment requirements.

## Change process

Propose the change, identify consumers, obtain review from affected owners, update contracts/examples/mocks together, and merge the implementation only when producer and consumer agree. Changing Java packages, JSON fields, error meanings, or delivery semantics is a contract change.

## Instructor guidance reported by the team — 2026-10-28

- Design the demo scenarios yourselves and explain what each scenario proves.
- Be ready to justify design choices and explain trade-offs.
- A concise, complete report is sufficient; prioritize the protocol and concurrency design.
- Congestion testing is not required given the available machine count. The team interprets this as removing congestion benchmarking from required validation, while retaining inexpensive correctness checks and the existing design target. The required implementation depth is still open; no exemption from implementation was explicitly reported.

These points are reported classroom guidance, not independent confirmation of the other open interpretations in this register.

## Design rationale and defense prompts

| Choice | Why it fits this project | Trade-off / alternative to explain |
|---|---|---|
| Java 21 and virtual threads | Shared typed contracts; readable blocking-I/O tasks | Threads still need admission limits and safe state ownership; NIO offers explicit event-loop control with more complex code. |
| Central relay server | Simple authentication, routing, presence and repeatable demo | Server bandwidth/failure bottleneck; P2P reduces relay traffic but complicates discovery, connectivity and trust. |
| TCP/TLS setup plus custom UDP data | Secure setup and explicit exercise of application reliability | Two channels and binding logic; using TCP everywhere is simpler but does not satisfy the team's UDP interpretation. |
| Independent ordered streams | File loss need not delay unrelated chat delivery | More state/queues and no total ordering across streams; a single ordered queue is simpler but couples unrelated traffic. |
| Selective retransmission | Retransmit missing segments rather than an entire window/file | ACK ranges, timers and receive buffering are more complex than stop-and-wait. |
| Bounded queues and one state owner per peer | Predictable memory and serialized protocol-state updates | Admission can wait/fail under pressure; unbounded queues hide overload and risk memory exhaustion. |
| Local Java agent and browser UI | Browser usability while Java owns raw sockets and secrets | Extra local process and HTTP boundary; a native/CLI UI would simplify the bridge but needs UI-requirement clarification. |
| TLS plus AES-GCM UDP | Standard cryptographic primitives with payload/header integrity | Certificate/key/nonce lifecycle complexity; the server still sees plaintext, so this is not end-to-end encryption. |
| Chunked files and final checksum | Bounded memory and verifiable complete content | More metadata, disk I/O and final verification delay; a transport ACK alone cannot prove a complete verified file. |
| CUBIC target with pacing | Addresses the assignment's stated congestion topic | More algorithm/state complexity; no realistic congestion performance claim can be made from a small local demo. |

Each member must explain how the selected trade-off appears in actual code and the demo, rather than merely memorizing this table. Keep statements aligned with what is implemented.
