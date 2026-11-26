package com.issuermatch.commitment;

import com.issuermatch.messaging.EventEnvelope;
import com.issuermatch.messaging.EventParser;
import com.issuermatch.messaging.InvalidEventException;
import com.issuermatch.messaging.Topics;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Writes the event ledger from Kafka alone. It deliberately does NOT look at the commitments table: the ledger
 * records what the events say, and reconciliation compares the two. Idempotent through the event_id primary key.
 * Messages are untrusted: anything malformed goes to "<topic>.DLT".
 */
@Component
class CommitmentLedgerConsumer {

    /** The only event type allowed on each topic, and what it means in the ledger. */
    private static final Map<String, Kind> KINDS = Map.of(
            Topics.COMMITMENT_REQUESTED, new Kind("CommitmentRequested", "REQUESTED"),
            Topics.COMMITMENT_ACCEPTED, new Kind("CommitmentAccepted", "ACCEPTED"),
            Topics.COMMITMENT_REJECTED, new Kind("CommitmentRejected", "REJECTED"));

    private static final int MAX_REASON = 2000;

    private record Kind(String eventType, String ledgerType) {}

    private final EventParser parser;
    private final JdbcTemplate db;

    CommitmentLedgerConsumer(EventParser parser, JdbcTemplate db) {
        this.parser = parser;
        this.db = db;
    }

    @KafkaListener(topics = {Topics.COMMITMENT_REQUESTED, Topics.COMMITMENT_ACCEPTED, Topics.COMMITMENT_REJECTED},
            concurrency = "3") // one thread per topic partition; inserts are independent and idempotent
    public void record(ConsumerRecord<String, String> message) {
        EventEnvelope event = parser.parse(message.value());
        Kind kind = KINDS.get(message.topic());
        if (kind == null || !kind.eventType().equals(event.type()) || event.occurredAt() == null) {
            throw new InvalidEventException("Event " + event.eventId() + " does not belong on topic " + message.topic());
        }
        Object reason = event.payload().get("reason");
        String text = reason == null ? null : String.valueOf(reason);
        if (text != null && text.length() > MAX_REASON) {
            text = text.substring(0, MAX_REASON);
        }
        db.update("INSERT INTO commitment_events(event_id, commitment_id, need_id, investor_id, amount, type, reason, occurred_at)"
                        + " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (event_id) DO NOTHING",
                event.eventId(), event.uuid("commitmentId"), event.uuid("needId"), event.uuid("investorId"), amount(event),
                kind.ledgerType(), text, Timestamp.from(event.occurredAt()));
    }

    private static BigDecimal amount(EventEnvelope event) {
        try {
            BigDecimal value = new BigDecimal(String.valueOf(event.payload().get("amount")));
            if (value.signum() > 0 && value.scale() <= 2 && value.precision() - value.scale() <= 16) {
                return value;
            }
        } catch (NumberFormatException e) {
            // falls through to the rejection below
        }
        throw new InvalidEventException("Event " + event.eventId() + " has an invalid amount");
    }
}
