## Goal
IssuerMatch: two-sided platform where companies (issuers) raise money and investor firms are ranked by a Gemini agent,
approved by staff, then question, offer and get an answer. Portfolio project, synthetic data only.
Repo root `/Users/Deb/Downloads/IssuerMatcher/IssuerMatch`, GitHub `undisputed-deb/IssuerMatch`, branch `main`.

**Current mission: prove it works end to end with REAL Gemini calls, fix what breaks, reach the finish line below.**

## Finish line (proposed; the user can change it)
- [ ] CI green on the latest commit, `images` job included.
- [ ] Local app stack rebuilt on latest code, healthy, database at Flyway V28.
- [ ] Live browser tests (signup, deal, team) pass, and the API log shows the **model** answered every match run
      (no "without the model", no "Model output rejected"); thesis and need embeddings stored.
- [ ] `REQUIRE_LIVE_MODEL=true scripts/e2e.sh` passes.
- [ ] Live output meets the prompt rules (test plan step 5).
- [ ] `scripts/chaos.sh` and `scripts/dependency-scan.sh` pass (neither re-run since the upgrades).
- [ ] Manual checklist (step 7) done, no leftover `E2E%` rows or `e2e-*` accounts.
- [ ] README "Measured results" updated with the new live numbers; this file updated; everything pushed.

Not part of the finish line unless the user asks: email confirmation on signup, deployment, second security audit,
repeat load test, merging Dependabot PRs.

## Done (all committed and pushed)
- Original build (phases 0-9): CRUD, Kafka outbox + idempotent consumers + DLT, agent with read-only tools, risk engine,
  idempotent commitments, reconciliation, Supabase JWT auth, rate limits, React UI, Docker, CI, load test, security audit.
- Two-sided platform (phases 1-5): roles ISSUER/INVESTOR/ANALYST/ADMIN, public signup, investor profiles, semantic
  search (pgvector), deterministic fallback matcher, offers, passes, private Q&A.
- Upgrade phase 1 (`433fc4c`): diligence questions go investor -> founder, batched investor Q&A, browser test cleanup.
- Upgrade phase 2 (`1c8457a`): company profile + Verified badge, investor role/deployment/mandate stages, new score
  weights (mandate25 ticket20 region10 risk10 track10 thesis10 stage10 participation5), issuer accept/decline with
  capacity release (`commitment.declined`), top 5 ranked, shorter analyst text.
- Upgrade phase 3 (`4b98aee`): investor firm teams (MANAGING_DIRECTOR/ANALYST/VIEWER), copy-link invites, badge revoked
  on name/website change, admin Verification page, CI uploads test reports on failure, investors see runway + data room
  only when approved.
- `edccaa9`: browser tests for team invite + offer loop, `scripts/e2e.sh offline`, `docker-compose.offline.yml`, fix for
  minutes-long fallback matching when Gemini is unreachable.

Last verified on `edccaa9`: `./mvnw test` 390 pass (95.0% lines / 86.0% branches), frontend build ok, 60 endpoints,
`scripts/e2e.sh offline` 6/6 pass.

## In progress
- Nothing half-written. Commit `a8982bd` is pushed; custom offer letter is prominent at the top of the Managing Director
  form. Frontend build passed and local web is healthy at localhost:5173. No Gemini calls.
- `gh` is unavailable, so the latest `images` CI log has not been inspected.

## Next steps
1. Check CI when GitHub CLI access is available (`gh` is not installed here).
2. No additional Gemini calls. Update README measured results only if the reviewed run metrics change.

## Decisions & gotchas
### Working rules (from the user)
- Commit author = the user only (git config is set). **No Co-Authored-By or tool attribution.** Prefixes
  feat/fix/test/docs/chore/refactor/security. Commit and push right after each finished task.
- Never print, log, commit or copy any `.env` value. Never put secrets in this file.
- Test before saying done. Say when something is a guess. Ask when intent is unclear. Keep it simple.
- Stack pins: Java 21, Spring Boot 4.1.3, Jackson 3 (`tools.jackson.*` only), Spring AI 2.0.3, Postgres 17,
  React/Vite/Tailwind. DB changes only as new sequential Flyway migrations (next: V30), RLS on every new table.
- `./mvnw test` must never call live Gemini or Supabase (fakes exist). Testcontainers Kafka stays `apache/kafka:3.8.0`
  (3.9.0 fails there; compose uses 3.9.0). No credential-shaped literals in tests. New Maven dependencies go in the LAST
  `</dependencies>` block. Keep `investors.owner_user_id`.
- Update this file after each task.

### Product decisions the user approved
- Investor lookup only through `InvestorMembershipService`. The owner column counts only for a firm with NO member rows,
  so a removed owner cannot return.
- Invite page cannot prefill the email (preview returns it masked); signup takes `inviteToken` and refuses other
  addresses. Demoted or removed members lose their open invites.
- Declined offer keeps `status = ACCEPTED` with `issuer_decision = DECLINED`; still counts for velocity and duplicate.
- Email confirmation stays OFF for local testing. Known risk, documented: whoever holds an invite link and registers the
  invited address first can join. Required before real users.

### Commands and traps
- Tests: `cd backend && ./mvnw test` (Docker needed). Coverage:
  `V=0.8.16; ./mvnw org.jacoco:jacoco-maven-plugin:${V}:prepare-agent test org.jacoco:jacoco-maven-plugin:${V}:report`
  (braces matter in zsh; old `target/site/jacoco` files go stale). Frontend: `cd frontend && npm run build`.
- A sandboxed agent needs permission for Docker, network and local ports.
- All MockMvc test requests are ADMIN by default; use `Auth.issuer/investor/analyst/anonymous`. Tests that commit rows
  must clean them up.
- Staff test account `admin@issuermatch.test` is real in Supabase (password in `backend/.env.admin`).
- Browser tests create real Supabase users. After a killed run: `scripts/e2e-cleanup.sh`.
- The Supabase Postgres database is separate from the compose one and is probably behind on migrations. Running the
  backend directly (`./mvnw spring-boot:run`) migrates it: ask the user first.
- An unrelated container `foodwaste-db` runs on this machine: leave it alone.
- The single-partition match consumer lets one slow run delay the next.
- More: README.md, docs/AUTH.md, docs/RISK.md, docs/SECURITY.md, SECURITY_AUDIT.md, INTERVIEW_NOTES.md.
