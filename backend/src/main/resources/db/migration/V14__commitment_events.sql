-- The event ledger: what Kafka says happened to commitments. Built ONLY from consumed events, on purpose without
-- foreign keys, so it is an independent record the reconciliation job can compare against the commitments table.
CREATE TABLE commitment_events (
    event_id      uuid          PRIMARY KEY,  -- redelivery of the same event is a no-op
    commitment_id uuid          NOT NULL,
    need_id       uuid          NOT NULL,
    investor_id   uuid          NOT NULL,
    amount        numeric(18,2) NOT NULL CHECK (amount > 0),
    type          text          NOT NULL CHECK (type IN ('REQUESTED','ACCEPTED','REJECTED')),
    reason        text,
    occurred_at   timestamptz   NOT NULL,
    recorded_at   timestamptz   NOT NULL DEFAULT now()
);
-- Reconciliation groups by commitment and sums by need.
CREATE INDEX idx_commitment_events_commitment ON commitment_events (commitment_id, type);
CREATE INDEX idx_commitment_events_need ON commitment_events (need_id, type);

ALTER TABLE commitment_events ENABLE ROW LEVEL SECURITY;
