#!/bin/sh
# Break things on purpose and check the app copes. Local throwaway database only; the Gemini key is deliberately
# invalid so no model call costs money. Needs Docker, backend/.env, backend/.env.admin.
set -eu
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"
mkdir -p scripts/load/results; log=scripts/load/results/chaos-app.log; app_pid=""
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
  SUPABASE_DB_PASSWORD=unused GEMINI_API_KEY=invalid-on-purpose java -jar target/issuermatch-0.0.1-SNAPSHOT.jar) > "$log" 2>&1 &
app_pid=$!
i=0; until curl -sf localhost:8080/health >/dev/null; do i=$((i+1)); [ $i -gt 60 ] && { echo "app did not start"; exit 1; }; sleep 1; done
grep -q "jdbc:postgresql://localhost:5433/" "$log" || { echo "ABORT: not the local database"; exit 1; }

sql() { docker exec -i issuermatch-pg-load psql -U postgres -v ON_ERROR_STOP=1 -At "$@"; }
TOKEN=$(scripts/api.sh token)
call() { curl -sS --max-time 60 -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$@"; }
wait_for() { n=0; until [ "$(sql -c "$1")" = "$2" ]; do n=$((n+1)); [ $n -gt "$3" ] && return 1; sleep 1; done; }
need_body() { printf '{"issuerId":"%s","title":"chaos","description":"chaos","sector":"FINTECH","region":"EUROPE","amount":1000,"instrument":"EQUITY","riskLevel":"LOW"}' "$1"; }

issuer=$(call -X POST localhost:8080/issuers -d '{"name":"Chaos Co","sector":"FINTECH","region":"EUROPE"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')

echo "== 1. model call fails (invalid Gemini key) =="
need=$(call -X POST localhost:8080/funding-needs -d "$(need_body "$issuer")" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
wait_for "SELECT count(*) FROM match_runs WHERE need_id='$need' AND status='FAILED'" 1 90 && echo "run FAILED safely" || echo "UNEXPECTED: run did not fail"
echo "stored error: $(sql -c "SELECT error FROM match_runs WHERE need_id='$need'")"
echo "need status: $(sql -c "SELECT status FROM funding_needs WHERE id='$need'")"

echo "== 2. Kafka down =="
docker compose stop kafka >/dev/null 2>&1
code=$(call -o scripts/load/results/k.json -w '%{http_code}' -X POST localhost:8080/funding-needs -d "$(need_body "$issuer")")
need=$(python3 -c 'import json;print(json.load(open("scripts/load/results/k.json"))["id"])')
echo "submit while broker is down: HTTP $code"
sleep 5; echo "events waiting in the outbox: $(sql -c "SELECT count(*) FROM outbox WHERE published_at IS NULL AND event_key='$need'")"
docker compose start kafka >/dev/null 2>&1; docker compose up -d --wait kafka >/dev/null 2>&1
wait_for "SELECT count(*) FROM match_runs WHERE need_id='$need'" 1 120 && echo "after the broker returned: run created, nothing lost" || echo "UNEXPECTED: event lost"

echo "== 3. database down =="
docker compose --profile load stop postgres-load >/dev/null 2>&1
start=$(date +%s)
out=$(call -w ' HTTP %{http_code}' localhost:8080/issuers)
echo "GET /issuers: $out (after $(( $(date +%s) - start )) s)"
echo "GET /actuator/health: $(curl -sS --max-time 60 -w ' HTTP %{http_code}' localhost:8080/actuator/health)"
echo "liveness /health: $(curl -sS --max-time 10 -w ' HTTP %{http_code}' localhost:8080/health)"
docker compose --profile load start postgres-load >/dev/null 2>&1; docker compose --profile load up -d --wait postgres-load >/dev/null 2>&1
n=0; until [ "$(call -o /dev/null -w '%{http_code}' localhost:8080/issuers)" = "200" ]; do n=$((n+1)); [ $n -gt 60 ] && break; sleep 1; done
echo "after the database returned: GET /issuers HTTP $(call -o /dev/null -w '%{http_code}' localhost:8080/issuers)"

echo "== secrets in the log? =="
for v in GEMINI_API_KEY SUPABASE_DB_PASSWORD; do val=$(grep -m1 "^$v=" backend/.env | cut -d= -f2-); grep -qF -- "$val" "$log" && echo "LEAK: $v" || echo "$v not in log"; done
grep -qF -- "$TOKEN" "$log" && echo "LEAK: access token" || echo "access token not in log"
