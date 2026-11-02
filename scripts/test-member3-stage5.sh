#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! java -version 2>&1 | grep -q 'version "21\.'; then
  echo 'JDK 21 required.' >&2
  exit 1
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
find src/main/java/org/streamcollab/client/connection \
     src/main/java/org/streamcollab/client/chat \
     src/main/java/org/streamcollab/client/httpbridge \
     -name '*.java' -print > "$OUT/sources.txt"
printf '%s\n' src/test/java/org/streamcollab/unit/LoopbackHttpBridgeSmokeTest.java >> "$OUT/sources.txt"
javac -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"
java -cp "$OUT/classes" org.streamcollab.unit.LoopbackHttpBridgeSmokeTest
