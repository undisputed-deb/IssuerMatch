-- Messages a consumer gave up on, copied from the "<topic>.DLT" topics so an admin can see why and replay them.
CREATE TABLE dead_letters (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_topic   text        NOT NULL,   -- where the message was originally headed (and where replay sends it)
    message_key    text        NOT NULL,
    payload        text        NOT NULL,   -- exactly as received
    reason         text        NOT NULL,
    dlt_topic      text        NOT NULL,
    dlt_partition  integer     NOT NULL,
    dlt_offset     bigint      NOT NULL,
    received_at    timestamptz NOT NULL DEFAULT now(),
    replayed_at    timestamptz,
    replayed_by    uuid,
    -- the same record redelivered must not create a second row
    CONSTRAINT uq_dead_letter_record UNIQUE (dlt_topic, dlt_partition, dlt_offset)
);
-- The admin screen: what is still waiting, newest first.
CREATE INDEX idx_dead_letters_pending ON dead_letters (received_at DESC) WHERE replayed_at IS NULL;

ALTER TABLE dead_letters ENABLE ROW LEVEL SECURITY;
