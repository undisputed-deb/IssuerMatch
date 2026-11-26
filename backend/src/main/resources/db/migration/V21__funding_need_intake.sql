-- Richer funding-need intake. Everything new has a default or is optional, so existing rows stay valid.
ALTER TABLE funding_needs
    ADD COLUMN currency          text          NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'),  -- currency of amount
    ADD COLUMN company_stage     text          CHECK (company_stage IN
                                     ('PRE_SEED','SEED','SERIES_A','SERIES_B_PLUS','GROWTH','MIDDLE_MARKET')),
    ADD COLUMN target_close_date date,
    ADD COLUMN use_of_funds      jsonb         NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(use_of_funds) = 'array'),
    ADD COLUMN min_ticket_size   numeric(18,2),
    ADD COLUMN hard_constraints  jsonb         NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(hard_constraints) = 'object'),
    ADD COLUMN instrument_terms  jsonb         CHECK (instrument_terms IS NULL OR jsonb_typeof(instrument_terms) = 'object'),
    ADD CONSTRAINT need_min_ticket_range CHECK (min_ticket_size IS NULL OR (min_ticket_size > 0 AND min_ticket_size <= amount));

ALTER TABLE funding_needs ENABLE ROW LEVEL SECURITY;
