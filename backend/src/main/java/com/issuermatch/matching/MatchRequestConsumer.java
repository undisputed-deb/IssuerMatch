package com.issuermatch.matching;

import com.issuermatch.messaging.EventEnvelope;
import com.issuermatch.messaging.InvalidEventException;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 1 of matching: a submitted funding need becomes a match run, and a match.requested event is queued.
 * Idempotent: the same event delivered twice creates exactly one run (processed_events + UNIQUE(need_id, event_id)).
 */
@Component
class MatchRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(MatchRequestConsumer.class);
    static final String CONSUMER = "match-run-creator";

    private final JdbcTemplate db;
    private final JsonMapper json;
    private final ProcessedEvents processed;
    private final OutboxService outbox;

    MatchRequestConsumer(JdbcTemplate db, JsonMapper json, ProcessedEvents processed, OutboxService outbox) {
        this.db = db;
        this.json = json;
        this.processed = processed;
        this.outbox = outbox;
    }

    @KafkaListener(topics = Topics.FUNDING_NEED_SUBMITTED)
    @Transactional
    public void onFundingNeedSubmitted(String message) {
        EventEnvelope event = parse(message);          // never trust the message: validate everything
        UUID needId = event.uuid("needId");

        if (!processed.firstTime(event.eventId(), CONSUMER)) {
            log.info("Duplicate event {} ignored", event.eventId());
            return;
        }
        UUID runId = db.queryForObject(
                "INSERT INTO match_runs(need_id, event_id) VALUES (?, ?) RETURNING id", UUID.class, needId, event.eventId());
        db.update("UPDATE funding_needs SET status = 'MATCHING' WHERE id = ?", needId);
        outbox.publish(Topics.MATCH_REQUESTED, needId.toString(), "MatchRequested",
                Map.of("needId", needId.toString(), "runId", runId.toString()));
        log.info("Created match run {} for need {}", runId, needId);
    }

    private EventEnvelope parse(String message) {
        try {
            EventEnvelope e = json.readValue(message, EventEnvelope.class);
            if (e.eventId() == null || e.payload() == null) {
                throw new InvalidEventException("Event is missing eventId or payload");
            }
            return e;
        } catch (InvalidEventException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidEventException("Unreadable event", e);
        }
    }
}
