# Member 3 — Stage 4 mock transport smoke test

- Java runtime: OpenJDK 21.0.12.1, Linux container.
- Test runner: `bash scripts/test-member3-stage4.sh`, no external dependencies.
- Result: 638 assertions passed in the standalone Java smoke-test class.
- Coverage: request ID idempotency; same-ID conflicting payload; global/direct application status transitions; transport ACK separation; malformed input, size cap and Unicode; transport future failures; session loss and callback races; bounded in-flight/outbox/incoming/event limits; cursor expiry/restart; 12 concurrent virtual-thread submissions sharing one `request_id`.
- **Not tested:** production login, server receipt authenticity, Member 1's reliable UDP transport, real network loss/retry, browser HTTP security or end-to-end chat delivery.

All assertions include 560 repeated bounded incoming-message checks. The count is assertions, not 638 independent scenarios.
