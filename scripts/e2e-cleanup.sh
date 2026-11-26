#!/bin/sh
# Deletes what the browser tests created: E2E% rows in a compose database and e2e-*@issuermatch.test accounts.
# usage: scripts/e2e-cleanup.sh [postgres|postgres-load]     (default: $E2E_DB_SERVICE, else the "app" stack's postgres)
# Account deletion needs SUPABASE_URL and an admin key in the environment; without them it is skipped and says so.
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"; cd "$root"
service="${1:-${E2E_DB_SERVICE:-postgres}}"
status=0
compose() { docker compose --profile app --profile load --profile e2e "$@"; }

if compose ps --status running --services 2>/dev/null | grep -qx "$service"; then
  if compose exec -T "$service" psql -U postgres -q -v ON_ERROR_STOP=1 < scripts/e2e-cleanup.sql; then
    echo "cleanup: E2E rows removed from $service"
  else
    echo "cleanup: FAILED to remove E2E rows from $service" >&2; status=1
  fi
else
  echo "cleanup: $service is not running, no rows removed"
fi

node scripts/e2e-delete-users.mjs || status=1
exit $status
