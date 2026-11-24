## Goal
IssuerMatch: Spring Boot + Kafka + Spring AI agent that ranks investors for issuer funding needs (synthetic data only).

## Done
- Phase 0 plan approved (in chat history; 46-step build order).
- Task 1: repo cloned to IssuerMatcher/IssuerMatch, `.gitignore`, `backend/.env.example`, real `backend/.env` ignored (verified).
- Task 2: gitleaks pre-commit hook in `.githooks/` (verified blocks a fake GitHub token).
- Task 3: Spring Boot 4.1.3 skeleton in backend/ (pom, main class, context test passes). Spring AI BOM 2.0.3 NOT added yet (add in agent phase).
- Task 4: GET /health -> {"status":"UP"}.
- Task 5: error format `{success:false,error:{code,message}}` via GlobalExceptionHandler (extends ResponseEntityExceptionHandler; ApiException/NotFoundException; generic 500 message). 6 tests.
- Task 6: Flyway + Postgres driver; DB creds from backend/.env via `spring.config.import=optional:file:.env[.properties]`; V1__baseline.sql applied to real Supabase (direct host works, PG 17.12). Tests use Testcontainers (postgres:17-alpine), 8 tests green. Custom `@IntegrationTest` annotation in src/test/.../support.
- Tasks 7-10: Flyway V2-V6 (issuers/investors, mandates, funding_needs, match_runs/results, audit_log) with CHECKs, FKs, indexes (each commented), RLS on (anon key sees nothing). SchemaTest (10 tests). Applied to real Supabase.

## In progress
- Waiting for user to commit nothing. All committed and pushed through task 10 (commit + push after each task, author = user only, NO Co-Authored-By).

## Next steps
1. Task 11-14: add spring-boot-starter-data-jpa; entities + repos + DTOs + CRUD for issuers, investors, mandates, funding needs (never return entities; Bean Validation; reject unknown JSON).
2. Task 15: pagination on all lists. Task 16: synthetic seed (50 investors, 20 issuers). Task 17: N+1 fix + EXPLAIN on idx_mandates_match.
3. Then Phase 2 Kafka.

## Decisions & gotchas
- User commits manually; wait for "committed" after each task.
- Never print `.env` values. Names only.
- Clone hooks: `git config core.hooksPath .githooks`, needs `brew install gitleaks`.
- Repo root = /Users/Deb/Downloads/IssuerMatcher/IssuerMatch. Maven/Docker/Node installed (Java 21.0.8, Maven 3.9.11, Docker 28.5, Node 24).
- If DB connect fails (no IPv6), use Supabase pooler string; username format differs. Do not guess.
- Pins: Boot 4.1.3 + Spring AI 2.0.3 (2.0.x supports Boot 4.0/4.1; Boot 3.5 OSS support ended mid-2026). Boot 4 starters: spring-boot-starter-webmvc / -webmvc-test. Run tests: `cd backend && ./mvnw test`.
- Docker Desktop must be running for tests (`open -a Docker`). JVM Testcontainers could not reach socket inside tool sandbox earlier only because the engine was down; use dangerouslyDisableSandbox for test/run if socket errors persist.
- Run app locally: `cd backend && ./mvnw spring-boot:run` (reads .env from cwd).
