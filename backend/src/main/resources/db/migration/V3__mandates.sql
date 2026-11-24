-- A mandate = what an investor is willing to fund. One investor can have several.

CREATE TABLE mandates (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    investor_id uuid          NOT NULL REFERENCES investors (id) ON DELETE CASCADE,
    sector      text          NOT NULL CHECK (sector IN ('TECHNOLOGY','FINTECH','HEALTHCARE','ENERGY','REAL_ESTATE','CONSUMER','INDUSTRIALS')),
    region      text          NOT NULL CHECK (region IN ('NORTH_AMERICA','EUROPE','ASIA_PACIFIC','LATAM','MEA')),
    min_ticket  numeric(18,2) NOT NULL CHECK (min_ticket > 0),
    max_ticket  numeric(18,2) NOT NULL,
    risk_level  text          NOT NULL CHECK (risk_level IN ('LOW','MEDIUM','HIGH')),
    instruments text[]        NOT NULL CHECK (
                    cardinality(instruments) > 0
                    AND instruments <@ ARRAY['EQUITY','DEBT','CONVERTIBLE','MEZZANINE']),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT mandate_ticket_range CHECK (max_ticket >= min_ticket)
);

-- The main matching query: WHERE sector = ? AND region = ? AND min_ticket <= ? AND max_ticket >= ?
-- Equality columns first, then the range columns, so Postgres can narrow by sector+region before scanning tickets.
CREATE INDEX idx_mandates_match ON mandates (sector, region, min_ticket, max_ticket);
-- Foreign keys are not indexed automatically in Postgres. Needed for "all mandates of investor X" and cascade deletes.
CREATE INDEX idx_mandates_investor ON mandates (investor_id);

ALTER TABLE mandates ENABLE ROW LEVEL SECURITY;
