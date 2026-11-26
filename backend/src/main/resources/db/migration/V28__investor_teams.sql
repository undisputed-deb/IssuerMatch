-- Several people can work for one investor firm. One user belongs to at most one firm.
CREATE TABLE investor_members (
    investor_id  uuid        NOT NULL REFERENCES investors (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL UNIQUE,   -- Supabase auth user id
    email        text        CHECK (char_length(email) <= 254),
    display_name text        CHECK (char_length(display_name) <= 100),
    org_role     text        NOT NULL CHECK (org_role IN ('MANAGING_DIRECTOR', 'ANALYST', 'VIEWER')),
    joined_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (investor_id, user_id)
);

-- A copy-link invitation. Only the SHA-256 of the link's token is kept, so reading this table opens nothing.
CREATE TABLE investor_invites (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    investor_id uuid        NOT NULL REFERENCES investors (id) ON DELETE CASCADE,
    email       text        NOT NULL CHECK (char_length(email) <= 254),
    org_role    text        NOT NULL CHECK (org_role IN ('MANAGING_DIRECTOR', 'ANALYST', 'VIEWER')),
    token_hash  text        NOT NULL UNIQUE,
    invited_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at  timestamptz NOT NULL,
    accepted_at timestamptz,
    accepted_by uuid,
    revoked_at  timestamptz
);
-- "Pending invites of my firm", and cascade deletes.
CREATE INDEX idx_investor_invites_investor ON investor_invites (investor_id);

ALTER TABLE investor_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE investor_invites ENABLE ROW LEVEL SECURITY;

-- Every existing owner becomes the managing director of the firm. investors.owner_user_id stays as it is.
INSERT INTO investor_members (investor_id, user_id, display_name, org_role)
SELECT id, owner_user_id, contact_name, 'MANAGING_DIRECTOR' FROM investors WHERE owner_user_id IS NOT NULL
ON CONFLICT DO NOTHING;
