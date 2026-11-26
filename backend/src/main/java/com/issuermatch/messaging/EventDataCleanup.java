package com.issuermatch.messaging;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the messaging tables bounded. Published outbox rows are finished work. Processed-event ids only matter
 * while Kafka could still redeliver the event, so they are kept far longer than the broker's retention (7 days).
 * Unpublished outbox rows are never touched.
 */
@Component
class EventDataCleanup {

    private static final Logger log = LoggerFactory.getLogger(EventDataCleanup.class);

    private final JdbcTemplate db;
    private final Duration outboxAge;
    private final Duration processedAge;

    EventDataCleanup(JdbcTemplate db, @Value("${app.retention.outbox:7d}") Duration outboxAge,
                     @Value("${app.retention.processed-events:30d}") Duration processedAge) {
        this.db = db;
        this.outboxAge = outboxAge;
        this.processedAge = processedAge;
    }

    @Scheduled(fixedDelayString = "${app.retention.interval:1h}", initialDelayString = "${app.retention.interval:1h}")
    void run() {
        try {
            int removed = purge();
            if (removed > 0) log.info("Removed {} old messaging row(s)", removed);
        } catch (Exception e) {
            log.warn("Messaging cleanup failed, will retry: {}", e.toString());
        }
    }

    int purge() {
        return db.update("DELETE FROM outbox WHERE published_at < now() - make_interval(secs => ?)", outboxAge.toSeconds())
                + db.update("DELETE FROM processed_events WHERE processed_at < now() - make_interval(secs => ?)",
                        processedAge.toSeconds());
    }
}
