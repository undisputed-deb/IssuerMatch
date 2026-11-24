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
- 71 tests green.

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
- Needs Kafka up for the app to be quiet: `docker compose up -d kafka`.
- Boot 4: Jackson 3 (tools.jackson.*), starters spring-boot-starter-webmvc/-kafka/-flyway, test annotations in org.springframework.boot.webmvc.test.autoconfigure.
- Auth not built: issuer ownerUserId is in the request body until Phase 4 (then from JWT).
- If DB connect fails (no IPv6) use Supabase pooler string; username format differs. Do not guess. Direct host works on this machine.
- Pins: Boot 4.1.3, Spring AI 2.0.3 (spring-ai-model only so far), Postgres 17, Java 21.
