# Technical report outline

This is a report plan, not a completed PDF or an implementation claim.

1. **System architecture — Member 2:** topology, Java concurrency, module boundaries and trust model.
2. **Protocol specification — Member 1:** exact finalized packet layout, types, ACK/retry/ordering, flow control and limits.
3. **Authentication/security — Member 3:** TLS trust, account verification, key setup, nonce/replay behavior and local HTTP boundary.
4. **Message/file sequences — Members 1 and 4:** reuse diagram sources, explain transport receipt versus final delivery.
5. **Congestion control — Member 4:** implemented mechanism, pacing, distinction from flow control, trade-offs and limitations. No multi-machine congestion benchmark is required.
6. **Testing — Member 5:** the team-designed demo scenarios, actual functional outcomes, selected screenshots and reproducible settings.
7. **Challenges and solutions — all members; Member 2 compiles:** approximately one page of concrete encountered problems and resolutions.
8. **Setup/demo and limitations — Member 2:** verified instructions, supported environment and remaining limitations.

Attach evidence only after implementation/testing. Keep source diagrams editable and export the final PDF near submission. The source assignment PDF in the project root remains unchanged.

## Editorial priorities from classroom guidance

Keep the report short enough to read easily and complete enough to explain the design. No fixed page limit was reported. Prioritize:

- Exact protocol structure, framing, message types and receipt/completion semantics.
- Concurrency: what runs concurrently, who owns mutable state, where queues are bounded, and how cancellation/shutdown works.
- Reasons for major decisions, realistic alternatives and trade-offs.
- A small set of demo scenarios showing expected versus observed behavior.
- Honest limitations, including the absence of realistic congestion benchmarks.

Use compact tables and diagrams instead of repeating code or full logs. Reuse setup details from README by reference. Do not turn this outline into eight unnecessarily long chapters; combine related topics. Each member supplies a concise module explanation and evidence, and Member 2 removes duplication. See [demo plan](../demo-plan.md) and [decision rationale](../decisions.md).
