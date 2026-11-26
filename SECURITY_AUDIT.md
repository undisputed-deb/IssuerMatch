# Security audit

Date: 2026-11-26. Scope: backend API, Kafka consumers, agent, database schema, dependencies. All data is synthetic.
Method: automated attack tests, failure injection against real Postgres and Kafka (`scripts/chaos.sh`), an SBOM
vulnerability scan (`scripts/dependency-scan.sh`) and the load test. The author of the code audited it, so treat this
as a self-review: an independent tester may find more.

## Findings
Severity reflects impact on this application. Each row names the fix commit and how I re-tested it.

| # | Severity | Finding | Fix | Re-test result |
|---|---|---|---|---|
| 1 | High | 7 known vulnerabilities (4 High) in transitive libraries: guava, snappy-java, lz4-java. A plain `pom.xml` scan reported "no issues" because it skipped BOM-managed versions. | `e9c9c2c` pins fixed versions, scans a generated SBOM | Scan: 0 findings |
| 2 | High | Parallel commitments from one investor to different needs slipped under velocity, duplicate and concentration limits: 10 of 15 passed cleanly where 4 should have. | `fa4ccf6` locks the investor row | `CommitmentVelocityRaceTest`: exactly 4 pass, 5 flagged, 6 blocked |
| 3 | Medium | A crafted `sort` parameter made the data layer throw: HTTP 500. No injection: the query never ran. | `d2ffdfb` validates sort and page | `HostileInputTest`: 400 |
| 4 | Medium | A huge `page` number overflowed the query offset: HTTP 500. | `d2ffdfb` | `HostileInputTest`: 400 |
| 5 | Medium | No request size limit. A 2 MB body was read and parsed before validation refused it. | `ee094a5` 64 KB cap | 413 for declared and chunked bodies |
| 6 | Medium | Database errors logged the offending row, which can hold names or funding text. | `186d706` logs SQLState and constraint only, disables server error detail | `LogHygieneTest`: marker absent from logs |
| 7 | Medium | Database down: requests hung for 30 s and returned a generic 500. | `f861946` 5 s timeout, 503 with Retry-After | `chaos.sh`: 503 after 5 s, recovers by itself |
| 8 | Medium | No rate limit. One user could start unlimited paid model calls. | `07eae18` token buckets per IP, per user, per model call and a global ceiling | `RateLimitTest`: 429 with Retry-After, forged `X-Forwarded-For` ignored |
| 9 | Low | NUL characters reached the database and came back as a misleading 409. | `13d7657` rejects them at parsing, for HTTP, Kafka and model output | `HostileInputTest`: 400 |
| 10 | Low | No CORS policy and only default security headers. | `5fdcdb1` one allowed origin, CSP, HSTS, frame, referrer and permissions headers | `CorsAndHeadersTest` |
| 11 | Low | Injection attempts in funding text were contained but invisible to reviewers, and model text could carry markup or links. | `74fc406` flags the run, rejects active content | `PromptInjectionDetectorTest`, `MatchFlowTest`, `ModelOutputTest` |
| 12 | Low | A failed match run left its funding need stuck, with no way to retry. | `8d20bab` | `MatchFlowTest` |
| 13 | Info | Credential-shaped strings in tests triggered a GitGuardian alert. No real secret was ever committed. | `bfec99a` builds them at runtime | gitleaks full history: 0 findings |

## Attacks tried that the application already withstood
| Attack | Result | Test |
|---|---|---|
| SQL injection in bodies, path, filters and dead-letter topic | Treated as text, tables intact | `HostileInputTest` |
| User A reads, changes or deletes user B's issuer or need (IDOR) | 404, indistinguishable from a missing id | `AuthorizationTest` |
| Expired, wrong issuer, wrong audience, tampered, `alg: none`, HS256-confusion and foreign-key JWTs | 401 | `JwtValidationTest` |
| Same Kafka event delivered twice | Processed once | `EventPipelineTest`, `CommitmentEventsTest` |
| Poison and malformed Kafka messages | Dead-lettered, original payload kept | `EventPipelineTest`, `DeadLetterTest` |
| 50 identical commitment requests at once | One commitment | `CommitmentApiTest` |
| Two investors race for the last capacity | One wins | `CommitmentCapacityTest` |
| Injection asking the agent to list all investors, call other tools or read another need | Tools unchanged, cross-need `scoreFit` refused | `MatchFlowTest`, `MatchAgentLoopTest` |
| Agent loops on tools, model hangs, model returns bad JSON or invented investors | Capped, timed out, retried once, failed safely | `MatchAgent*Test`, `ModelOutputTest` |
| Model provider rejects the key | Run `FAILED`, need released, generic stored message | `chaos.sh` |
| Kafka down during submission | 202, event waits in the outbox, processed after recovery | `chaos.sh` |
| Secrets in logs after failure injection | None found for the Gemini key, database password or access token | `chaos.sh` |

## Accepted risks and open items
| Item | Why it stays | What would fix it |
|---|---|---|
| Rate limits are per instance | One instance in this project | Shared store (Bucket4j with Redis) |
| Injection detection is pattern based and evadable | The structural defences do not depend on it | A classifier model as a second signal |
| Kafka has no TLS or SASL locally | Bound to 127.0.0.1, synthetic data | TLS, SASL and per-topic ACLs in production |
| Admin accounts have no enforced second factor | Supabase project setting | Require MFA and check the `aal` claim |
| Roles stay valid until the token expires (about 1 hour) after removal | Stateless tokens | Shorter token lifetime or a revocation check |
| No independent penetration test | Portfolio project | External review |
