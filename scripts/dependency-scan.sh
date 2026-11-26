#!/bin/sh
# Scan every dependency (including transitive ones) for known vulnerabilities. Exits non-zero on any finding.
# A pom.xml scan is not enough: versions come from BOMs, so the scanner would skip them. We scan a generated SBOM.
# Needs: osv-scanner (brew install osv-scanner).
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/backend"
./mvnw -q org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DincludeTestScope=false -DoutputFormat=json -DoutputName=bom
osv-scanner scan source -L target/bom.json
if [ -f "$root/frontend/package-lock.json" ]; then
  osv-scanner scan source -L "$root/frontend/package-lock.json"
fi
