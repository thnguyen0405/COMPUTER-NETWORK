#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if ! java -version 2>&1 | grep -q 'version "21\.'; then
  echo 'JDK 21 required' >&2
  exit 1
fi

mkdir -p target/member3-stage2
javac -encoding UTF-8 -d target/member3-stage2 \
  src/main/java/org/streamcollab/client/connection/*.java \
  src/test/java/org/streamcollab/unit/ConnectionStateMachineSmokeTest.java \
  src/test/java/org/streamcollab/unit/ClientConnectionSupervisorSmokeTest.java \
  src/test/java/org/streamcollab/unit/LocalTlsHandshakeSmokeTest.java

java -cp target/member3-stage2 org.streamcollab.unit.ConnectionStateMachineSmokeTest
java -cp target/member3-stage2 org.streamcollab.unit.ClientConnectionSupervisorSmokeTest

# This keystore is for one-off localhost tests only; never commit live keys/certs.
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
keytool -genkeypair -noprompt \
  -alias local-test -keyalg RSA -keysize 2048 -validity 1 \
  -storetype PKCS12 -keystore "$workdir/test.p12" \
  -storepass stage2test -keypass stage2test \
  -dname 'CN=localhost' -ext 'SAN=dns:localhost' >/dev/null 2>&1
java -cp target/member3-stage2 org.streamcollab.unit.LocalTlsHandshakeSmokeTest \
  "$workdir/test.p12" stage2test
