package com.issuermatch.matching;

import com.issuermatch.messaging.EventEnvelope;
import com.issuermatch.messaging.EventParser;
import com.issuermatch.messaging.OutboxService;
import com.issuermatch.messaging.ProcessedEvents;
import com.issuermatch.messaging.Topics;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 1 of matching: a submitted funding need becomes a match run, and a match.requested event is queued.
 * Idempotent: the same event delivered twice creates exactly one run (processed_events + UNIQUE(need_id, event_id)).
 */
@Component
class MatchRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchRequestConsumer.class);
    static final String CONSUMER = "match-run-creator";

    private final JdbcTemplate db;
    private final EventParser parser;
    private final ProcessedEvents processed;
    private final OutboxService outbox;

    MatchRequestConsumer(JdbcTemplate db, EventParser parser, ProcessedEvents processed, OutboxService outbox) {
        this.db = db;
        this.parser = parser;
        this.processed = processed;
        this.outbox = outbox;
    }

    @KafkaListener(topics = Topics.FUNDING_NEED_SUBMITTED)
    @Transactional
    public void onFundingNeedSubmitted(String message) {
        EventEnvelope event = parser.parse(message);   // never trust the message: validate everything
        UUID needId = event.uuid("needId");

        if (!processed.firstTime(event.eventId(), CONSUMER)) {
            log.info("Duplicate event {} ignored", event.eventId());
            return;
        }
        UUID runId = db.queryForObject(
                "INSERT INTO match_runs(need_id, event_id, requested_by) VALUES (?, ?, ?) RETURNING id", UUID.class,
                needId, event.eventId(), event.optionalUuid("requestedBy"));
        db.update("UPDATE funding_needs SET status = 'MATCHING' WHERE id = ?", needId);
        outbox.publish(Topics.MATCH_REQUESTED, needId.toString(), "MatchRequested",
                Map.of("needId", needId.toString(), "runId", runId.toString()));
        log.info("Created match run {} for need {}", runId, needId);
    }
}
