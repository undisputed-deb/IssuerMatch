# Load test: `POST /commitments`

Reproduce: `scripts/load/run.sh` (needs Docker, k6, `backend/.env` and `backend/.env.admin`).

## What one request does
Verifies a real Supabase JWT (ES256), claims the idempotency key, locks the funding need and the investor, runs five risk rules,
reserves capacity, writes the commitment, its decisions and an audit row, and queues three events in the outbox.
All in one database transaction.

## Setup
- Apple M4 laptop, 10 cores, 16 GB. App (Java 21, packaged jar, default 10-connection pool), PostgreSQL 17 and Kafka 3.9
  (Docker, 10 CPUs, about 7.7 GB) and k6 2.4.0 all on the same machine.
- Local database, so there is no network latency to a hosted database. It never touches Supabase.
- Synthetic data: 20,000 investors, 200 funding needs. 50 virtual users, 20 s warm-up, then 60 s measured. Random amounts.
- 201 (accepted), 202 (flagged) and 422 (blocked by a rule) are all correct answers. Anything else counts as a failure.

## Results (one measured run each, 2026-11-25/26)

| | Before the publisher fix | After |
|---|---|---|
| Requests in 60 s | 38,760 | 37,168 |
| Throughput | 645 /s | **619 /s** |
| Latency median / p95 / p99 | 72 / 109 / 178 ms | 72 / **133** / 221 ms |
| Failed requests | 0 | **0** |
| Accepted / flagged / blocked | 35,608 / 3,146 / 6 | 34,094 / 3,071 / 3 |
| Outbox backlog when the load stopped | **105,334** events | **439** events |
| Event ledger caught up | not within 10 minutes (701 s elapsed when checked) | **27 s** after the load stopped |
| Reconciliation afterwards | 1,499 missing events | **0 mismatches** |
| Over-filled raises, capacity mismatches, duplicate writes | 0, 0, 0 | 0, 0, 0 |

## What the test found
The outbox publisher sent one Kafka message at a time, waited for its acknowledgement, then updated one row: roughly 180 events
per second against about 1,900 produced. Reconciliation correctly reported the events that had not arrived.
It also ran on Spring's single scheduler thread, so a publisher that never caught up would starve the stuck-run reaper and
the reconciliation job.

Fix: send a batch of 500 without waiting between messages (the idempotent producer keeps order per partition), wait for all
acknowledgements, mark the batch with one statement, give the scheduler four threads, and run three ledger consumer threads.

## Honest reading
- Request throughput did not improve. It dropped about 4% and p95 rose by about 24 ms, probably because the publisher and
  consumers now do their work during the test instead of falling behind. One run each cannot separate that from noise.
- Numbers describe this laptop with everything on one machine. They are not a production capacity claim.
- Not measured: hosted database latency, multiple app instances, long soak runs, contention on a single hot funding need.
