# Member 5 implementation readiness

Status: preparation only; no application tests have run. Baseline: initial repository scaffold.

## Work order

1. Review the HTTP contract with Member 3 using the checklist below.
2. Implement browser UI and adapter against approved examples and Member 3's mock.
3. Add focused UI tests and real agent/server E2E tests when their dependencies exist.
4. Implement fault injection with Members 1/3 and the multi-client runner with Member 2.
5. Execute the system test plan, collect evidence, and rehearse the demo.

Owned implementation paths: `src/main/resources/ui/`, `tools/`, and `src/test/java/org/streamcollab/e2e/`. Member 3 reviews UI/API work. Each module owner fixes their own defects.

## API review checklist (Members 3 and 5)

These items are open decisions, not new API requirements or approved schemas. Record agreed answers in `docs/local-http-api.md` before adapter integration.

- [ ] Full login/logout and `/api/state` JSON, including recent messages and transfer metadata.
- [ ] Event payload examples for every declared event type; cursor expiry status and response.
- [ ] Token bootstrap, Origin/Host rules, token expiry and authorized downloads.
- [ ] Upload headers, Content-Length rules, size/quota limits and staging cleanup.
- [ ] Command deduplication duration and behavior after reconnect or agent restart.
- [ ] HTTP error statuses, retry rules and long-poll concurrency limits.
- [ ] Accept/reject/cancel responses and races with terminal transfer states.
- [ ] Fixtures/mock supplied by Member 3 match the agreed contract.

## UI behavior checklist

- [ ] Login failure is actionable; connection state remains visible.
- [ ] Global and direct targets are explicit; offline recipients show a defined error.
- [ ] HTTP 202 means command admission; show server acceptance and recipient delivery separately.
- [ ] Global chat shows server acceptance only; no read receipts are implied.
- [ ] Disable duplicate submission while pending; retain request ID for retries within the agreed window.
- [ ] Separate local upload, bytes transferred, verification and final completion.
- [ ] Sender progress describes bytes acknowledged by server, not bytes verified by recipient.
- [ ] Downloads appear only for verified received files; 100% bytes is not completion.
- [ ] Offer accept/reject, cancel and failure states have visible feedback.
- [ ] Expired cursor fetches a fresh consistent snapshot before polling resumes.
- [ ] Reconnect shows failed pending transfers; automatic resume is outside baseline.
- [ ] Render user text and filenames as text, never executable HTML; never expose secrets.

## Integration dependencies

| Dependency | Owner | Needed for |
|---|---|---|
| Pinned test framework and launch commands | 2 with 5 | Executable E2E and runner |
| Demo accounts and server configuration | 2 | Login and 20-client scenarios |
| Approved HTTP schemas and mock | 3 with 5 | UI adapter and UI tests |
| Authenticated fault-injection boundary | 1/3 with 5 | Simulator |
| File lifecycle and checksum evidence | 4 | File E2E and demo |

No dependency is considered ready merely because its directory exists. `mvn compile` or an empty test run does not prove application behavior.

## Evidence and handoff

Use `docs/member5-manual-tests.md` for initial scenarios and `docs/test-results/TEMPLATE.md` for each run. Existing `docs/test-plan.md` remains the canonical system acceptance matrix; `docs/demo-plan.md` remains the canonical presentation sequence. Do not claim mock results as integrated success.
