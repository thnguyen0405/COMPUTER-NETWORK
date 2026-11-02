# Member 3 — Stage 2 local test evidence

- Date: 2026-11-02
- Environment: Linux container; OpenJDK 21.0.12.1; `javac` + JDK keytool. Maven was not used for this record.
- Test command: `bash scripts/test-member3-stage2.sh`
- Result:

```text
PASS: 27 Member 3 connection lifecycle checks
PASS: 43 Member 3 stage 2 supervisor checks (mock auth/UDP)
PASS: 3 real TLS scenarios (trusted host / hostname mismatch / untrusted cert)
```

- Covered: valid/invalid state transitions, successful lifecycle with FAKE auth/UDP, failure classification, resource cleanup, explicit retry budget, logout/auth race, credential guards, real loopback TLS verification (trusted name, wrong hostname, untrusted cert).
- NOT covered: Maven pipeline, macOS clean startup, real login server, authenticated UDP binding, heartbeat monitoring, genuine client/server end-to-end scenario, file transfer, browser HTTP endpoints.
- Expected gaps: wire/control protocols have not been frozen across Member 1/2/3, so adapters have no production implementation yet.
