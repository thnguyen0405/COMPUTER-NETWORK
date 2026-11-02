#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p target/member3-stage3
javac -encoding UTF-8 -d target/member3-stage3 \
  src/main/java/org/streamcollab/shared/security/*.java \
  src/test/java/org/streamcollab/unit/UdpSessionCryptoSmokeTest.java
java -cp target/member3-stage3 org.streamcollab.unit.UdpSessionCryptoSmokeTest
