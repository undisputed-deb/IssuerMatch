#!/bin/sh
# Browser tests: real Supabase login and a real Gemini call.
#   scripts/e2e.sh            staff flow on a throwaway database
#   scripts/e2e.sh accounts   signup and investor/issuer flow against the running "app" stack; creates real accounts
# Whatever happens, the accounts and E2E rows the tests created are deleted on exit.
# Needs Docker, backend/.env, backend/.env.admin, and once: cd frontend && npm ci && npx playwright install chromium
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"
trap 'exit 130' INT TERM # so the EXIT trap also runs on Ctrl-C
if [ "${1:-}" = "accounts" ]; then
  curl -sf localhost:8080/health >/dev/null || { echo "start the app stack first (see README)"; exit 1; }
  set -a; . backend/.env; . backend/.env.admin; set +a
  trap 'sh scripts/e2e-cleanup.sh postgres' EXIT
  (cd frontend && SIGNUP_E2E=1 npx playwright test signup deal)
  exit 0
fi
mkdir -p scripts/load/results; log=scripts/load/results/e2e-app.log; app_pid=""
cleanup() {
  [ -n "$app_pid" ] && { kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; }
  sh scripts/e2e-cleanup.sh postgres-load || true
  docker compose --profile load down -v >/dev/null 2>&1 || true
}
curl -sf localhost:8080/health >/dev/null && { echo "port 8080 is busy"; exit 1; }
trap cleanup EXIT
docker compose --profile load down -v >/dev/null 2>&1 || true
docker compose --profile load up -d --wait kafka postgres-load >/dev/null 2>&1
(cd backend && ./mvnw -q package -DskipTests)
(cd backend && exec env SUPABASE_DB_URL=jdbc:postgresql://localhost:5433/postgres SUPABASE_DB_USER=postgres \
  SUPABASE_DB_PASSWORD=unused SPRING_PROFILES_ACTIVE=seed java -jar target/issuermatch-0.0.1-SNAPSHOT.jar) > "$log" 2>&1 &
app_pid=$!
i=0; until grep -q "Synthetic data created" "$log" 2>/dev/null; do i=$((i+1)); [ $i -gt 60 ] && { echo "app did not start, see $log"; exit 1; }; sleep 1; done
grep -q "jdbc:postgresql://localhost:5433/" "$log" || { echo "ABORT: not the local database"; exit 1; }
set -a; . backend/.env; . backend/.env.admin; set +a
export E2E_DB_SERVICE=postgres-load
(cd frontend && npx playwright test) # in a subshell, so the EXIT trap still runs from the repo root
