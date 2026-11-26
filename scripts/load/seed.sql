-- Synthetic load-test data: 20,000 investors, 200 funding needs, each investor approved for exactly one need.
-- Many investors so that normal traffic does not trip the velocity rule; amounts are random per request.
INSERT INTO investors(name, investor_type, region) SELECT 'Load Investor ' || g, 'VC', 'EUROPE' FROM generate_series(1, 20000) g;
INSERT INTO mandates(investor_id, sector, region, min_ticket, max_ticket, risk_level, instruments)
SELECT id, 'FINTECH', 'EUROPE', 1, 1000000000, 'MEDIUM', ARRAY['EQUITY'] FROM investors;
INSERT INTO issuers(name, sector, region, owner_user_id)
SELECT 'Load Issuer ' || g, 'FINTECH', 'EUROPE', gen_random_uuid() FROM generate_series(1, 200) g;
INSERT INTO funding_needs(issuer_id, title, description, sector, region, amount, instrument, risk_level, status, remaining_capacity)
SELECT id, 'Load need', 'synthetic', 'FINTECH', 'EUROPE', 10000000000, 'EQUITY', 'MEDIUM', 'MATCHED', 10000000000 FROM issuers;
INSERT INTO match_runs(need_id, event_id, status) SELECT id, gen_random_uuid(), 'APPROVED' FROM funding_needs;
WITH n AS (SELECT m.id AS run_id, row_number() OVER (ORDER BY m.id) - 1 AS k FROM match_runs m),
     i AS (SELECT id, row_number() OVER (ORDER BY id) - 1 AS k FROM investors)
INSERT INTO match_results(run_id, investor_id, rank, score, explanation)
SELECT n.run_id, i.id, (i.k / 200) + 1, 100, 'load' FROM i JOIN n ON n.k = i.k % 200;
ANALYZE;
