# Measured performance notes

All numbers come from tests in `backend/src/test/java/com/issuermatch/perf/`. Re-run them to reproduce.

## N+1 queries: `GET /investors?size=50` (NPlusOneTest)

Each investor response embeds its mandates (lazy collection).

| | SQL statements |
|---|---|
| Before (lazy load per investor) | **52** (1 page + 1 count + 50 mandate loads) |
| After (`@BatchSize(100)` on `Investor.mandates`) | **3** (1 page + 1 count + 1 `WHERE investor_id IN (...)`) |

Why not `JOIN FETCH`: fetching a collection with paging makes Hibernate paginate in memory (warning HHH90003004).

## Main matching query and `idx_mandates_match` (MandateIndexTest)

`WHERE sector = ? AND region = ? AND min_ticket <= ? AND max_ticket >= ?` on 200,000 mandates (Postgres 17, `EXPLAIN ANALYZE`):

| | Plan | Execution time |
|---|---|---|
| With `idx_mandates_match (sector, region, min_ticket, max_ticket)` | Bitmap Index Scan | **0.83 ms** |
| Without | Parallel Seq Scan, 3 workers | **17.9 ms** |

About 21x faster. Equality columns (sector, region) come first so Postgres narrows to one sector+region before applying the ticket range.
