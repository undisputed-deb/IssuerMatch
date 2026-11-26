# Authentication and authorization

Supabase Auth logs users in and issues JWTs. This API verifies them. It sees a password only during public signup, where it passes it to Supabase and never stores or logs it.

## How a request is checked
1. `Authorization: Bearer <jwt>` is required on everything except health checks and `POST /auth/signup`.
2. The signature is checked against the keys at `SUPABASE_JWKS_URI`. Only ES256 and RS256 are accepted. HS256 and `alg: none` are refused.
3. `iss` must equal `<SUPABASE_URL>/auth/v1`, `aud` must contain `authenticated`, and the token must not be expired.
4. Roles come from `app_metadata.roles` only. `user_metadata` is ignored because users can edit it. The top-level `role` claim is ignored because it is the Postgres role.
5. Any failure returns `401 {"success":false,"error":{"code":"UNAUTHORIZED",...}}` with no hint about why.

The app refuses to start if `SUPABASE_JWKS_URI` or `SUPABASE_URL` is missing.

## Roles
Four roles, always read from `app_metadata.roles`: `ISSUER`, `INVESTOR`, `ANALYST`, `ADMIN`.

### Public signup (ISSUER and INVESTOR only)
`POST /auth/signup` with `{"email","password","fullName","role"}` (no token).
- `role` must be exactly `ISSUER` or `INVESTOR`. Anything else, including `ADMIN` and `ANALYST`, gets 400. Unknown fields get 400.
- The server creates the user through Supabase's Auth Admin API with `app_metadata: {"roles": [role]}`, using
  `SUPABASE_SERVICE_ROLE_KEY` (or an existing `SUPABASE_SECRET_KEY`). The key stays on the server. A second check in the
  client class refuses to create staff roles.
- The account is confirmed at once, so **nobody proves they own the email address**. For a real product, send a
  confirmation email instead.
- Rate limited to 5 per minute per address and 30 per minute overall. A taken email answers 409, which does reveal that
  an account exists.
- Email, name and password never reach the logs or the audit trail. Without a key the endpoint answers 503.

### Staff
An administrator grants `ANALYST` or `ADMIN` in Supabase (raw app metadata `{"roles":["ANALYST"]}`). A token carries the
roles it had when it was issued, so a change shows up after the next sign-in.

### Who may do what
| | ISSUER | INVESTOR | ANALYST | ADMIN |
|---|---|---|---|---|
| Companies (issuers): list / read | own | no | all | all |
| Companies: create / change / delete | own | no | no | all (may name another owner) |
| Funding needs: submit | own companies | no | no | any company |
| Funding needs: list / read | own | only those a human approved it for | all | all |
| Matched deals feed, own offers list | no | own | no | no |
| Pass on a deal | no | own approved match | no | no |
| Offers on a need: read | own need (rejected ones hidden) | no | all | all |
| Accepted offer: accept or decline | own need | no | no | no |
| Grant or remove a Verified badge | no | no | no | yes |
| Deal questions: read | every thread on own need | own thread on matched needs | all | all |
| Deal questions: ask | no | matched needs | no | no |
| Deal questions: answer | own need | no | no | no |
| Funding needs: request matching again | own | no | yes | yes |
| Own investor profile (`/investors/me`) | no | yes | no | no |
| Investors and mandates (list, read) | no | no | yes | yes |
| Investors and mandates: change | no | no | no | yes |
| Match runs, results, approve / reject | no | no | yes | yes |
| Commitments: create | no | own profile, approved matches | yes | yes |
| Commitments: list, read | no | no | yes | yes |
| Review a flagged commitment | no | no | yes (not their own) | yes (not their own) |
| Risk rules, dead letters, reconciliation, metrics | no | no | no | yes |

An investor is "matched" to a need only when a match run for it is `APPROVED` and ranked the investor profile that user owns.
Pending and rejected runs show nothing. `MatchVisibility` holds this rule.

### What each side sees about the other
- An investor sees the company name, the need and its own score, brief and questions, plus the company card: Verified
  badge, contact name and role, website, summary, founding year, team size and amount raised. It does not receive the
  runway or the data room link, the analyst explanation, the tool calls, its rank, other investors, their offers or
  their questions.
- An issuer never sees the investor list or the ranking. It learns about an investor when that investor asks a question
  (firm name) or submits an offer: firm, type, contact name and role, assets or revenue, deployable capital, website,
  Verified badge, amount, terms and rationale. The thesis and mandates stay private.
- Nobody can set a Verified badge on itself. The profile endpoints reject the field, the entities map the column read
  only, and one admin endpoint writes it. Editing a profile keeps an existing badge.
- Risk rule reasons go to staff. An investor receives the offer status without them.
- Using someone else's `investorId` in an offer, or offering on an unmatched need, answers `404`.

An issuer asking for someone else's data gets `404`, the same answer as for an id that does not exist, so ids cannot be probed.
A missing role gets `403`.
Request bodies are validated before the role check, so a caller without the role who sends an invalid body sees `400`. Nothing is written either way.

## Who did it
- `match_runs.requested_by`: the user who submitted the funding need.
- `match_runs.reviewed_by`: the analyst or admin who approved or rejected.
- `commitments.created_by`: who recorded it.
- `deal_questions.asked_by` and `answered_by`; `match_results.investor_decision`, `pass_reason` and `decided_at`.
- `idempotency_keys` are per user: user B reusing user A's key creates their own commitment and sees nothing of A's.
- `audit_log` and the `audit.events` topic: actor, action, resource, IP, time. IP is the socket address. `X-Forwarded-For` is not trusted because any client can forge it.

## Agent permissions
The agent's five tools are read-only. `scoreFit` and the semantic thesis search are bound to the funding need of their own run, so a prompt injection in one issuer's text cannot make the agent read another issuer's need.
Investor thesis text returned by the search is marked untrusted, and instruction-like content in it flags the run.
