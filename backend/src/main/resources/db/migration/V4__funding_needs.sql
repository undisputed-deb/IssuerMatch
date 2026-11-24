-- A funding need = an issuer asking for money. remaining_capacity shrinks as commitments are accepted (Phase 3B).

CREATE TABLE funding_needs (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer_id          uuid          NOT NULL REFERENCES issuers (id) ON DELETE CASCADE,
    title              text          NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    description        text          NOT NULL CHECK (char_length(description) <= 5000),  -- untrusted text, capped
    sector             text          NOT NULL CHECK (sector IN ('TECHNOLOGY','FINTECH','HEALTHCARE','ENERGY','REAL_ESTATE','CONSUMER','INDUSTRIALS')),
    region             text          NOT NULL CHECK (region IN ('NORTH_AMERICA','EUROPE','ASIA_PACIFIC','LATAM','MEA')),
    amount             numeric(18,2) NOT NULL CHECK (amount > 0),
    instrument         text          NOT NULL CHECK (instrument IN ('EQUITY','DEBT','CONVERTIBLE','MEZZANINE')),
    risk_level         text          NOT NULL CHECK (risk_level IN ('LOW','MEDIUM','HIGH')),
    status             text          NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','MATCHING','MATCHED','CLOSED')),
    remaining_capacity numeric(18,2) NOT NULL,
    version            bigint        NOT NULL DEFAULT 0,  -- optimistic locking (@Version)
    created_at         timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT need_capacity_range CHECK (remaining_capacity >= 0 AND remaining_capacity <= amount)
);

-- "My funding needs, newest first": the list endpoint, always scoped to an issuer.
CREATE INDEX idx_needs_issuer_created ON funding_needs (issuer_id, created_at DESC);
-- Workers looking for needs in a given state.
CREATE INDEX idx_needs_status ON funding_needs (status);

ALTER TABLE funding_needs ENABLE ROW LEVEL SECURITY;
