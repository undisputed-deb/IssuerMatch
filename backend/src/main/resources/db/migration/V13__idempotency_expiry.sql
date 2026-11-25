-- Keys are only useful while a client may still be retrying. The cleanup job deletes old ones and needs this index.
CREATE INDEX idx_idempotency_created ON idempotency_keys (created_at);
