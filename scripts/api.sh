#!/bin/sh
# Log in to Supabase and call the local API. No secrets live in this file.
#   scripts/api.sh GET /issuers
#   scripts/api.sh PUT /admin/risk-rules/velocity '{"enabled":true,"params":{"flagCount":7}}'
#   scripts/api.sh token                         # just print an access token
# Reads SUPABASE_URL and VITE_SUPABASE_ANON_KEY from backend/.env, and the login from backend/.env.admin
# (or set ADMIN_EMAIL / ADMIN_PASSWORD in the environment). Both files are git-ignored.
set -eu
dir="$(cd "$(dirname "$0")/../backend" && pwd)"
val() { grep -m1 "^$2=" "$1" | cut -d= -f2-; }
url=$(val "$dir/.env" SUPABASE_URL)
key=$(val "$dir/.env" VITE_SUPABASE_ANON_KEY)
email=${ADMIN_EMAIL:-$(val "$dir/.env.admin" ADMIN_EMAIL)}
password=${ADMIN_PASSWORD:-$(val "$dir/.env.admin" ADMIN_PASSWORD)}

# the password goes to curl on stdin, so it never shows up in the process list
token=$(EMAIL="$email" PASSWORD="$password" python3 -c 'import json,os;print(json.dumps({"email":os.environ["EMAIL"],"password":os.environ["PASSWORD"]}))' \
  | curl -sS -X POST "$url/auth/v1/token?grant_type=password" -H "apikey: $key" -H "Content-Type: application/json" --data-binary @- \
  | python3 -c 'import json,sys;d=json.load(sys.stdin);t=d.get("access_token");sys.exit("login failed: "+str(d.get("msg") or d.get("error_description") or d.get("error_code"))) if not t else print(t)')

if [ "${1:-}" = "token" ]; then echo "$token"; exit 0; fi
method=${1:?usage: scripts/api.sh METHOD /path [json-body]}
path=${2:?usage: scripts/api.sh METHOD /path [json-body]}
body=${3:-}
base=${API_URL:-http://localhost:8080}
set -- -sS -X "$method" "$base$path" -H "Authorization: Bearer $token" -w '\nHTTP %{http_code}\n'
[ -n "$body" ] && set -- "$@" -H "Content-Type: application/json" -d "$body"
# a fresh Idempotency-Key for writes that need one, unless the caller provides IDEMPOTENCY_KEY
case "$path" in /commitments) set -- "$@" -H "Idempotency-Key: ${IDEMPOTENCY_KEY:-cli-$(date +%s)-$$}";; esac
exec curl "$@"
