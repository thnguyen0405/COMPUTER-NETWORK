# Member 3 Stage 5 local verification

Environment: OpenJDK 21.0.12.1, Linux build environment, Nov 2 2026.

Commands executed from the combined original repo + Stage 2/3/4/5 sources:

```text
bash scripts/test-member3-stage5.sh
PASS: 46 Member 3 stage 5 loopback HTTP checks (mock gateway/chat transport)

bash scripts/test-member3-stage4.sh
PASS: 638 Member 3 stage 4 chat service checks (mock transport)

bash scripts/test-member3-stage3.sh
PASS: 47 Member 3 stage 3 AES-GCM and replay checks

bash scripts/test-member3-stage2.sh
PASS: 27 Member 3 connection lifecycle checks
PASS: 43 Member 3 stage 2 supervisor checks (mock auth/UDP)
PASS: 3 real TLS scenarios (trusted host / hostname mismatch / untrusted cert)
```

Counts represent asserted test conditions, **not** independent test cases. The Local HTTP Stage-5 test uses real HTTP TCP sockets on 127.0.0.1 but fake application login and chat transport. No real end-to-end collaboration was performed. Re-run on macOS after installation.

File endpoints remain HTTP 501 until Member 4 integration; packaged UI assets not present in original source, so `GET /` returns 404. No production readiness claim.
