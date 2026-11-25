-- Results of comparing commitments, need capacity and the event ledger.
CREATE TABLE reconciliation_runs (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at    timestamptz,
    mismatch_count integer     NOT NULL DEFAULT 0,
    triggered_by   uuid                       -- null = the scheduled job
);
CREATE INDEX idx_reconciliation_started ON reconciliation_runs (started_at DESC);

CREATE TABLE reconciliation_mismatches (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id        uuid NOT NULL REFERENCES reconciliation_runs (id) ON DELETE CASCADE,
    type          text NOT NULL CHECK (type IN
                  ('CAPACITY_MISMATCH','MISSING_EVENT','DUPLICATE_EVENT','UNEXPECTED_EVENT','AMOUNT_MISMATCH')),
    need_id       uuid,
    commitment_id uuid,
    detail        text NOT NULL
);
CREATE INDEX idx_mismatches_run ON reconciliation_mismatches (run_id);

ALTER TABLE reconciliation_runs       ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_mismatches ENABLE ROW LEVEL SECURITY;
