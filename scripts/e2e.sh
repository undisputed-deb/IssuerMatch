#!/bin/sh
# Browser test of the whole product: real Supabase login, real Gemini call, local throwaway database.
# Needs Docker, backend/.env, backend/.env.admin, and once: cd frontend && npm ci && npx playwright install chromium
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"
mkdir -p scripts/load/results; log=scripts/load/results/e2e-app.log; app_pid=""
cleanup() {
  [ -n "$app_pid" ] && { kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; }
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
set -a; . backend/.env.admin; set +a
cd frontend && npx playwright test
