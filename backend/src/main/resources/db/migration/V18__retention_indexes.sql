-- The hourly cleanup deletes by age.
CREATE INDEX idx_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX idx_processed_events_at ON processed_events (processed_at);
