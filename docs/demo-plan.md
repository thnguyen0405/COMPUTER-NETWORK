# Team-designed demo and defense plan

Owner: Member 5 coordinates; all five members prepare their explanations. Status: planned, not executed. Based on classroom guidance reported on 2026-10-28. Scenario order and timing are team proposals, not an instructor-imposed format.

## Goal

Demonstrate a working collaboration flow and explain the protocol and concurrency mechanisms that make it work. Show a small reproducible functional scenario, not a congestion performance benchmark. Keep evidence honest and connect visible behavior to the actual implementation.

## Setup and reset checklist

- One Java server and three client agents named A, B and C; one machine with separate ports/runtime directories is acceptable.
- Pre-created demo accounts and verified TLS trust; no passwords/keys visible in logs or screenshots.
- Small text/binary files plus a generated larger file with recorded size and SHA-256. Choose a size that makes progress visible on the available hardware.
- Separate downloads and local HTTP ports for each agent. Ensure prior sessions/transfers are stopped before restarting.
- Record commit, JDK/OS, configuration and impairment seed where applicable.
- Implement and verify actual start/reset commands in README; no working commands are claimed yet.
- Save a known-good fixture set and choose a repeatable fault injection point with Members 1/3. Do not disable cryptographic checks for fault injection.

## Scenario sequence

| Scenario | Actions | Expected visible evidence | Explain / owner |
|---|---|---|---|
| 1. Session and presence | Start server; log in A/B/C; try one invalid login | Online list updates; invalid login rejected | TLS/authentication, UDP binding and session state; Members 2/3 |
| 2. Global and direct chat | A sends global chat, then a direct message to B | B/C get global chat; C does not receive the private message; distinct acceptance/delivery status | Routing, sender identity and application receipt versus transport ACK; Members 1/2/3 |
| 3. File with concurrent chat | A offers a file to B; B accepts; C/A continue chatting | Transfer progress, responsive chat, final size/hash match and completion only after verification | Separate streams, chunking, concurrency and bounded buffers; Members 1/4 |
| 4. Controlled recovery | Use a recorded fault scenario that drops a file data segment or introduces bounded reordering | File recovers; no duplicate application delivery; unrelated chat continues | Sequence numbers, selective ACK/retry and limits of the HoL claim; Members 1/5 |
| 5. Disconnect/cancellation | Disconnect B during an active transfer, then reconnect; optionally cancel a fresh transfer | Offline state, defined failed/cancelled transfer, no false completion, fresh session | Heartbeat/timeout, resource cleanup and no automatic resume; Members 2/3/4 |

Scenario 4 is a functional protocol demonstration, not a congestion test. Inspect sanitized logs showing IDs/sequence transitions if UI alone cannot prove the mechanism. If fault injection is not ready, do not substitute an unverified claim: record the limitation and demonstrate only working behavior.

## Concurrency evidence

A/B/C are enough to explain simultaneous operations live. Separately run a local multi-client check toward the assignment's 20-client target and record actual outcomes; no need to present 20 browser windows or provision 20 machines. Explain the UDP receive loop, per-peer state owner, bounded queues, virtual-thread I/O tasks and file-work separation with a diagram. Do not infer network-scale performance from local process counts.

## Questions each member must be able to answer

1. What is the input/output contract of your module, and who owns the state?
2. Why did you choose this design rather than one realistic alternative?
3. What cost or limitation does that choice introduce?
4. What happens on duplicate input, timeout, cancellation or malformed data?
5. Which part of the demo proves your claim, and what does it not prove?

Targeted questions:

- Member 1: Why separate packet numbers from logical segment sequence? What does ACK guarantee? Where can ordered delivery still block?
- Member 2: Why relay through a server? How do you avoid races in sessions/routing? What happens when a recipient is slow?
- Member 3: Why Java virtual threads and a local agent? What is bounded despite lightweight threads? Who can read encrypted content?
- Member 4: Why chunks and a final hash? Why is 100% sent not completion? How do flow and congestion control differ?
- Member 5: Why these scenarios? How are they reset/reproduced? What is simulated, and what is measured directly?

## Rehearsal and evidence

Run the sequence from a clean reset. Assign the operator and speaker for each step, record observed results, and trim repetitive actions to fit the actual presentation slot once known. Keep a concise expected/actual checklist and a few screenshots/log excerpts. A rehearsal recording may be a backup if allowed, but must not be presented as a live run.

No multi-machine congestion benchmark is required. Keep focused controller/accounting correctness checks in the test plan and explain the remaining performance-validation limitation in the report. This does not assert that the instructor waived congestion implementation.
