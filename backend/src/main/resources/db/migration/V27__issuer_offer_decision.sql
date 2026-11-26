-- The issuer's answer to an offer that passed risk rules or review. status stays ACCEPTED (what risk and staff decided);
-- a DECLINED offer no longer holds any capacity of the raise.
ALTER TABLE commitments
    ADD COLUMN issuer_decision   text        NOT NULL DEFAULT 'PENDING' CHECK (issuer_decision IN ('PENDING','ACCEPTED','DECLINED')),
    ADD COLUMN issuer_decided_at timestamptz,
    ADD COLUMN issuer_decided_by uuid,
    ADD CONSTRAINT commitment_issuer_decision_complete CHECK (
        (issuer_decision = 'PENDING') = (issuer_decided_at IS NULL) AND (issuer_decision = 'PENDING') = (issuer_decided_by IS NULL)),
    ADD CONSTRAINT commitment_issuer_decides_accepted_only CHECK (issuer_decision = 'PENDING' OR status = 'ACCEPTED');

-- The event ledger learns the new outcome.
ALTER TABLE commitment_events DROP CONSTRAINT commitment_events_type_check;
ALTER TABLE commitment_events ADD CONSTRAINT commitment_events_type_check
    CHECK (type IN ('REQUESTED','ACCEPTED','REJECTED','DECLINED'));

ALTER TABLE commitments       ENABLE ROW LEVEL SECURITY;
ALTER TABLE commitment_events ENABLE ROW LEVEL SECURITY;
