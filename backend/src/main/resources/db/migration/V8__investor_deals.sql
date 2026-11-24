-- Past deals an investor has done (synthetic). Read by the agent's getInvestorHistory tool.
CREATE TABLE investor_deals (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    investor_id uuid          NOT NULL REFERENCES investors (id) ON DELETE CASCADE,
    issuer_name text          NOT NULL CHECK (char_length(issuer_name) BETWEEN 1 AND 200),
    sector      text          NOT NULL CHECK (sector IN ('TECHNOLOGY','FINTECH','HEALTHCARE','ENERGY','REAL_ESTATE','CONSUMER','INDUSTRIALS')),
    region      text          NOT NULL CHECK (region IN ('NORTH_AMERICA','EUROPE','ASIA_PACIFIC','LATAM','MEA')),
    amount      numeric(18,2) NOT NULL CHECK (amount > 0),
    instrument  text          NOT NULL CHECK (instrument IN ('EQUITY','DEBT','CONVERTIBLE','MEZZANINE')),
    closed_on   date          NOT NULL
);
-- "Most recent deals of investor X": filter by investor, newest first, no sort step needed.
CREATE INDEX idx_deals_investor_closed ON investor_deals (investor_id, closed_on DESC);

ALTER TABLE investor_deals ENABLE ROW LEVEL SECURITY;
