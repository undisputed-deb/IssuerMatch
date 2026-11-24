package com.issuermatch.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Consumer-side duplicate detection. Call inside the same transaction as the work it protects. */
@Component
public class ProcessedEvents {

    private final JdbcTemplate db;

    ProcessedEvents(JdbcTemplate db) {
        this.db = db;
    }

    /** @return true the first time this consumer sees this event, false for every redelivery */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(UUID eventId, String consumer) {
        return db.update("INSERT INTO processed_events(event_id, consumer) VALUES (?,?) ON CONFLICT DO NOTHING",
                eventId, consumer) == 1;
    }
}
