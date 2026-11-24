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

## In progress
- Waiting for user to commit tasks 1-6 (user will commit all together).

## Next steps
1. Tasks 7-10: Flyway migrations V2+ (issuers, investors, mandates, funding_needs, match_runs/results, audit_log). Add spring-boot-starter-data-jpa at entity task (11).

## Decisions & gotchas
- User commits manually; wait for "committed" after each task.
- Never print `.env` values. Names only.
- Clone hooks: `git config core.hooksPath .githooks`, needs `brew install gitleaks`.
- Repo root = /Users/Deb/Downloads/IssuerMatcher/IssuerMatch. Maven/Docker/Node installed (Java 21.0.8, Maven 3.9.11, Docker 28.5, Node 24).
- If DB connect fails (no IPv6), use Supabase pooler string; username format differs. Do not guess.
- Pins: Boot 4.1.3 + Spring AI 2.0.3 (2.0.x supports Boot 4.0/4.1; Boot 3.5 OSS support ended mid-2026). Boot 4 starters: spring-boot-starter-webmvc / -webmvc-test. Run tests: `cd backend && ./mvnw test`.
- Docker Desktop must be running for tests (`open -a Docker`). JVM Testcontainers could not reach socket inside tool sandbox earlier only because the engine was down; use dangerouslyDisableSandbox for test/run if socket errors persist.
- Run app locally: `cd backend && ./mvnw spring-boot:run` (reads .env from cwd).
