#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if ! java -version 2>&1 | grep -q 'version "21\.'; then
  echo 'JDK 21 required for Stage 4 chat smoke tests' >&2
  exit 1
fi

mkdir -p target/member3-stage4
javac -encoding UTF-8 -d target/member3-stage4 \
  src/main/java/org/streamcollab/client/chat/*.java \
  src/test/java/org/streamcollab/unit/ChatServiceSmokeTest.java
java -cp target/member3-stage4 org.streamcollab.unit.ChatServiceSmokeTest
