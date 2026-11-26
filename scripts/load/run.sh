#!/bin/sh
# Reproducible load test: throwaway Postgres + Kafka in Docker, the packaged app on this machine, k6.
#   scripts/load/run.sh            (needs Docker, k6, backend/.env and backend/.env.admin)
#   VUS=100 DURATION=120s scripts/load/run.sh
# It never touches the Supabase database: the app is pointed at the local one and the run aborts if it is not.
set -eu
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
out="scripts/load/results"; mkdir -p "$out"
VUS=${VUS:-50}; DURATION=${DURATION:-60s}
app_pid=""
cleanup() {
  if [ -n "$app_pid" ]; then
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true   # let it finish shutting down before its database and broker disappear
  fi
  docker compose --profile load down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

curl -sf localhost:8080/health >/dev/null && { echo "port 8080 is already serving an app: stop it first"; trap - EXIT; exit 1; }
docker compose --profile load down -v >/dev/null 2>&1 || true
docker compose --profile load up -d --wait kafka postgres-load >/dev/null 2>&1
(cd backend && ./mvnw -q package -DskipTests)

# Environment variables override backend/.env, so the database below is the local throwaway one.
(cd backend && exec env SUPABASE_DB_URL=jdbc:postgresql://localhost:5433/postgres SUPABASE_DB_USER=postgres \
  SUPABASE_DB_PASSWORD=unused APP_RATELIMIT_ENABLED=false java -jar target/issuermatch-0.0.1-SNAPSHOT.jar) > "$out/app.log" 2>&1 &
app_pid=$!
i=0; until curl -sf localhost:8080/health >/dev/null; do
  i=$((i+1)); [ $i -gt 60 ] && { echo "app did not start, see $out/app.log"; exit 1; }; sleep 1; done
grep -q "jdbc:postgresql://localhost:5433/" "$out/app.log" || { echo "ABORT: app is not using the local load database"; exit 1; }

sql() { docker exec -i issuermatch-pg-load psql -U postgres -v ON_ERROR_STOP=1 -At "$@"; }
sql -q < scripts/load/seed.sql
sql -c "SELECT json_agg(json_build_object('needId', m.need_id, 'investorId', r.investor_id)) FROM match_results r JOIN match_runs m ON m.id = r.run_id" > scripts/load/pairs.json

TOKEN=$(scripts/api.sh token)
echo "== warm-up (not measured) =="
k6 run -q -e TOKEN="$TOKEN" -e RUN_ID=warm -e VUS="$VUS" -e DURATION=20s scripts/load/commitments.js >/dev/null 2>&1 || true
until [ "$(sql -c 'SELECT count(*) FROM outbox WHERE published_at IS NULL')" = "0" ]; do sleep 1; done
before=$(sql -c 'SELECT count(*) FROM commitments')

echo "== measured run: $VUS virtual users for $DURATION =="
k6 run -q -e TOKEN="$TOKEN" -e RUN_ID=main -e VUS="$VUS" -e DURATION="$DURATION" \
  --summary-export "$out/summary.json" scripts/load/commitments.js 2>&1 | tee "$out/k6.txt" || echo "(k6 thresholds not met)"

echo "== after the run =="
echo "commitments created in measured run: $(( $(sql -c 'SELECT count(*) FROM commitments') - before ))"
echo "outbox backlog right after load:     $(sql -c 'SELECT count(*) FROM outbox WHERE published_at IS NULL')"
start=$(date +%s)
expected="SELECT (SELECT count(*) FROM commitments) + (SELECT count(*) FROM commitments WHERE status IN ('ACCEPTED','REJECTED'))"
caught_up="no (gave up after 10 min)"
i=0; while [ $i -le 600 ]; do
  if [ "$(sql -c 'SELECT count(*) FROM commitment_events')" -ge "$(sql -c "$expected")" ]; then
    caught_up="yes, $(( $(date +%s) - start )) s after the load stopped"; break; fi
  i=$((i+1)); sleep 1; done
echo "event ledger caught up:              $caught_up"
echo "over-filled needs (must be 0):       $(sql -c 'SELECT count(*) FROM funding_needs WHERE remaining_capacity < 0')"
echo "needs where capacity != commitments: $(sql -c "SELECT count(*) FROM funding_needs n WHERE n.amount - n.remaining_capacity <> (SELECT coalesce(sum(amount),0) FROM commitments c WHERE c.need_id = n.id AND status IN ('ACCEPTED','PENDING_REVIEW'))")"
echo "duplicate idempotency results (0):   $(sql -c 'SELECT count(*) - count(DISTINCT (actor, idem_key)) FROM idempotency_keys')"
sleep 61  # past the reconciliation grace period
echo "reconciliation: $(scripts/api.sh POST /admin/reconciliation/run | head -1 | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["mismatchCount"], "mismatches", d["countsByType"])')"
