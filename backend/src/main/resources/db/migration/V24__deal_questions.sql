-- A private Q&A thread between ONE matched investor and the issuer of ONE funding need.
CREATE TABLE deal_questions (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    need_id     uuid        NOT NULL REFERENCES funding_needs (id) ON DELETE CASCADE,
    investor_id uuid        NOT NULL REFERENCES investors (id) ON DELETE CASCADE,
    asked_by    uuid        NOT NULL,
    question    text        NOT NULL CHECK (char_length(question) BETWEEN 1 AND 2000),
    answer      text        CHECK (char_length(answer) BETWEEN 1 AND 4000),
    answered_by uuid,
    created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    answered_at timestamptz,
    CHECK ((answer IS NULL) = (answered_at IS NULL) AND (answer IS NULL) = (answered_by IS NULL))
);
-- "The threads of this need" and "this investor's thread on this need".
CREATE INDEX idx_deal_questions_need ON deal_questions (need_id, investor_id, created_at);
-- Foreign key lookup when an investor is deleted.
CREATE INDEX idx_deal_questions_investor ON deal_questions (investor_id);

ALTER TABLE deal_questions ENABLE ROW LEVEL SECURITY;
