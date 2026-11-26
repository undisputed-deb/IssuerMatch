-- An investor's offer is a commitment with extra context. A pass has no amount, so it lives on the match result.
ALTER TABLE commitments
    ADD COLUMN proposed_stake_pct   numeric(6,3) CHECK (proposed_stake_pct > 0 AND proposed_stake_pct <= 100),  -- equity only
    ADD COLUMN proposed_terms_json  jsonb        CHECK (jsonb_typeof(proposed_terms_json) = 'object'),
    ADD COLUMN investment_rationale text         CHECK (char_length(investment_rationale) <= 5000),  -- "why we like this company"
    ADD COLUMN investor_decision    text         CHECK (investor_decision = 'INTERESTED');           -- null: recorded by staff

ALTER TABLE match_results
    ADD COLUMN investor_decision text CHECK (investor_decision IN ('INTERESTED','PASSED')),
    ADD COLUMN pass_reason       text CHECK (char_length(pass_reason) <= 1000),
    ADD COLUMN decided_at        timestamptz,
    ADD CONSTRAINT match_results_pass_reason_only_when_passed CHECK (pass_reason IS NULL OR investor_decision = 'PASSED');
