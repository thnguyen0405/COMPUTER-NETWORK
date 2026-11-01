# Test plan

Coordinator: Member 5. Each member implements module tests and fixes defects in their own code. This file is a plan; no tests have run and no performance results are claimed.

## Validation priority after classroom guidance

The instructor guidance reported on 2026-10-28 emphasizes a self-designed demo, protocol/concurrency explanation, justified trade-offs, and a concise report. Multi-machine congestion testing/benchmarking is not required. Keep correctness checks for accounting, bounded queues and state transitions; do not treat these as proof of CUBIC performance or fairness.

The concurrency target can be exercised by multiple local processes/sessions, not 20 physical machines. Local tests demonstrate functional concurrency only; they do not establish realistic LAN/WAN capacity. See [demo plan](demo-plan.md).

## Locations and layers

- `src/test/java/org/streamcollab/unit`: isolated codec, transport, security, routing, HTTP, file and controller tests.
- `src/test/java/org/streamcollab/integration`: cooperating real modules and local sockets.
- `src/test/java/org/streamcollab/e2e`: full agent/server/user workflows and multi-client scenarios.
- `src/test/resources/fixtures`: small fixed payloads; generate large files into ignored runtime directories.
- `tools/network_simulator`: impairment configuration and runner documentation.
- `tools/multi_client_runner`: reproducible concurrent-client scenarios.

Member 2 and Member 5 select and pin a test framework before executable tests are added. Do not report `mvn test` as passing application tests when the test tree is empty.

## Acceptance matrix

| ID | Scenario | Pass condition | Responsible members |
|---|---|---|---|
| T01 | Valid/invalid credentials; certificate/identity failure | Defined result, no insecure TLS fallback, no leaked secrets | 2, 3 |
| T02 | Twenty concurrent clients (local sessions allowed) | Login, presence, chat and disconnects without server restart | 2, 5 |
| T03 | Direct/global routing | Intended recipients only; sender identity cannot be spoofed | 2, 3 |
| T04 | UDP loss/reorder/duplication | Correct ordering within stream; no duplicate application delivery | 1 |
| T05 | Lost file segment while chatting | File recovers; independent chat stream continues | 1, 4, 5 |
| T06 | Empty/binary/chunk-boundary/100 MiB generated file | Exact size and SHA-256; bounded buffers | 4 |
| T07 | Slow/offline recipient | Backpressure bounds relay memory; defined failure | 1, 2, 4 |
| T08 | Reject/cancel/session loss/checksum failure | No false completion; defined cleanup and terminal state | 3, 4 |
| T09 | Ciphertext/header tamper, replay, duplicate retries | Invalid packets rejected, no nonce reuse, valid session survives | 1, 3 |
| T10 | UI long polling, expired cursor, repeated commands | Correct snapshot resync and bounded deduplication | 3, 5 |
| T11 | Malformed packet/HTTP/upload path | Rejected without unbounded allocation or arbitrary file access | 1, 3, 4 |
| T12 | Clean-machine startup | README steps work with aligned Java/tool versions | All |
| T13 | Controller ACK/loss feedback and retransmission accounting | Deterministic expected state; no double decrement or bypass | 1, 4 |

## Reproducible impairment tests

Start with no impairment, then test each fault separately. Initial combined target: 5% random loss, 50 ms one-way added delay, plus bounded configurable duplication/reordering in both directions. Record the exact parameters and random seed. A proxy/injection hook must not modify production crypto checks or bypass session binding; simulator design must accommodate the authenticated endpoint mapping.

Required evidence: chat remains functional while an independent file stream recovers; all received file bytes match the expected checksum. Optional diagnostic timing can help explain a failure, but a latency/throughput/fairness benchmark is not a required acceptance gate. Do not invent performance values or claim complete elimination of within-stream head-of-line blocking.

## Evidence template

```text
Test ID:
Date / commit:
JDK / Maven / OS:
Client count / topology:
File sizes and expected hashes:
Impairment parameters / seed:
Expected result:
Actual result:
Latency / throughput / memory observations:
Pass / fail / not run:
Logs or screenshots (no secrets):
Owner and follow-up:
```

Each merged feature requires relevant tests plus one integrated flow. Final regression covers both localhost and a LAN setup when available. Mark unavailable environments honestly rather than assuming cross-platform behavior.
