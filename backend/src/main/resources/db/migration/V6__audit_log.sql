-- Who did what, when, from where. Append-only by convention: the app never updates or deletes rows.

CREATE TABLE audit_log (
    id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor    text        NOT NULL,  -- user id, or 'system'
    action   text        NOT NULL,
    resource text        NOT NULL,
    ip       text,
    details  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    at       timestamptz NOT NULL DEFAULT now()
);
-- "What did this user do recently?"
CREATE INDEX idx_audit_actor_at ON audit_log (actor, at DESC);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
