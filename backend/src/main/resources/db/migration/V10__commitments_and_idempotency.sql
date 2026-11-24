-- A commitment = an investor agrees to put an amount into an issuer's raise (after an analyst approved the match).

CREATE TABLE commitments (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    need_id     uuid          NOT NULL REFERENCES funding_needs (id),
    investor_id uuid          NOT NULL REFERENCES investors (id),
    amount      numeric(18,2) NOT NULL CHECK (amount > 0),
    status      text          NOT NULL CHECK (status IN ('ACCEPTED','PENDING_REVIEW','REJECTED')),
    created_at  timestamptz   NOT NULL DEFAULT now()
);
-- "All commitments for this need" (capacity, reconciliation).
CREATE INDEX idx_commitments_need ON commitments (need_id);
-- "Recent commitments of this investor" (velocity and concentration risk rules).
CREATE INDEX idx_commitments_investor_created ON commitments (investor_id, created_at DESC);

-- Idempotency: the client sends an Idempotency-Key. Same key + same body = same saved answer, never a second write.
CREATE TABLE idempotency_keys (
    idem_key       text        PRIMARY KEY CHECK (char_length(idem_key) BETWEEN 1 AND 128),
    request_hash   text        NOT NULL,   -- SHA-256 of the normalised request body
    status_code    integer,                -- saved response, filled in before the transaction commits
    response_body  text,
    created_at     timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE commitments      ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
