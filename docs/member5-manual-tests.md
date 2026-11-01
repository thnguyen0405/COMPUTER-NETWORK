# Member 5 executable-test preparation

Status: all cases NOT RUN. These procedures become executable when the server, agents and approved HTTP bridge exist. They do not introduce a test dependency or assume launch commands.

Common setup: record commit, JDK/Maven/OS, configuration and accounts; start one server and isolated agents A/B/C using documented commands. Use distinct loopback HTTP ports and runtime directories. Never record passwords, API tokens or keys.

| Case / system ID | Procedure | Expected result |
|---|---|---|
| M5-01 / T01 | Attempt invalid login, then valid login on A/B/C. | Invalid login has actionable error; valid sessions appear in online lists. |
| M5-02 / T03 | A sends one global message and one direct message to B. | B/C receive global once; only B receives direct; acceptance and delivery statuses remain distinct. |
| M5-03 / T10 | Rapidly click Send twice for one pending command; retry the same request ID within the agreed deduplication window. | One logical operation ID and one application delivery; separate intentional messages remain allowed. |
| M5-04 / T10 | Expire the event cursor using an agreed test hook or retention limit, then resume polling. | RESYNC_REQUIRED leads to fresh state/cursor and renewed polling without duplicate visible messages. |
| M5-05 / T05 | A transfers a file to B; drop a file segment through the approved injection hook; continue independent chat. | Chat continues; file recovers; unique-byte progress and final SHA-256 agree. |
| M5-06 / T06 | Transfer empty, binary, chunk-boundary and generated large files. | Exact size/hash; empty file verifies; download only after completion. Record actual sizes. |
| M5-07 / T08 | In separate runs reject, cancel, disconnect during transfer, and trigger checksum failure through an approved test seam. | Defined terminal state, no false completion/download, temporary data cleaned according to contract. |
| M5-08 / T02 | Start 20 independent authenticated agents; exchange chat and disconnect a subset. | Remaining clients remain usable; server does not require restart. Record actual client count and outcomes. |
| M5-09 / T12 | Follow README from a clean configured setup; run the five demo scenarios. | Commands and reset steps are sufficient; record missing steps as defects. |

For M5-06, choose sizes 0, 1, chunk-size minus 1, chunk-size, chunk-size plus 1, and a generated 100 MiB file when supported by configured limits. Generate large data in ignored runtime storage rather than committing it.

For impairment tests, establish a no-fault baseline, test each fault separately, then combine configured 5% loss and 50 ms one-way delay with explicitly recorded duplication/reordering settings in both directions. Record seed, injection boundary and queue limits. A seed alone does not guarantee identical scheduling; preserve the actual fault trace if supported.

Reset: stop only processes started for this run; close browser sessions; ensure pending transfers have ended; archive sanitized evidence; clear only this run's disposable staging/download data; restart with fresh sessions and the same recorded configuration. Preserve account and trust configuration. Replace this procedure with verified exact commands when launch tooling exists.

Member 5 records findings and routes failures to the relevant module owner. Mock runs, localhost runs and LAN runs must be labeled separately. Use NOT RUN or BLOCKED when prerequisites are absent.
