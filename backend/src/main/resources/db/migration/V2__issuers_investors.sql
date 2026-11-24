-- Issuers raise money, investors provide it. All data is synthetic.
-- Allowed values are enforced with CHECK so bad data cannot get in even if the API has a bug.

CREATE TABLE issuers (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name          text        NOT NULL UNIQUE CHECK (char_length(name) BETWEEN 1 AND 200),
    sector        text        NOT NULL CHECK (sector IN ('TECHNOLOGY','FINTECH','HEALTHCARE','ENERGY','REAL_ESTATE','CONSUMER','INDUSTRIALS')),
    region        text        NOT NULL CHECK (region IN ('NORTH_AMERICA','EUROPE','ASIA_PACIFIC','LATAM','MEA')),
    owner_user_id uuid        NOT NULL,  -- Supabase auth user id; used for ownership (IDOR) checks
    created_at    timestamptz NOT NULL DEFAULT now()
);
-- "Show me my issuers": every ownership check filters on this column.
CREATE INDEX idx_issuers_owner ON issuers (owner_user_id);

CREATE TABLE investors (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL UNIQUE CHECK (char_length(name) BETWEEN 1 AND 200),
    type       text        NOT NULL CHECK (type IN ('VC','PE','FAMILY_OFFICE','BANK','ANGEL')),
    region     text        NOT NULL CHECK (region IN ('NORTH_AMERICA','EUROPE','ASIA_PACIFIC','LATAM','MEA')),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Supabase exposes the public schema over its REST API with a public anon key.
-- RLS on + no policies = that key sees nothing. The backend connects as the table owner, which bypasses RLS.
ALTER TABLE issuers   ENABLE ROW LEVEL SECURITY;
ALTER TABLE investors ENABLE ROW LEVEL SECURITY;
