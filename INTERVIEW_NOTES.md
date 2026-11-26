# Interview notes

Three sentences per decision, then likely questions. Read the linked code before an interview so you can open it.

## Decisions

**Transactional outbox** (`messaging/OutboxService`, `OutboxPublisher`).
I write each event into a table inside the same transaction as the business change, and a poller sends it to Kafka.
Publishing directly could save the data and lose the event, or send an event for data that rolled back.
The price is at-least-once delivery, so every consumer has to tolerate duplicates.

**Idempotent consumers** (`ProcessedEvents`, `MatchRequestConsumer`).
A consumer inserts the event id into `processed_events` in the same transaction as its work.
A redelivery hits the primary key and gets skipped, and a failed transaction removes the marker so the retry runs.
The match run also has `UNIQUE(need_id, event_id)` as a second guard.

**Score in Java** (`matching/scoring/FitScorer`).
The model chooses tools and writes explanations, and a pure function computes the number.
That makes rankings repeatable and testable, and a prompt injection cannot change a score.
I check that each ranked investor came from a search in that run and was scored.

**Agent limits** (`RunToolbox`, `MatchAgent`, `ModelOutput`).
The model gets four read-only tools, 12 calls and one deadline shared with its single repair retry.
Its JSON must match a strict shape, cite tools that ran and contain no markup or links.
When anything fails, the run ends as `FAILED` with a generic message and the need goes back to `SUBMITTED`.

**Prompt injection** (`PromptInjectionDetector`, `MatchAgent.userPrompt`).
I treat the funding text as data: fenced, angle brackets escaped, and never able to choose tools or permissions.
A pattern detector flags suspicious text for the reviewer, and I know patterns can be evaded.
The protection that holds is structural: read-only tools, need-bound scoring and human approval.

**Human in the loop** (`MatchReviewService`, `CommitmentService.review`).
Results wait for an analyst, and nothing the agent can call changes data.
Flagged commitments need a second person, because the one who recorded it cannot approve it.
Both reviews are a conditional update, so two clicks cannot both win.

**Idempotency keys** (`IdempotencyService`).
The key row is inserted in the same transaction as the commitment, so a concurrent duplicate blocks on the unique index
and then replays the saved response. The same key with a different body returns 422, and keys are scoped per user.
I proved it with 50 parallel requests and broke the logic on purpose to see the test fail.

**Concurrency** (`CommitmentService.doCreate`).
Each commitment locks the funding need, then the investor, before any rule runs.
I first locked the need alone, and a test showed 10 of 15 parallel requests slipping past the velocity limit.
A fixed lock order rules out deadlock, and a `CHECK` constraint backs up the capacity update.

**Risk engine** (`commitment/risk`).
Each rule is one class behind `RiskRule`, and admins change thresholds at runtime without a deploy.
Every decision stores the thresholds in force, so I can explain an old decision after the limits change.
A rule that crashes counts as a flag, never a pass.

**Reconciliation** (`ReconciliationService`).
A job compares commitments, remaining capacity and an event ledger built only from Kafka.
It reports five kinds of mismatch and repairs nothing, because a person has to decide which record is right.
During the load test it flagged the events my slow publisher had not delivered.

**Dead letters** (`deadletter`).
Failed messages land in a table with the original payload and root cause.
An admin replays each one once, through the outbox, into an allowlisted topic.
The payload keeps its event id, so consumers that already handled it skip it.

**Authentication** (`security/SecurityConfig`).
Supabase issues JWTs and the API checks signature, issuer, audience, expiry and algorithm.
My project signs with ES256, and Spring's default decoder accepts RS256 alone, so I pinned the algorithms.
Roles come from `app_metadata`, which users cannot edit.

**Authorization and IDOR** (`IssuerService`, `FundingNeedService`).
Controllers check roles and services check ownership next to the query.
An issuer asking for another company's data gets 404, the same as a missing id.
Tests cover reading, changing, deleting and submitting across two users.

**Performance** (`docs/PERFORMANCE.md`, `docs/LOAD_TEST.md`).
I fixed an N+1 from 52 statements to 3 with batch fetching, because a join fetch breaks paging.
The load test showed the outbox publisher sending about 180 events per second, so I batched the sends.
The backlog fell from 105,334 to 439, while request latency rose a little.

## 15 likely questions

1. **Why Kafka instead of calling the agent in the request?** A model call takes seconds and can fail. The API answers 202 at once and a consumer retries or dead-letters the work.
2. **Is your delivery exactly once?** No. It is at least once with idempotent consumers, which gives one effect per event.
3. **What happens if Kafka is down?** Submissions still return 202 and wait in the outbox. `chaos.sh` stops the broker and shows the event processed after it returns.
4. **What if the app dies during a model call?** The run stays `RUNNING` until a reaper fails it after five minutes and releases the need. A late result cannot revive it.
5. **Why is the agent not one database transaction?** It would hold a connection for up to a minute. An atomic claim from `REQUESTED` to `RUNNING` gives idempotency instead.
6. **How do you stop the model inventing investors?** The id must appear in this run's search results and have a `scoreFit` call. The database also has a foreign key.
7. **Optimistic or pessimistic locking?** Pessimistic row locks, because several rules read history and need it stable. The need row also keeps a version column that capacity changes bump.
8. **How would this scale to several instances?** The outbox uses `SKIP LOCKED` and consumers share a group. Rate limits would need Redis, and topics need more partitions.
9. **Where could you still lose data?** Dead letters are copied from Kafka, so a long database outage past Kafka retention would lose them. The copier retries without limit to narrow that window.
10. **What did your tests miss?** The load test found the publisher bottleneck, the failure script found the 30 second hang, and the browser test found a page that never refreshed.
11. **Why 404 and not 403 for another user's data?** A 403 confirms the id exists.
12. **Why not store roles in your own table?** Supabase `app_metadata` is already server controlled and travels in the token. The trade-off is that a removed role lasts until the token expires.
13. **How do you test without paying for the model?** An `LlmClient` interface with a scripted fake that calls the real tools. One browser test uses real Gemini.
14. **What is the weakest part?** Injection detection is pattern based, and rate limits are per instance.
15. **What would you do next?** Shared rate limiting, TLS and ACLs on Kafka, tracing across services and an independent security review.

## Resume bullets (measured numbers only)
- Built an agentic investor-matching service (Java 21, Spring Boot, Kafka, Spring AI) with 37 REST endpoints and 281 integration and unit tests at 94.6% line coverage.
- Designed a retry-safe commitment API with idempotency keys, row locking and a runtime-tunable risk engine, sustaining about 620 requests/s at p95 133 ms with zero errors in a single-laptop load test.
- Found and fixed an event-publishing bottleneck through load testing, cutting the post-load backlog from 105,334 events to 439.
- Hardened the system against IDOR, JWT forgery and prompt injection, and fixed 7 dependency vulnerabilities (4 high) found by SBOM scanning in CI.
- Cut a list endpoint from 52 SQL statements to 3 and the core matching query from 17.9 ms to 0.83 ms on 200,000 rows.
