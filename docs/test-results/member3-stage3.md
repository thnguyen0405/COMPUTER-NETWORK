# Member 3 — Stage 3 security test results

Environment: OpenJDK 21.0.12.1, Linux test environment; Maven not available here,
so tests were compiled/executed directly with Java 21 `javac` and `java`.

```text
PASS: 47 Member 3 stage 3 AES-GCM and replay checks
PASS: 27 Member 3 connection lifecycle checks
PASS: 43 Member 3 stage 2 supervisor checks (mock auth/UDP)
PASS: 3 real TLS scenarios (trusted host / hostname mismatch / untrusted cert)
```

Coverage of stage 3: independent directional keys, encrypt/decrypt in both directions,
modified header/payload/packet number rejected, replay rejected, successful decryption
not poisoned by failed authentication, out-of-order acceptance within replay window,
too-old replay rejected, fresh retransmit nonce, size cap, closed-session behavior,
wrong session/secret/role failure, invalid input, caller key buffer clearing.

**Limitations:** This is a smoke-test suite; no live UDP integration, formal security
review, concurrency stress test, fuzzing, or confirmed compatibility with Member 1's
wire framing and Member 2's TLS authentication protocol. The default replay window,
HKDF context, nonce design, and crypto interface need team review before final
integration, as the repo explicitly marks those decisions open.
