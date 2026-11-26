-- Removes everything the browser tests create: rows named 'E2E%' (older runs: 'Browser test%') and whatever hangs off
-- them. Safe to run twice.
-- The audit log is append-only history and stays.
BEGIN;

CREATE TEMP TABLE e2e_investors ON COMMIT DROP AS
    SELECT id FROM investors WHERE name LIKE ANY (ARRAY['E2E%', 'Browser test%']);
CREATE TEMP TABLE e2e_needs ON COMMIT DROP AS
    SELECT n.id FROM funding_needs n JOIN issuers s ON s.id = n.issuer_id
    WHERE n.title LIKE ANY (ARRAY['E2E%', 'Browser test%']) OR s.name LIKE ANY (ARRAY['E2E%', 'Browser test%']);
CREATE TEMP TABLE e2e_commitments ON COMMIT DROP AS
    SELECT id, need_id, amount, status, issuer_decision FROM commitments
    WHERE need_id IN (SELECT id FROM e2e_needs) OR investor_id IN (SELECT id FROM e2e_investors);

-- capacity a test investor holds on a real need goes back to it
UPDATE funding_needs n SET remaining_capacity = n.remaining_capacity + held.amount, version = n.version + 1
FROM (SELECT need_id, sum(amount) AS amount FROM e2e_commitments
      WHERE status IN ('ACCEPTED', 'PENDING_REVIEW') AND issuer_decision <> 'DECLINED' GROUP BY need_id) AS held
WHERE n.id = held.need_id AND n.id NOT IN (SELECT id FROM e2e_needs);

-- events still waiting to be published would recreate ledger rows for deleted commitments
DELETE FROM outbox o USING e2e_commitments c WHERE o.published_at IS NULL AND position(c.id::text IN o.payload) > 0;
DELETE FROM commitment_events
    WHERE need_id IN (SELECT id FROM e2e_needs) OR investor_id IN (SELECT id FROM e2e_investors);
DELETE FROM commitments WHERE id IN (SELECT id FROM e2e_commitments);          -- cascades risk_decisions
DELETE FROM match_results WHERE investor_id IN (SELECT id FROM e2e_investors); -- also from runs on real needs
DELETE FROM funding_needs WHERE id IN (SELECT id FROM e2e_needs);              -- cascades runs, results, questions
DELETE FROM issuers WHERE name LIKE ANY (ARRAY['E2E%', 'Browser test%']);
DELETE FROM investors WHERE id IN (SELECT id FROM e2e_investors);              -- cascades mandates, questions

COMMIT;
