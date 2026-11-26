-- An investor profile can belong to a self-registered user, the same way issuers already do.
-- NULL = a profile entered by staff that nobody logs in as. UNIQUE = one profile per user.
ALTER TABLE investors ADD COLUMN owner_user_id uuid;
ALTER TABLE investors ADD CONSTRAINT uq_investors_owner UNIQUE (owner_user_id);  -- also the index for "my profile"
ALTER TABLE investors ENABLE ROW LEVEL SECURITY;  -- already on since V2; stated again so this table's rule is visible here
