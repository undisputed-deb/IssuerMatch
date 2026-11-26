#!/bin/sh
# Browser tests: real Supabase login and a real Gemini call. If Gemini is unavailable the app ranks without it and the
# test still passes; REQUIRE_LIVE_MODEL=true makes that a failure.
#   scripts/e2e.sh            staff flow on a throwaway database
#   scripts/e2e.sh accounts   signup, deal and team flows against the running "app" stack; creates real accounts
#   scripts/e2e.sh offline    every browser test with Gemini unreachable, on its own Kafka, database and ports
#                             (API :8081, web :5174); a running app stack is not touched. Creates real accounts.
# Whatever happens, the accounts and E2E rows the tests created are deleted on exit.
# Needs Docker, backend/.env, backend/.env.admin, and once: cd frontend && npm ci && npx playwright install chromium
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"
trap 'exit 130' INT TERM # so the EXIT trap also runs on Ctrl-C
if [ "${1:-}" = "accounts" ]; then
  curl -sf localhost:8080/health >/dev/null || { echo "start the app stack first (see README)"; exit 1; }
  set -a; . backend/.env; . backend/.env.admin; set +a
  trap 'sh scripts/e2e-cleanup.sh postgres' EXIT
  (cd frontend && SIGNUP_E2E=1 npx playwright test signup deal team --workers=1)
  exit 0
fi
if [ "${1:-}" = "offline" ]; then
  log=scripts/load/results/e2e-offline-app.log; mkdir -p scripts/load/results; app_pid=""
  for port in 8081 5174; do
    curl -s "localhost:$port" >/dev/null 2>&1 && { echo "port $port is busy"; exit 1; }
  done
  stack() { docker compose --profile e2e "$@"; }
  offline_cleanup() {
    sh scripts/e2e-cleanup.sh postgres-load || true
    [ -n "$app_pid" ] && { kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; }
    stack rm -sfv kafka-e2e postgres-load >/dev/null 2>&1 || true
  }
  trap offline_cleanup EXIT
  stack rm -sfv kafka-e2e postgres-load >/dev/null 2>&1 || true
  stack up -d --wait kafka-e2e postgres-load >/dev/null 2>&1
  (cd backend && ./mvnw -q package -DskipTests)
  # Gemini out of reach twice over: a dummy key replaces the real one, and HTTPS to anything but Supabase goes to a
  # proxy port where nothing listens (E2E_BLOCK_PORT can point at a listener that records what was blocked).
  block="${E2E_BLOCK_PORT:-9}"
  (cd backend && exec env SUPABASE_DB_URL=jdbc:postgresql://localhost:5433/postgres SUPABASE_DB_USER=postgres \
    SUPABASE_DB_PASSWORD=unused KAFKA_BOOTSTRAP_SERVERS=localhost:9094 SPRING_PROFILES_ACTIVE=seed SERVER_PORT=8081 \
    APP_CORS_ORIGIN=http://localhost:5174 GEMINI_API_KEY=offline-no-key \
    java -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort="$block" '-Dhttp.nonProxyHosts=localhost|127.0.0.1|*.supabase.co' \
    -Dapp.agent.daily-token-budget=0 -Dspring.ai.retry.max-attempts=1 -Dapp.embeddings.initial-delay=24h -Dapp.rate-limit.signup-per-minute=100 \
    -Dapp.rate-limit.signup-global-per-minute=200 -Dapp.rate-limit.ip-per-minute=3000 -Dapp.rate-limit.user-per-minute=1000 \
    -jar target/issuermatch-0.0.1-SNAPSHOT.jar) > "$log" 2>&1 &
  app_pid=$!
  i=0; until grep -q "Synthetic data created" "$log" 2>/dev/null; do i=$((i+1)); [ $i -gt 90 ] && { echo "app did not start, see $log"; exit 1; }; sleep 1; done
  grep -q "jdbc:postgresql://localhost:5433/" "$log" || { echo "ABORT: not the local database"; exit 1; }
  set -a; . backend/.env; . backend/.env.admin; set +a
  unset GEMINI_API_KEY # the browser tests have no use for it
  (cd frontend && E2E_DB_SERVICE=postgres-load SIGNUP_E2E=1 WEB_URL=http://localhost:5174 API_URL=http://localhost:8081 \
    VITE_API_URL=http://localhost:8081 npx playwright test --workers=1)
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
