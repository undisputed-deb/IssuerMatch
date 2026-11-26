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

### Firm roles (investors)
An investor firm can have several people. The platform role stays `INVESTOR` for all of them; what each may do inside the
firm comes from `investor_members`, read from the database on every request. A role sent by a client is ignored.

| In the firm | Viewer | Analyst | Managing Director |
|---|---|---|---|
| Firm profile (read), matched deals, firm's offers and questions, team roster | yes | yes | yes |
| Ask the issuer, pass on a deal | no | yes | yes |
| Edit the profile and mandates, submit offers | no | no | yes |
| Invite, revoke invitations, change roles, remove members, see pending invitations | no | no | yes |

- A role too low answers `403`. Another firm's deal, invitation or member answers `404`.
- Whoever creates a profile becomes its first Managing Director. Migration V28 made every earlier owner one.
- One user belongs to at most one firm (`UNIQUE (user_id)`).
- A firm cannot lose its last Managing Director (`409`). Team changes lock the firm's row and read the caller's role
  again, so two directors acting at once cannot both succeed.
- A member who is demoted or removed loses the invitations they had left open.
- `investors.owner_user_id` is kept. It counts only for a profile with no member rows at all (inserted directly, not
  through the app), so a removed owner does not return through it.

### Invitation links
`POST /investors/me/invites` returns `/invite/<token>` once. The token is 32 random bytes; the database keeps its SHA-256
digest. It lasts 7 days, works once, and only for the invited address (compared without case). No email is sent.
- `GET /investor-invites/{token}/preview` is public and rate limited: firm name, role, masked address, expiry. A link that
  is unknown, expired, revoked or used answers `404`.
- `POST /investor-invites/{token}/accept` needs a signed-in `INVESTOR` whose token email matches (`403` otherwise). A used
  link answers `409`, as does a caller who already belongs to a firm.
- Signup accepts the token and refuses another address before it creates an account.
- The web server does not log `/invite/` requests and sends `Referrer-Policy: no-referrer` for that page.
- Limit: signup does not prove that someone owns an address. Whoever holds a link and registers the invited address first
  can join. Send links privately, and revoke one that went astray.

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
| Own firm profile and team (`/investors/me`) | no | by firm role, see above | no | no |
| Investors and mandates (list, read) | no | no | yes | yes |
| Investors and mandates: change | no | no | no | yes |
| Match runs, results, approve / reject | no | no | yes | yes |
| Commitments: create | no | own profile, approved matches | yes | yes |
| Commitments: list, read | no | no | yes | yes |
| Review a flagged commitment | no | no | yes (not their own) | yes (not their own) |
| Risk rules, dead letters, reconciliation, metrics | no | no | no | yes |

An investor is "matched" to a need only when a match run for it is `APPROVED` and ranked the firm that user belongs to.
Pending and rejected runs show nothing. `MatchVisibility` holds this rule.

### What each side sees about the other
- An investor sees the company name, the need and its own score, brief and questions, plus the company card: Verified
  badge, contact name and role, website, summary, founding year, team size, amount raised, runway and data room link.
  Only an approved match receives that card. An investor never receives the analyst explanation, the tool calls, its rank,
  other investors, their offers or their questions.
- An issuer never sees the investor list or the ranking. It learns about an investor when that investor asks a question
  (firm name) or submits an offer: firm, type, contact name and role, assets or revenue, deployable capital, website,
  Verified badge, amount, terms and rationale. The thesis and mandates stay private.
- Nobody can set a Verified badge on itself. The profile endpoints reject the field, the entities map the column read
  only, and one admin endpoint writes it (the **Verification** screen calls it). Changing a verified company's or firm's
  name or website removes the badge and records `..._VERIFICATION_REVOKED`; other edits keep it.
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
