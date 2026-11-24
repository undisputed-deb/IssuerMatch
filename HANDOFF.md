## Goal
IssuerMatch: Spring Boot + Kafka + Spring AI agent that ranks investors for issuer funding needs (synthetic data only).

## Done (all committed + pushed, author = user only, NO Co-Authored-By; commit+push after each task)
- Setup: .gitignore, backend/.env.example, gitleaks pre-commit (.githooks), Boot 4.1.3 skeleton, health, error format.
- DB: Flyway V1-V8 (issuers, investors, mandates, funding_needs, match_runs/results, audit_log, outbox+processed_events, investor_deals). CHECKs, FKs, commented indexes, RLS on every table (test guards it). Applied to real Supabase; synthetic seed loaded there (50 investors, 20 issuers, 32 needs).
- API (no auth yet): CRUD issuers, investors (+nested mandates), funding-needs (POST -> 202, GET list/one). Paged (max 100), safe sort (400 on bad field), unknown JSON fields rejected, DTOs only.
- Perf (docs/PERFORMANCE.md): N+1 52 -> 3 statements (@BatchSize); idx_mandates_match 17.9ms -> 0.83ms on 200k rows.
- Kafka: docker-compose.yml (Kafka 3.9.0 KRaft + UI on :8089, localhost-only). Topics funding-need.submitted, match.requested, match.completed, audit.events + .DLT for consumed ones.
- Outbox: OutboxService (MANDATORY tx) + OutboxPublisher (poll, SKIP LOCKED). ProcessedEvents guard. MatchRequestConsumer (funding-need.submitted -> match_run + match.requested outbox event), idempotent. Retry 3x backoff then <topic>.DLT (name pinned; Spring Kafka 4 default is -dlt). EventPipelineTest proves dup event = 1 run, poison -> DLT, failing event retried -> DLT.
- Agent building blocks: LlmClient interface (+ FakeLlmClient in tests), FitScorer (pure, 0-100, unit tested), MatchingTools with 4 read-only @Tool methods: searchInvestorsByMandate, getInvestorHistory, getMarketContext, scoreFit.
- Agent (tasks 29-32): SpringAiLlmClient (Gemini 2.5 flash via Spring AI, key from GEMINI_API_KEY), RunToolbox wraps the 4 tools (call budget 12, records every call), MatchAgent (60s timeout, untrusted description fenced + angle brackets escaped), ModelOutput validator (strict JSON, investors must come from this run's search AND be scored, citedTools must be real, max 5) with ONE repair retry then FAILED. MatchRunService persists: scores/ranks from Java (scoreFit), tool_calls jsonb, tokens, latency; status PENDING_REVIEW. MatchRunConsumer (match.requested): atomic claim REQUESTED->RUNNING = idempotency; no DB tx held during LLM call; AgentFailure -> FAILED run + match.completed event. MatchReviewService: approve/reject only from PENDING_REVIEW (409 otherwise), approve -> need MATCHED, reject -> need SUBMITTED. Endpoints: GET /match-runs, /match-runs/{id} (with toolCalls), /match-runs/{id}/results, POST /match-runs/{id}/approve|reject.
- Live run verified with the real key (Supabase data): PENDING_REVIEW, 3 tool calls, 4210 in / 318 out tokens, ~7s, injection text in description ignored.
- Fixes: retry shares ONE tool budget + ONE deadline (repair prompt carries earlier tool results); V9 started_at + StuckRunReaper (RUNNING > app.agent.stuck-after=5m -> FAILED + match.completed); complete()/fail() only act on RUNNING runs (late result cannot resurrect a reaped run).
- Task 33: V10 commitments + idempotency_keys. POST /commitments needs Idempotency-Key header (^[A-Za-z0-9._:-]{1,128}$). IdempotencyService: key row inserted in SAME tx as the work; concurrent same-key callers block on the unique index then replay (header Idempotent-Replayed: true); same key + different body -> 422; failed work rolls the key back (retry allowed); hash is over a canonical string (whitespace/500000 vs 500000.00 equal). Only investors in an APPROVED run's results can commit (409 otherwise). Test: 50 parallel identical requests -> 1 row; mutation-checked (random key per request makes it fail).
- Phase 4 auth DONE (163 tests green): security pkg. SecurityConfig = stateless JWT resource server; JwtDecoder from SUPABASE_JWKS_URI, ES256+RS256 only (project key is ES256 P-256), iss = SUPABASE_URL/auth/v1, aud=authenticated; app.auth.* has NO defaults (fail fast). Roles from app_metadata.roles only (SupabaseJwtConverter). CurrentUser.get() = caller. 401/403 in our JSON format (entry point + GlobalExceptionHandler). Default deny; only /health public. Role gates (@PreAuthorize) on controllers, ownership in services (issuer gets 404 for others' data). Matrix in docs/AUTH.md. V11: idempotency_keys PK (actor, key), commitments.created_by, match_runs.requested_by; reviewed_by set from token. RunToolbox.Policy: scoreFit bound to run's need (prompt-injection IDOR guard). AuditService (audit_log + audit.events via outbox, same tx, clock_timestamp, socket IP only, no free text) on issuer CRUD, need submit, approve/reject, commitment create.
- Live smoke vs real Supabase: V9-V11 applied; no token/garbage/real anon key -> 401. NOT yet tested with a real user token (needs a user + roles in dashboard).
- 119 tests green (before auth).

## In progress
- nothing

## Next steps
1. Task 29+: agent loop. Add Spring AI Google GenAI starter (`spring-ai-starter-model-google-genai`, BOM 2.0.3 already imported), `SpringAiLlmClient implements LlmClient` using ChatClient + tools, key from GEMINI_API_KEY (tests need a dummy key or exclude autoconfig). Max iterations + timeout, structured JSON output validated (retry once, then fail safely), persist tool calls/results/tokens/latency on match_runs, results -> PENDING_REVIEW, approve/reject endpoints. match.requested consumer runs the agent (idempotent), emits match.completed.
2. Tool calls must be logged per run (tool_calls jsonb on match_runs). Tools are read-only; allowlist test.
3. Then Phase 3B (commitments, idempotency keys, risk rules, reconciliation, k6), Phase 4 auth (Supabase JWT via JWKS, roles in app_metadata), Phase 5 security, 6 audit, 7 frontend, 8 observability, 9 docs.

## Decisions & gotchas
- Never print .env values. Names only. SUPABASE_SECRET_KEY unused.
- Tests: Docker Desktop must run (`open -a Docker`); use dangerouslyDisableSandbox for mvn test/run. Test Kafka image must be apache/kafka:3.8.0 (3.9.0 fails in Testcontainers: advertised.listeners 0.0.0.0). Compose uses 3.9.0 fine.
- Tests share one DB container per JVM: tests that COMMIT (EventPipelineTest) leave rows; other tests must use relative counts.
- Run app: `cd backend && ./mvnw spring-boot:run` (reads backend/.env). Seed: add `-Dspring-boot.run.profiles=seed` (idempotent; slow over network, wait for "Synthetic data created").
- Tests: every MockMvc request is ADMIN by default (TestInfraConfig.adminByDefault). Use .with(Auth.issuer(uuid)/Auth.analyst()/Auth.noRoles()/Auth.anonymous()) to test other callers. Tokens go through the real converter; signature checks have their own test (JwtValidationTest, local EC key).
- Maven gotcha: new <dependency> blocks must go in the LAST </dependencies> (the first is inside dependencyManagement).
- Tests never call Gemini: IntegrationTest sets a dummy key and TestInfraConfig provides a @Primary lenient FakeLlmClient (use fake.when(predicate, turn) keyed on the prompt). Tests that COMMIT rows must clean up after themselves (MatchFlowTest, MatchAgentRetryTest do).
- Needs Kafka up for the app to be quiet: `docker compose up -d kafka`.
- Boot 4: Jackson 3 (tools.jackson.*), starters spring-boot-starter-webmvc/-kafka/-flyway, test annotations in org.springframework.boot.webmvc.test.autoconfigure.
- Auth not built: issuer ownerUserId is in the request body until Phase 4 (then from JWT).
- If DB connect fails (no IPv6) use Supabase pooler string; username format differs. Do not guess. Direct host works on this machine.
- Pins: Boot 4.1.3, Spring AI 2.0.3 (spring-ai-model only so far), Postgres 17, Java 21.
