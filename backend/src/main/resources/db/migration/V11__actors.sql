-- Who did it. Filled from the verified JWT subject, never from request data.

-- Idempotency keys belong to the caller who made them: user B reusing user A's key must not see A's response.
ALTER TABLE idempotency_keys ADD COLUMN actor uuid;
UPDATE idempotency_keys SET actor = '00000000-0000-0000-0000-000000000000' WHERE actor IS NULL;
ALTER TABLE idempotency_keys ALTER COLUMN actor SET NOT NULL;
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (actor, idem_key);

ALTER TABLE commitments ADD COLUMN created_by uuid;     -- analyst or admin who recorded it
ALTER TABLE match_runs  ADD COLUMN requested_by uuid;   -- issuer (or admin) whose submission triggered the run
