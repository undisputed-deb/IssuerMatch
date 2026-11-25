# Authentication and authorization

Supabase Auth logs users in and issues JWTs. This API only verifies them. It never sees a password.

## How a request is checked
1. `Authorization: Bearer <jwt>` is required on everything except `GET /health`.
2. The signature is checked against the keys at `SUPABASE_JWKS_URI`. Only ES256 and RS256 are accepted. HS256 and `alg: none` are refused.
3. `iss` must equal `<SUPABASE_URL>/auth/v1`, `aud` must contain `authenticated`, and the token must not be expired.
4. Roles come from `app_metadata.roles` only. `user_metadata` is ignored because users can edit it. The top-level `role` claim is ignored because it is the Postgres role.
5. Any failure returns `401 {"success":false,"error":{"code":"UNAUTHORIZED",...}}` with no hint about why.

The app refuses to start if `SUPABASE_JWKS_URI` or `SUPABASE_URL` is missing.

## Roles
Give a user a role in the Supabase dashboard: Authentication, Users, the user, edit raw app metadata:
```json
{ "roles": ["ANALYST"] }
```
Valid values: `ISSUER`, `ANALYST`, `ADMIN`. A token carries the roles it had when it was issued, so a change shows up after the next token refresh.

| | ISSUER | ANALYST | ADMIN |
|---|---|---|---|
| Issuers: list / read | own only | all | all |
| Issuers: create / change / delete | own only | no | all (may name another owner) |
| Funding needs: submit | own companies | no | any company |
| Funding needs: list / read | own only | all | all |
| Investors and mandates: read | no | yes | yes |
| Investors and mandates: change | no | no | yes |
| Match runs and results: read | no | yes | yes |
| Approve / reject a run | no | yes | yes |
| Record a commitment | no | yes | yes |
| Review a flagged commitment | no | yes (not their own) | yes (not their own) |
| Read / change risk rules (`/admin/risk-rules`) | no | no | yes |

An issuer asking for someone else's data gets `404`, the same answer as for an id that does not exist, so ids cannot be probed.
A missing role gets `403`.

## Who did it
- `match_runs.requested_by`: the user who submitted the funding need.
- `match_runs.reviewed_by`: the analyst or admin who approved or rejected.
- `commitments.created_by`: who recorded it.
- `idempotency_keys` are per user: user B reusing user A's key creates their own commitment and sees nothing of A's.
- `audit_log` and the `audit.events` topic: actor, action, resource, IP, time. IP is the socket address. `X-Forwarded-For` is not trusted because any client can forge it.

## Agent permissions
The agent's tools are read-only, and `scoreFit` is bound to the funding need of its own run. A prompt injection in one issuer's text cannot make the agent read another issuer's need.
