-- Risk rules are code (one class each) but their thresholds are data, so an admin can tune them at runtime.
-- No row = the rule's built-in defaults.
CREATE TABLE risk_rule_config (
    rule_id    text        PRIMARY KEY,
    enabled    boolean     NOT NULL DEFAULT true,
    params     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_by uuid,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Every rule result for every commitment, with the thresholds that were in force at that moment.
-- That snapshot is what makes a decision explainable later, even after the thresholds change.
CREATE TABLE risk_decisions (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    commitment_id uuid        NOT NULL REFERENCES commitments (id) ON DELETE CASCADE,
    rule_id       text        NOT NULL,
    verdict       text        NOT NULL CHECK (verdict IN ('PASS','FLAG','BLOCK')),
    reason        text        NOT NULL,
    params        jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
-- "Why was this commitment flagged?"
CREATE INDEX idx_decisions_commitment ON risk_decisions (commitment_id);

-- Four-eyes review of FLAGGED commitments.
ALTER TABLE commitments ADD COLUMN reviewed_by uuid;
ALTER TABLE commitments ADD COLUMN reviewed_at timestamptz;

ALTER TABLE risk_rule_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE risk_decisions   ENABLE ROW LEVEL SECURITY;
