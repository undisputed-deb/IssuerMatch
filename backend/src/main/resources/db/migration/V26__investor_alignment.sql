-- How an investor takes part in rounds, whether it is investing right now, and which company stages each mandate targets.
ALTER TABLE investors
    ADD COLUMN participation_role text    CHECK (participation_role IN ('LEAD','CO_LEAD','FOLLOW_ON')),  -- null: not said
    ADD COLUMN deployment_status  text    NOT NULL DEFAULT 'ACTIVE' CHECK (deployment_status IN ('ACTIVE','PAUSED')),
    ADD COLUMN verified           boolean NOT NULL DEFAULT false;

-- Same values as funding_needs.company_stage. Empty: not said.
ALTER TABLE mandates
    ADD COLUMN target_stages jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (
        jsonb_typeof(target_stages) = 'array'
        AND target_stages <@ '["PRE_SEED","SEED","SERIES_A","SERIES_B_PLUS","GROWTH","MIDDLE_MARKET"]'::jsonb);

ALTER TABLE investors ENABLE ROW LEVEL SECURITY;
ALTER TABLE mandates  ENABLE ROW LEVEL SECURITY;
