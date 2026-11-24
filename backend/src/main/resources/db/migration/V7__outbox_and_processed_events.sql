-- Transactional outbox: the event is written in the SAME database transaction as the business change,
-- then a poller publishes it to Kafka. A crash can never leave "data saved, event lost" or the reverse.
CREATE TABLE outbox (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    topic        text        NOT NULL,
    event_key    text        NOT NULL,   -- Kafka message key (keeps events for one entity in order)
    payload      text        NOT NULL,   -- JSON envelope
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
-- Partial index: the poller only ever looks at unpublished rows, so the index stays tiny as history grows.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

-- Consumer idempotency: a consumer records "I handled event X" in the SAME transaction as the work.
-- Redelivered event -> primary key conflict -> skipped.
CREATE TABLE processed_events (
    event_id     uuid        NOT NULL,
    consumer     text        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

ALTER TABLE outbox           ENABLE ROW LEVEL SECURITY;
ALTER TABLE processed_events ENABLE ROW LEVEL SECURITY;
